param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$runDir = Join-Path $root '.tools\anywhere'
$launcher = Join-Path $PSScriptRoot 'Start-Anywhere.ps1'
$jar = Join-Path $root 'backend\target\accounting-0.1.0-SNAPSHOT.jar'
$webScript = Join-Path $PSScriptRoot 'serve-laptop-app.mjs'
$envPath = Join-Path $root '.env'
$launchProcess = $null
$launchStartedAt = $null
$launchAction = ''
$launchErrorLog = Join-Path $runDir 'control-launch.err.log'
$launchOutputLog = Join-Path $runDir 'control-launch.out.log'

function Get-ConfiguredPort {
    param([string]$Name, [int]$Default)
    if (-not (Test-Path -LiteralPath $envPath)) { return $Default }
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match ('^' + [regex]::Escape($Name) + '=(\d+)$')) {
            $value = [int]$Matches[1]
            if ($value -ge 1 -and $value -le 65535) { return $value }
        }
    }
    return $Default
}

function Get-OwnedListener {
    param([int]$Port, [string]$Executable, [string]$Marker)
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -eq '127.0.0.1' } | Select-Object -First 1
    if (-not $listener) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" -ErrorAction SilentlyContinue
    if ($process -and $process.Name -ieq $Executable -and $process.CommandLine -and
        $process.CommandLine.Contains($Marker)) { return $process }
    return $null
}

function Test-BackendHealthy {
    param([int]$Port)
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
        return $health.status -eq 'UP'
    } catch { return $false }
}

[IO.Directory]::CreateDirectory($runDir) | Out-Null
$form = New-Object Windows.Forms.Form
$form.Text = 'Al Anan - App control'
$form.Size = New-Object Drawing.Size(600, 450)
$form.MinimumSize = $form.Size
$form.MaximumSize = $form.Size
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.BackColor = [Drawing.Color]::FromArgb(251, 243, 230)
$form.Font = New-Object Drawing.Font('Segoe UI', 10)
$panelIconPath = Join-Path $root '.tools\control-panel\AlAnan.ico'
if (Test-Path -LiteralPath $panelIconPath) { $form.Icon = New-Object Drawing.Icon($panelIconPath) }

$header = New-Object Windows.Forms.Panel
$header.SetBounds(0, 0, 584, 86)
$header.BackColor = [Drawing.Color]::FromArgb(74, 37, 22)
$form.Controls.Add($header)

$logoPath = Join-Path $root 'frontend\public\icon-192.png'
if (Test-Path -LiteralPath $logoPath) {
    $logo = New-Object Windows.Forms.PictureBox
    $logo.SetBounds(20, 15, 58, 58)
    $logo.SizeMode = 'Zoom'
    $logo.Image = [Drawing.Image]::FromFile($logoPath)
    $header.Controls.Add($logo)
}

$title = New-Object Windows.Forms.Label
$title.Text = 'Al Anan Roastery'
$title.Font = New-Object Drawing.Font('Segoe UI', 18, [Drawing.FontStyle]::Bold)
$title.ForeColor = [Drawing.Color]::FromArgb(255, 250, 241)
$title.SetBounds(92, 12, 460, 36)
$header.Controls.Add($title)

$subtitle = New-Object Windows.Forms.Label
$subtitle.Text = 'Laptop server control'
$subtitle.ForeColor = [Drawing.Color]::FromArgb(237, 207, 145)
$subtitle.SetBounds(94, 51, 455, 24)
$header.Controls.Add($subtitle)

$backendCard = New-Object Windows.Forms.Panel
$backendCard.SetBounds(22, 106, 540, 57)
$backendCard.BackColor = [Drawing.Color]::FromArgb(255, 250, 241)
$backendCard.BorderStyle = 'FixedSingle'
$form.Controls.Add($backendCard)
$backendDot = New-Object Windows.Forms.Panel
$backendDot.SetBounds(14, 22, 10, 10)
$backendCard.Controls.Add($backendDot)

$backendStatus = New-Object Windows.Forms.Label
$backendStatus.SetBounds(35, 14, 485, 28)
$backendStatus.Font = New-Object Drawing.Font('Segoe UI', 10, [Drawing.FontStyle]::Bold)
$backendCard.Controls.Add($backendStatus)

$webCard = New-Object Windows.Forms.Panel
$webCard.SetBounds(22, 173, 540, 57)
$webCard.BackColor = [Drawing.Color]::FromArgb(255, 250, 241)
$webCard.BorderStyle = 'FixedSingle'
$form.Controls.Add($webCard)
$webDot = New-Object Windows.Forms.Panel
$webDot.SetBounds(14, 22, 10, 10)
$webCard.Controls.Add($webDot)
$webStatus = New-Object Windows.Forms.Label
$webStatus.SetBounds(35, 14, 485, 28)
$webCard.Controls.Add($webStatus)

$message = New-Object Windows.Forms.Label
$message.SetBounds(24, 245, 535, 26)
$message.ForeColor = [Drawing.Color]::FromArgb(118, 91, 71)
$form.Controls.Add($message)

$progress = New-Object Windows.Forms.ProgressBar
$progress.SetBounds(24, 274, 536, 7)
$progress.Style = 'Marquee'
$progress.MarqueeAnimationSpeed = 28
$progress.Visible = $false
$form.Controls.Add($progress)

