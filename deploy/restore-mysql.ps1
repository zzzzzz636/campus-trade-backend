param(
    [Parameter(Mandatory=$true)][string]$Project,
    [Parameter(Mandatory=$true)][string]$EnvFile,
    [Parameter(Mandatory=$true)][string]$InputFile,
    [Parameter(Mandatory=$true)][string]$ConfirmDatabaseName
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $InputFile)) { throw "Backup file does not exist: $InputFile" }
$compose = Join-Path $PSScriptRoot '..\docker-compose.dev.yml'
$prefix = @('compose','-p',$Project,'--env-file',$EnvFile,'-f',$compose,'exec','-T','mysql','sh','-c')
$actual = (& docker @prefix 'printf "%s" "$MYSQL_DATABASE"').Trim()
if ($LASTEXITCODE -ne 0 -or $actual -ne $ConfirmDatabaseName) {
    throw "Database confirmation mismatch; expected '$ConfirmDatabaseName', got '$actual'"
}
Get-Content -LiteralPath $InputFile -Raw -Encoding utf8 |
    & docker @prefix 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"'
if ($LASTEXITCODE -ne 0) { throw "MySQL restore failed with exit code $LASTEXITCODE" }
Write-Output "Restore completed for $actual"
