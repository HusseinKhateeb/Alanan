param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$PgDump = 'pg_dump',
    [string]$PgRestore = 'pg_restore'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:PGHOST) -or [string]::IsNullOrWhiteSpace($env:PGDATABASE) -or
    [string]::IsNullOrWhiteSpace($env:PGUSER) -or [string]::IsNullOrWhiteSpace($env:PGPASSWORD)) {
    throw 'Set PGHOST, PGDATABASE, PGUSER and PGPASSWORD in the current process before exporting.'
}
if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    throw 'OutputDirectory must be an existing directory on the intended backup destination.'
}
$destination = (Resolve-Path -LiteralPath $OutputDirectory).ProviderPath
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$identifier = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$filename = "alanan-$stamp-$identifier.dump"
$temporary = Join-Path $destination (".$filename.partial")
$archive = Join-Path $destination $filename
$checksum = "$archive.sha256"
try {
    & $PgDump --format=custom --no-owner --no-acl --file=$temporary
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed; no backup was published.' }
    & $PgRestore --list $temporary *> $null
    if ($LASTEXITCODE -ne 0) { throw 'pg_restore could not read the archive; no backup was published.' }
    $hash = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    Move-Item -LiteralPath $temporary -Destination $archive -ErrorAction Stop
    [IO.File]::WriteAllText($checksum, "$hash  $filename`n", [Text.UTF8Encoding]::new($false))
    Write-Output "Backup archive readable: $archive"
    Write-Output "SHA-256: $hash"
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}
