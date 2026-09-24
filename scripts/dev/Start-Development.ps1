param([switch]$BootstrapAdmin)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$localEnvPath = Join-Path $repoRoot '.env'
$environmentNames = @('SERVER_PORT', 'DB_URL', 'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'SPRING_PROFILES_ACTIVE', 'BOOTSTRAP_ADMIN_ENABLED', 'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function New-RandomSecret {
    $bytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes); return [Convert]::ToBase64String($bytes) }
    finally { $generator.Dispose() }
}

try {
    if (-not (Test-Path -LiteralPath $localEnvPath)) {
        $configuration = @('DB_HOST=localhost', 'DB_PORT=5432', 'DB_NAME=alanan', 'DB_USERNAME=alanan_app', ('DB_PASSWORD=' + (New-RandomSecret)), ('JWT_SECRET=' + (New-RandomSecret)))
        [IO.File]::WriteAllLines($localEnvPath, $configuration, [Text.UTF8Encoding]::new($false))
        Write-Host 'Created ignored local configuration with random development secrets.'
    }
    foreach ($line in [IO.File]::ReadAllLines($localEnvPath)) {
        if ($line -match '^([A-Z_]+)=(.*)$' -and $environmentNames -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    # Neon's transaction pooler discards session search_path settings between queries.
    # This application uses a small Hikari pool and needs a direct session connection.
    if ($env:DB_URL -match '(?i)^jdbc:postgresql://ep-[a-z0-9-]+-pooler\..*\.neon\.tech(?::\d+)?/') {
        $env:DB_URL = $env:DB_URL -replace '(?i)(^jdbc:postgresql://ep-[a-z0-9-]+)-pooler(\.)', '$1$2'
        Write-Host 'Using the direct Neon endpoint for persistent database sessions.'
    }
    if ($env:DB_HOST -match '(?i)^ep-[a-z0-9-]+-pooler\..*\.neon\.tech$') {
        $env:DB_HOST = $env:DB_HOST -replace '(?i)(^ep-[a-z0-9-]+)-pooler(\.)', '$1$2'
        Write-Host 'Using the direct Neon endpoint for persistent database sessions.'
    }
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -or $env:DB_PASSWORD.StartsWith('REPLACE_') -or
        [string]::IsNullOrWhiteSpace($env:JWT_SECRET) -or $env:JWT_SECRET.StartsWith('REPLACE_')) {
        throw 'Set DB_PASSWORD and JWT_SECRET in the existing root .env. This script never overwrites existing configuration.'
    }
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    if ($BootstrapAdmin -or $env:BOOTSTRAP_ADMIN_ENABLED -eq 'true') {
        if ([string]::IsNullOrWhiteSpace($env:BOOTSTRAP_ADMIN_USERNAME) -or [string]::IsNullOrWhiteSpace($env:BOOTSTRAP_ADMIN_PASSWORD)) {
            throw 'Set BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD in the ignored root .env before enabling admin bootstrap.'
        }
        $env:BOOTSTRAP_ADMIN_ENABLED = 'true'
    } else {
        $env:BOOTSTRAP_ADMIN_ENABLED = 'false'
    }
    Push-Location $repoRoot
    try {
        if ([string]::IsNullOrWhiteSpace($env:DB_URL) -and ($env:DB_HOST -in @('localhost', '127.0.0.1', '::1', ''))) {
            & docker compose up -d --wait postgres
            if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL could not start. Check Docker Desktop.' }
        } else {
            Write-Host 'Using the configured external database; local PostgreSQL startup skipped.'
        }
        $mavenPath = Join-Path $repoRoot '.tools\apache-maven-3.9.11\bin\mvn.cmd'
        if (-not (Test-Path -LiteralPath $mavenPath)) { $mavenPath = (Get-Command mvn -ErrorAction Stop).Source }
        Push-Location (Join-Path $repoRoot 'backend')
        try {
            & $mavenPath '-Dmaven.repo.local=../.tools/m2' 'spring-boot:run'
            if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed. See the application output above.' }
        } finally { Pop-Location }
    } finally { Pop-Location }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
