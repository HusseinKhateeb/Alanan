param([int]$WebPort = 8090, [switch]$UseExistingBackend, [switch]$RestartBackend, [switch]$QuickStart)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Create the ignored root .env first.' }
if ($WebPort -lt 1 -or $WebPort -gt 65535) { throw 'WebPort must be between 1 and 65535.' }
if ($UseExistingBackend -and $RestartBackend) { throw 'Choose either -UseExistingBackend or -RestartBackend.' }
if ($QuickStart -and $RestartBackend) { throw 'QuickStart cannot be combined with RestartBackend; rebuild to apply code changes.' }
$names = @('JAVA_HOME','SERVER_PORT','SERVER_ADDRESS','DB_URL','DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD',
    'JWT_SECRET','SPRING_PROFILES_ACTIVE','BOOTSTRAP_ADMIN_ENABLED','BOOTSTRAP_ADMIN_USERNAME',
    'BOOTSTRAP_ADMIN_PASSWORD','AUTH_COOKIE_SECURE','PG_DUMP_COMMAND','PG_RESTORE_COMMAND',
    'ALANAN_WEB_PORT','ALANAN_BACKEND_PORT','ALANAN_STATIC_DIR')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Test-LocalDatabasePort {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $connection = $client.BeginConnect('127.0.0.1', 15433, $null, $null)
        if (-not $connection.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($connection)
        return $true
    } catch { return $false }
    finally { $client.Dispose() }
}

