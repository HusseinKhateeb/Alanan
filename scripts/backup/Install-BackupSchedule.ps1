param(
    [Parameter(Mandatory = $true)][string]$OffsiteDirectory,
    [Parameter(Mandatory = $true)][string]$PgHostName,
    [Parameter(Mandatory = $true)][string]$PgDatabase,
    [Parameter(Mandatory = $true)][string]$PgUser,
    [int]$PgPort = 5432,
    [string]$DailyAt = '02:00',
    [string]$TaskName = 'Alanan Database Backup',
    [string]$PgDump = 'pg_dump',
    [string]$PgRestore = 'pg_restore'
)

$ErrorActionPreference = 'Stop'
if ($OffsiteDirectory -notmatch '^\\\\[^\\]+\\[^\\]+') { throw 'OffsiteDirectory must be a network share (UNC path), not a local folder.' }
if (-not (Test-Path -LiteralPath $OffsiteDirectory -PathType Container)) { throw 'The offsite directory must exist and be writable by the scheduled Windows account.' }
if ($PgPort -lt 1 -or $PgPort -gt 65535) { throw 'PgPort is invalid.' }
if ($DailyAt -notmatch '^([01][0-9]|2[0-3]):[0-5][0-9]$') { throw 'DailyAt must use HH:mm (24-hour time).' }
$account = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$store = Join-Path $env:LOCALAPPDATA 'Alanan\backup'
New-Item -ItemType Directory -Path $store -Force | Out-Null
$credentialPath = Join-Path $store 'postgres-credential.xml'
$configPath = Join-Path $store 'schedule.json'
$dbPassword = Read-Host 'Database password (encrypted for this Windows account)' -AsSecureString
[System.Management.Automation.PSCredential]::new($PgUser, $dbPassword) | Export-Clixml -LiteralPath $credentialPath
$config = [ordered]@{ Host=$PgHostName; Port=$PgPort; Database=$PgDatabase; OffsiteDirectory=(Resolve-Path -LiteralPath $OffsiteDirectory).ProviderPath;
    CredentialPath=$credentialPath; SslMode='require'; ChannelBinding='require'; PgDump=$PgDump; PgRestore=$PgRestore }
[IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$runner = Join-Path $PSScriptRoot 'Invoke-ScheduledBackup.ps1'
$action = New-ScheduledTaskAction -Execute (Get-Command powershell.exe).Source -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$runner`" -ConfigPath `"$configPath`""
$trigger = New-ScheduledTaskTrigger -Daily -At $DailyAt
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 4)
$windowsPassword = Read-Host "Windows account password for $account (needed to access the network share unattended)" -AsSecureString
$plainPassword = [System.Net.NetworkCredential]::new('', $windowsPassword).Password
try {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -User $account -Password $plainPassword -Force | Out-Null
} finally { $plainPassword = $null }
Write-Output "Daily backup task installed: $TaskName at $DailyAt"
Write-Output "Destination: $($config.OffsiteDirectory)"
Write-Output "Run once now: Start-ScheduledTask -TaskName '$TaskName'"
Write-Output 'Check its LastTaskResult and verify a .dump plus .sha256 appear on the offsite share.'
