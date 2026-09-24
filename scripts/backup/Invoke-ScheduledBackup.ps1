param([Parameter(Mandatory = $true)][string]$ConfigPath)

$ErrorActionPreference = 'Stop'
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$credential = Import-Clixml -LiteralPath $config.CredentialPath
if (-not ($credential -is [System.Management.Automation.PSCredential])) { throw 'Backup credential could not be opened by this Windows account.' }
$keys = @('PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD','PGSSLMODE','PGCHANNELBINDING')
$old = @{}
foreach ($key in $keys) { $old[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    $env:PGHOST = $config.Host
    $env:PGPORT = [string]$config.Port
    $env:PGDATABASE = $config.Database
    $env:PGUSER = $credential.UserName
    $env:PGPASSWORD = $credential.GetNetworkCredential().Password
    $env:PGSSLMODE = $config.SslMode
    $env:PGCHANNELBINDING = $config.ChannelBinding
    & (Join-Path $PSScriptRoot 'Export-Database.ps1') -OutputDirectory $config.OffsiteDirectory -PgDump $config.PgDump -PgRestore $config.PgRestore
    if (-not $?) { throw 'Scheduled database export failed.' }
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $old[$key], 'Process') }
}
