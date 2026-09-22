param([switch]$BootstrapAdmin)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$localEnvPath = Join-Path $repoRoot '.env'
$environmentNames = @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'SPRING_PROFILES_ACTIVE', 'BOOTSTRAP_ADMIN_ENABLED', 'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD')
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
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -or $env:DB_PASSWORD.StartsWith('REPLACE_') -or
        [string]::IsNullOrWhiteSpace($env:JWT_SECRET) -or $env:JWT_SECRET.StartsWith('REPLACE_')) {
        throw 'Set DB_PASSWORD and JWT_SECRET in the existing root .env. This script never overwrites existing configuration.'
    }
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:BOOTSTRAP_ADMIN_ENABLED = 'false'
    if ($BootstrapAdmin) {
        $env:BOOTSTRAP_ADMIN_USERNAME = Read-Host 'Initial admin username (3–100 ASCII letters/digits/._-)'
        $securePassword = Read-Host 'Initial admin password (at least 14 characters; maximum 72 UTF-8 bytes)' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try { $env:BOOTSTRAP_ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $securePassword.Dispose() }
        $env:BOOTSTRAP_ADMIN_ENABLED = 'true'
    }
    Push-Location $repoRoot
    try {
        & docker compose up -d --wait postgres
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL could not start. Check Docker Desktop.' }
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
