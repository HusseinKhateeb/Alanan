param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [string]$TargetConfig = '.env.restore'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$source = (Resolve-Path -LiteralPath $Archive).ProviderPath
$configPath = if ([IO.Path]::IsPathRooted($TargetConfig)) { $TargetConfig } else { Join-Path $root $TargetConfig }
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'Create an ignored .env.restore for a separate empty database first.' }

function Read-Settings([string]$path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}
function Parse-Database([string]$value) {
    if (-not $value.StartsWith('jdbc:postgresql://', [StringComparison]::OrdinalIgnoreCase)) { throw 'Database URL must be a PostgreSQL JDBC URL.' }
    $uri = [uri]$value.Substring(5)
    if (-not $uri.Host -or $uri.AbsolutePath.Length -lt 2) { throw 'Database URL must include a host and database name.' }
    return $uri
}

$live = Read-Settings (Join-Path $root '.env')
$target = Read-Settings $configPath
foreach ($key in @('DB_URL','DB_USERNAME','DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace($target[$key])) { throw "Target config is missing $key." }
}
$liveUri = Parse-Database $live['DB_URL']
$targetUri = Parse-Database $target['DB_URL']
if ($targetUri.Host -match '-pooler\.') { throw 'Use a direct PostgreSQL endpoint for the restore target, not a pooler.' }
$liveHost = $liveUri.Host -replace '-pooler\.', '.'
$targetHost = $targetUri.Host -replace '-pooler\.', '.'
$livePort = if ($liveUri.Port -gt 0) { $liveUri.Port } else { 5432 }
$targetPort = if ($targetUri.Port -gt 0) { $targetUri.Port } else { 5432 }
if ($liveHost -eq $targetHost -and $livePort -eq $targetPort -and $liveUri.AbsolutePath -eq $targetUri.AbsolutePath) {
    throw 'Target is the current live database. Restore to a separate empty database instead.'
}
$query = @{}
foreach ($part in $targetUri.Query.TrimStart('?').Split('&')) {
    $pair = $part.Split('=', 2)
    if ($pair.Count -eq 2) { $query[[uri]::UnescapeDataString($pair[0])] = [uri]::UnescapeDataString($pair[1]) }
}
$bin = Join-Path $root '.tools\pgsql\bin'
foreach ($command in @('psql.exe','pg_restore.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $bin $command))) { throw 'PostgreSQL 18 client tools are missing from .tools/pgsql/bin.' }
}

& (Join-Path $PSScriptRoot 'Test-Restore.ps1') -Archive $source
if ($LASTEXITCODE -ne 0) { throw 'Disposable restore test did not pass.' }

$env:PGHOST = $targetUri.Host
$env:PGPORT = [string]$targetPort
$env:PGDATABASE = $targetUri.AbsolutePath.TrimStart('/')
$env:PGUSER = $target['DB_USERNAME']
$env:PGPASSWORD = $target['DB_PASSWORD']
$env:PGSSLMODE = if ($query.ContainsKey('sslmode')) { $query['sslmode'] } else { 'require' }
if ($query.ContainsKey('channelBinding')) { $env:PGCHANNELBINDING = $query['channelBinding'] }

$psql = Join-Path $bin 'psql.exe'
$restore = Join-Path $bin 'pg_restore.exe'
$existing = & $psql -X -A -t -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the target database.' }
if ([int]($existing | Select-Object -Last 1) -ne 0) { throw 'Target database is not empty. No data was changed.' }

& $restore --exit-on-error --single-transaction --no-owner --no-acl -d $env:PGDATABASE $source
if ($LASTEXITCODE -ne 0) { throw 'Restore failed. Inspect the separate target database before trying again.' }
$counts = & $psql -X -A -t -v ON_ERROR_STOP=1 -c "SELECT 'purchases=' || (SELECT count(*) FROM purchase_invoices) || ', sales=' || (SELECT count(*) FROM sales)"
if ($LASTEXITCODE -ne 0) { throw 'Restore finished but target verification failed.' }
Write-Output "Separate database restored: $counts"
Write-Output 'The running app still uses its original database. Switch the root .env only after reviewing the recovered data.'