try {
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line -match '^([A-Z_]+)=(.*)$' -and $names -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD) -or [string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
        throw 'DB_PASSWORD and JWT_SECRET are required in the ignored root .env.'
    }
    if ($env:DB_URL -match '(?i)^jdbc:postgresql://ep-[a-z0-9-]+-pooler\..*\.neon\.tech(?::\d+)?/') {
        $env:DB_URL = $env:DB_URL -replace '(?i)(^jdbc:postgresql://ep-[a-z0-9-]+)-pooler(\.)', '$1$2'
    }
    if ($env:DB_HOST -match '(?i)^ep-[a-z0-9-]+-pooler\..*\.neon\.tech$') {
        $env:DB_HOST = $env:DB_HOST -replace '(?i)(^ep-[a-z0-9-]+)-pooler(\.)', '$1$2'
    }
    if ($env:DB_URL -match '(?i)^jdbc:postgresql://(127\.0\.0\.1|localhost):15433/' -and
        -not (Test-LocalDatabasePort)) {
        $docker = Get-Command docker.exe -ErrorAction SilentlyContinue
        if (-not $docker) { throw 'Docker Desktop is required for the local database but docker.exe was not found.' }
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
        $dockerVersion = & $docker.Source info '--format' '{{.ServerVersion}}' 2>$null
        if ([string]::IsNullOrWhiteSpace($dockerVersion)) {
            $desktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
            if (-not (Test-Path -LiteralPath $desktop)) { throw 'Start Docker Desktop, then try Start app again.' }
            Write-Host 'Starting Docker Desktop for the local database...'
            Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
            $dockerReady = $false
            for ($attempt = 0; $attempt -lt 45; $attempt++) {
                Start-Sleep -Seconds 2
                $dockerVersion = & $docker.Source info '--format' '{{.ServerVersion}}' 2>$null
                if (-not [string]::IsNullOrWhiteSpace($dockerVersion)) { $dockerReady = $true; break }
            }
            if (-not $dockerReady) { throw 'Docker Desktop did not become ready. Open Docker Desktop and try again.' }
        }
        $databaseContainer = 'alanan-local-postgres18'
        $containerState = & $docker.Source inspect '--format' '{{.State.Status}}' $databaseContainer 2>$null
        if ([string]::IsNullOrWhiteSpace($containerState)) { throw "Local database container $databaseContainer was not found. No new database was created." }
        if ($containerState.Trim() -ne 'running') {
            Write-Host 'Starting the existing local PostgreSQL container...'
            $startedContainer = & $docker.Source start $databaseContainer 2>$null
            if ($startedContainer -notcontains $databaseContainer) { throw "Could not start $databaseContainer. Check Docker Desktop." }
        }
        $databaseReady = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            $databaseStatus = & $docker.Source exec $databaseContainer pg_isready '-h' '127.0.0.1' '-p' '5432' 2>$null
            if ($databaseStatus -match 'accepting connections') { $databaseReady = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $databaseReady) { throw 'Local PostgreSQL did not become ready. Check the database container logs.' }
        Write-Host 'Local PostgreSQL is ready.'
        } finally { $ErrorActionPreference = $previousPreference }
    }
    $backendPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
    if ($backendPort -eq $WebPort) { throw 'The frontend and backend ports must differ.' }
    $webListener = Get-NetTCPConnection -LocalPort $WebPort -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -eq '127.0.0.1' } | Select-Object -First 1
    $reuseWeb = $false
    if ($webListener) {
        $webProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($webListener.OwningProcess)"
        $webScript = Join-Path $root 'scripts\deploy\serve-laptop-app.mjs'
        if ($webProcess -and $webProcess.Name -eq 'node.exe' -and
            $webProcess.CommandLine.Contains($webScript)) {
            $reuseWeb = $true
        } else {
            throw "Port $WebPort is in use by another program. Stop it or choose another WebPort."
        }
    }
    $backendListener = Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -eq '127.0.0.1' } | Select-Object -First 1
    $reuseBackend = $false
    if ($backendListener) {
        $backendProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($backendListener.OwningProcess)"
        $jar = Join-Path $root 'backend\target\accounting-0.1.0-SNAPSHOT.jar'
        if ($backendProcess -and $backendProcess.Name -eq 'java.exe' -and
            $backendProcess.CommandLine.Contains($jar)) {
            $reuseBackend = $true
        } else {
            throw "Port $backendPort is in use by another program. Stop it before starting Alanan Anywhere."
        }
    } elseif ($UseExistingBackend) {
        throw "No backend is running on port $backendPort. Run without -UseExistingBackend."
    }
    $env:SPRING_PROFILES_ACTIVE = 'prod'
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:BOOTSTRAP_ADMIN_ENABLED = 'false'
    $env:AUTH_COOKIE_SECURE = 'true'
    $env:ALANAN_WEB_PORT = [string]$WebPort
    $env:ALANAN_BACKEND_PORT = [string]$backendPort
    $env:ALANAN_STATIC_DIR = Join-Path $root 'frontend\dist'
    $postgresBins = @((Join-Path $root '.tools\pgsql\bin'), (Join-Path $root '.tools\postgresql-18\pgsql\bin'),
        'C:\Program Files\PostgreSQL\18\bin')
    foreach ($bin in $postgresBins) {
        if ((Test-Path -LiteralPath (Join-Path $bin 'pg_dump.exe')) -and
            (Test-Path -LiteralPath (Join-Path $bin 'pg_restore.exe'))) {
            if (-not $env:PG_DUMP_COMMAND) { $env:PG_DUMP_COMMAND = Join-Path $bin 'pg_dump.exe' }
            if (-not $env:PG_RESTORE_COMMAND) { $env:PG_RESTORE_COMMAND = Join-Path $bin 'pg_restore.exe' }
            break
        }
    }

    $maven = Join-Path $root '.tools\apache-maven-3.9.11\bin\mvn.cmd'
    if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { '' }
    if (-not (Test-Path -LiteralPath $java)) { $java = 'C:\Program Files\Java\jdk-21\bin\java.exe' }
    if (-not (Test-Path -LiteralPath $java)) { $java = (Get-Command java.exe -ErrorAction Stop).Source }
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    if (-not (Test-Path -LiteralPath $java)) { throw 'Java 21 was not found. Set JAVA_HOME.' }
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
    $runDir = Join-Path $root '.tools\anywhere'
    [IO.Directory]::CreateDirectory($runDir) | Out-Null

    Push-Location $root
    try {
        $builtFrontend = Join-Path $root 'frontend\dist\index.html'
        if ($QuickStart -and (Test-Path -LiteralPath $builtFrontend)) {
            Write-Host 'Using the last built frontend. Choose Apply code changes to rebuild.'
        } else {
            & npm.cmd --prefix frontend run build
            if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        }
        if ($RestartBackend -and $reuseBackend) {
            Stop-Process -Id $backendListener.OwningProcess -ErrorAction Stop
            for ($attempt = 0; $attempt -lt 20; $attempt++) {
                if (-not (Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction SilentlyContinue)) { break }
                Start-Sleep -Milliseconds 500
            }
            if (Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction SilentlyContinue) {
                throw "Backend port $backendPort did not become free after stopping Alanan."
            }
            $reuseBackend = $false
        }
        if (-not $reuseBackend) {
            $builtBackend = Join-Path $root 'backend\target\accounting-0.1.0-SNAPSHOT.jar'
            if ($QuickStart -and (Test-Path -LiteralPath $builtBackend)) {
                Write-Host 'Using the last built backend. Choose Apply code changes to rebuild.'
            } else {
                & $maven '-Dmaven.repo.local=.tools/m2' '-f' 'backend/pom.xml' '-DskipTests' 'package' '-q'
                if ($LASTEXITCODE -ne 0) { throw 'Backend package failed.' }
            }
        }
    } finally { Pop-Location }

    $backendPid = $null
    if ($reuseBackend) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$backendPort/actuator/health" -TimeoutSec 10
            if ($health.status -ne 'UP') { throw 'Backend is not healthy.' }
        } catch { throw "The Alanan backend on port $backendPort is not healthy. Check its log or stop it before restarting." }
        $backendPid = $backendListener.OwningProcess
    } else {
        $jar = Join-Path $root 'backend\target\accounting-0.1.0-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $jar)) { throw 'Packaged backend JAR was not found.' }
        $backend = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jar + '"')) -WorkingDirectory (Join-Path $root 'backend') `
            -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDir 'backend.out.log') `
            -RedirectStandardError (Join-Path $runDir 'backend.err.log') -PassThru
        $backendPid = $backend.Id
    }
    if ($reuseWeb) {
        $webPid = $webListener.OwningProcess
    } else {
        $web = Start-Process -FilePath $node -ArgumentList @(('"' + (Join-Path $root 'scripts\deploy\serve-laptop-app.mjs') + '"')) `
            -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runDir 'web.out.log') `
            -RedirectStandardError (Join-Path $runDir 'web.err.log') -PassThru
        $webPid = $web.Id
    }
    [IO.File]::WriteAllText((Join-Path $runDir 'processes.json'),
        (@{ backendPid = $backendPid; webPid = $webPid; webPort = $WebPort } | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$WebPort/actuator/health" -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { Start-Sleep -Seconds 2 }
    }
    if (-not $ready) { throw "Alanan did not become healthy on port $WebPort. Check $runDir logs." }
    Write-Host "Alanan is ready locally at http://127.0.0.1:$WebPort/"
    Write-Host "Backend log: $runDir\backend.out.log"
    Write-Host "Web log: $runDir\web.out.log"
    if ($reuseWeb) { Write-Host "Reused the existing Alanan web server on port $WebPort." }
    if ($reuseBackend) { Write-Host "Reused the existing Alanan backend on port $backendPort." }
    Write-Host 'Tailscale Serve makes the app available to devices signed in to your tailnet.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
