param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$MailpitUrl = 'http://127.0.0.1:8025',
    [switch]$ConfirmIsolatedEnvironment
)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmIsolatedEnvironment -or $BaseUrl -ne 'http://127.0.0.1:18080') {
    throw 'This script creates test accounts and orders. Confirm the isolated acceptance app on port 18080.'
}

function PostJson([string]$path, [object]$body, [hashtable]$headers = @{}) {
    Invoke-RestMethod -Method Post "$BaseUrl$path" -ContentType 'application/json' -Headers $headers -Body ($body | ConvertTo-Json -Compress -Depth 8)
}

function WaitFor([scriptblock]$condition, [string]$label) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $value = & $condition
        if ($value) { return $value }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $label"
}

function Register([string]$nickname) {
    $email = "$nickname-$([guid]::NewGuid().ToString('N').Substring(0,10))@example.invalid"
    $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
    $send = PostJson '/api/auth/verification-codes' @{email=$email}
    if ($send.deliveryMode -ne 'SMTP') { throw "Registration did not use SMTP for $nickname" }
    $code = WaitFor {
        $mail = Invoke-RestMethod "$MailpitUrl/api/v1/messages?limit=20"
        $entry = $mail.messages | Where-Object { $_.To[0].Address -eq $email } | Select-Object -First 1
        if (-not $entry) { return $null }
        $message = Invoke-RestMethod "$MailpitUrl/api/v1/message/$($entry.ID)"
        [regex]::Match($message.Text, '\b[0-9]{6}\b').Value
    } "SMTP test mail for $nickname"
    $user = PostJson '/api/auth/register' @{email=$email;password=$password;nickname=$nickname;code=$code}
    $login = PostJson '/api/auth/login' @{email=$email;password=$password}
    if (-not $login.accessToken) { throw "Login failed for $nickname" }
    @{id=$user.id;headers=@{Authorization="Bearer $($login.accessToken)"}}
}

$seller = Register 'seller'
$buyer = Register 'buyer'
$term = 'campusdemo' + [guid]::NewGuid().ToString('N').Substring(0,8)
$product = PostJson '/api/products' @{
    categoryId=1; title="$term used textbook"; description='isolated end-to-end test product'
    price=58.00; conditionLevel='GOOD'
} $seller.headers
$productId = [long]$product.id

$indexed = WaitFor {
    $result = Invoke-RestMethod "$BaseUrl/api/products/search?q=$term&page=1&size=10"
    if ($result.items.id -contains $productId) { $true } else { $null }
} 'Elasticsearch product indexing'
$detail = Invoke-RestMethod "$BaseUrl/api/products/$productId"
if ($detail.id -ne $productId) { throw 'Product detail mismatch' }
Invoke-RestMethod -Method Post "$BaseUrl/api/products/$productId/favorite" -Headers $buyer.headers | Out-Null
$hot = WaitFor {
    $items = Invoke-RestMethod "$BaseUrl/api/products/hot?size=50"
    $entry = $items | Where-Object { $_.productId -eq $productId } | Select-Object -First 1
    if ($entry -and $entry.score -ge 2) { $entry } else { $null }
} 'Kafka hot ranking for view and favorite'

$key = [guid]::NewGuid().ToString('N')
$order = PostJson '/api/orders' @{
    productId=$productId; requestKey=$key; fulfillmentType='CAMPUS_HANDOFF'
    handoffLocation='campus library'
} $buyer.headers
$orderId = [long]$order.id
$notification = WaitFor {
    $count = Invoke-RestMethod "$BaseUrl/api/notifications/unread-count" -Headers $seller.headers
    if ($count.count -ge 1) { $true } else { $null }
} 'RabbitMQ seller notification'
$sales = Invoke-RestMethod "$BaseUrl/api/orders/sales/page?page=1&size=20" -Headers $seller.headers
$purchases = Invoke-RestMethod "$BaseUrl/api/orders/purchases/page?page=1&size=20" -Headers $buyer.headers
if ($sales.items.id -notcontains $orderId -or $purchases.items.id -notcontains $orderId) {
    throw 'Order not visible to its two participants'
}
$accepted = PostJson "/api/orders/$orderId/accept" @{} $seller.headers
$completed = PostJson "/api/orders/$orderId/complete" @{} $buyer.headers
$sold = Invoke-RestMethod "$BaseUrl/api/products/$productId" -Headers $seller.headers
if ($completed.status -ne 'COMPLETED' -or $sold.status -ne 'SOLD') {
    throw "Incorrect terminal states: order=$($completed.status) product=$($sold.status)"
}

[pscustomobject]@{
    registeredUsers = 2; productId = $productId; indexed = [bool]$indexed
    hotScoreBeforeSale = $hot.score; sellerNotified = [bool]$notification
    orderId = $orderId; acceptedStatus = $accepted.status
    completedStatus = $completed.status; productStatus = $sold.status
} | ConvertTo-Json -Compress
