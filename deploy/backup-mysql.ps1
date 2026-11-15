param(
    [Parameter(Mandatory=$true)][string]$Project,
    [Parameter(Mandatory=$true)][string]$EnvFile,
    [Parameter(Mandatory=$true)][string]$OutputFile
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputFile) { throw "Backup file already exists: $OutputFile" }
$compose = Join-Path $PSScriptRoot '..\docker-compose.dev.yml'
$partial = "$OutputFile.partial"
if (Test-Path -LiteralPath $partial) { throw "Partial file already exists: $partial" }
$args = @('compose','-p',$Project,'--env-file',$EnvFile,'-f',$compose,'exec','-T','mysql','sh','-c',
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u "$MYSQL_USER" --single-transaction --no-tablespaces "$MYSQL_DATABASE"')
try {
    & docker @args | Set-Content -LiteralPath $partial -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with exit code $LASTEXITCODE" }
    if ((Get-Item -LiteralPath $partial).Length -lt 100) { throw 'Backup file is unexpectedly small' }
    Move-Item -LiteralPath $partial -Destination $OutputFile
    Write-Output "Backup saved to $OutputFile"
} catch {
    Write-Error "Backup failed. Partial output remains at $partial. $($_.Exception.Message)"
    throw
}