function New-PanelButton {
    param([string]$Text, [int]$X, [int]$Y, [int]$Width, [switch]$Primary)
    $button = New-Object Windows.Forms.Button
    $button.Text = $Text
    $button.SetBounds($X, $Y, $Width, 44)
    $button.FlatStyle = 'Flat'
    $button.FlatAppearance.BorderSize = 1
    $button.UseVisualStyleBackColor = $false
    $button.BackColor = if ($Primary) { [Drawing.Color]::FromArgb(74, 37, 22) } else { [Drawing.Color]::FromArgb(255, 250, 241) }
    $button.ForeColor = if ($Primary) { [Drawing.Color]::FromArgb(255, 250, 241) } else { [Drawing.Color]::FromArgb(60, 33, 22) }
    $button.FlatAppearance.BorderColor = if ($Primary) { [Drawing.Color]::FromArgb(74, 37, 22) } else { [Drawing.Color]::FromArgb(222, 195, 156) }
    $form.Controls.Add($button)
    return $button
}

$start = New-PanelButton 'Start app' 22 302 171 -Primary
$restart = New-PanelButton 'Apply code changes' 204 302 174
$stop = New-PanelButton 'Stop backend' 389 302 173
$openSite = New-PanelButton 'Open website' 22 358 171
$openCode = New-PanelButton 'Open code' 204 358 174
$openLogs = New-PanelButton 'Open logs' 389 358 173

function Update-Panel {
    $backendPort = Get-ConfiguredPort 'SERVER_PORT' 8080
    $webPort = 8090
    $backend = Get-OwnedListener $backendPort 'java.exe' $jar
    $web = Get-OwnedListener $webPort 'node.exe' $webScript
    $healthy = $backend -and (Test-BackendHealthy $backendPort)
    $webHealthy = $web -and $healthy -and (Test-BackendHealthy $webPort)
    $backendStatus.Text = if ($healthy) { "Backend: running and healthy (port $backendPort)" } elseif ($backend) { "Backend: starting or unhealthy (port $backendPort)" } else { "Backend: stopped (port $backendPort)" }
    $backendStatus.ForeColor = if ($healthy) { [Drawing.Color]::FromArgb(76, 102, 36) } else { [Drawing.Color]::FromArgb(156, 61, 36) }
    $backendDot.BackColor = $backendStatus.ForeColor
    $webStatus.Text = if ($webHealthy) { "Website: ready (http://127.0.0.1:$webPort/)" } elseif ($web) { 'Website: running, waiting for backend' } else { 'Website: stopped' }
    $webStatus.ForeColor = if ($webHealthy) { [Drawing.Color]::FromArgb(76, 102, 36) } else { [Drawing.Color]::FromArgb(156, 61, 36) }
    $webDot.BackColor = $webStatus.ForeColor
    $start.Enabled = -not ($launchProcess -and -not $launchProcess.HasExited)
    $restart.Enabled = $start.Enabled
    $stop.Enabled = [bool]$backend -and $start.Enabled
    $openSite.Enabled = [bool]$web
    $progress.Visible = -not $start.Enabled
    if ($launchProcess -and $launchProcess.HasExited) {
        if ($webHealthy) { $message.Text = 'App is ready and reachable.' }
        else { $message.Text = 'Startup failed. Open logs to see what happened.' }
        $script:launchProcess = $null
        $script:launchStartedAt = $null
        $progress.Visible = $false
        $start.Enabled = $true
        $restart.Enabled = $true
    } elseif ($webHealthy -and ($message.Text.StartsWith('Startup failed') -or [string]::IsNullOrWhiteSpace($message.Text))) {
        $message.Text = 'App is ready and reachable.'
    }
}

function Start-App {
    param([switch]$RebuildBackend)
    if ($launchProcess -and -not $launchProcess.HasExited) { return }
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $launcher + '"'))
    if ($RebuildBackend) { $arguments += '-RestartBackend' }
    else { $arguments += '-QuickStart' }
    $script:launchProcess = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $launchOutputLog `
        -RedirectStandardError $launchErrorLog -PassThru
    $script:launchStartedAt = Get-Date
    $script:launchAction = if ($RebuildBackend) { 'Building and applying code changes' } else { 'Starting the app' }
    $message.Text = "$launchAction..."
    Update-Panel
}

$start.Add_Click({ Start-App })
$restart.Add_Click({ Start-App -RebuildBackend })
$stop.Add_Click({
    $port = Get-ConfiguredPort 'SERVER_PORT' 8080
    $owned = Get-OwnedListener $port 'java.exe' $jar
    if (-not $owned) { $message.Text = 'No Al Anan backend was found on its configured port.'; return }
    Stop-Process -Id $owned.ProcessId -ErrorAction Stop
    $message.Text = 'Backend stopped. The website will reconnect after you start it again.'
    Update-Panel
})
$openSite.Add_Click({ Start-Process "http://127.0.0.1:8090/" })
$openCode.Add_Click({
    $editor = Get-Command code.cmd -ErrorAction SilentlyContinue
    if (-not $editor) { $editor = Get-Command code -ErrorAction SilentlyContinue }
    if ($editor) { Start-Process -FilePath $editor.Source -ArgumentList ('"' + $root + '"') }
    else { Start-Process explorer.exe -ArgumentList ('"' + $root + '"') }
})
$openLogs.Add_Click({ Start-Process explorer.exe -ArgumentList ('"' + $runDir + '"') })

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 5000
$timer.Add_Tick({ try { Update-Panel } catch { $message.Text = $_.Exception.Message } })
$timer.Start()
$animationTimer = New-Object Windows.Forms.Timer
$animationTimer.Interval = 1000
$animationTimer.Add_Tick({
    if ($launchProcess -and -not $launchProcess.HasExited -and $launchStartedAt) {
        $elapsed = [int]((Get-Date) - $launchStartedAt).TotalSeconds
        $message.Text = "$launchAction... ${elapsed}s"
    }
})
$animationTimer.Start()
$form.Add_FormClosed({ $timer.Stop(); $timer.Dispose(); $animationTimer.Stop(); $animationTimer.Dispose() })
Update-Panel
[void]$form.ShowDialog()
