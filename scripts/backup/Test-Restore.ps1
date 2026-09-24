param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [string]$Docker = 'docker',
    [string]$Image = 'postgres:17.9-alpine'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) { throw 'Archive not found.' }
$source = (Resolve-Path -LiteralPath $Archive).ProviderPath
$checksumPath = "$source.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) { throw 'Matching .sha256 file not found.' }
$expectedLine = (Get-Content -LiteralPath $checksumPath -TotalCount 1)
$expected = '^([a-fA-F0-9]{64})  ' + [regex]::Escape([IO.Path]::GetFileName($source)) + '$'
if ($expectedLine -notmatch $expected) { throw 'Checksum file is invalid or names another archive.' }
$actualHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if ($actualHash -ne $Matches[1]) { throw 'Archive SHA-256 does not match. Restore aborted.' }
$name = 'alanan-restore-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$testPassword = [Guid]::NewGuid().ToString('N')
$created = $false
try {
    & $Docker run --detach --rm --name $name --env "POSTGRES_PASSWORD=$testPassword" --env 'POSTGRES_DB=alanan_restore' $Image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL container could not start.' }
    $created = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        & $Docker exec $name pg_isready -U postgres -d alanan_restore *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready.' }
    & $Docker cp $source "${name}:/tmp/alanan.dump"
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy archive into disposable container.' }
    & $Docker exec $name pg_restore --exit-on-error --no-owner --no-acl -U postgres -d alanan_restore /tmp/alanan.dump
    if ($LASTEXITCODE -ne 0) { throw 'Restore failed in disposable PostgreSQL.' }
    $check = & $Docker exec $name psql -U postgres -d alanan_restore -At -c "SELECT count(*) FROM flyway_schema_history WHERE success AND to_regclass('public.purchase_invoices') IS NOT NULL AND to_regclass('public.sales') IS NOT NULL"
    if ($LASTEXITCODE -ne 0 -or [int]$check -lt 1) { throw 'Restored schema verification failed.' }
    $counts = & $Docker exec $name psql -U postgres -d alanan_restore -At -c "SELECT 'purchases=' || (SELECT count(*) FROM purchase_invoices) || ', sales=' || (SELECT count(*) FROM sales)"
    if ($LASTEXITCODE -ne 0) { throw 'Restored data verification failed.' }
    Write-Output "Disposable restore passed: $counts"
} finally {
    if ($created) { & $Docker rm --force $name *> $null }
}
