$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$output = Join-Path $root '.tools\control-panel'
[IO.Directory]::CreateDirectory($output) | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Windows .NET Framework C# compiler was not found.' }
$icon = Join-Path $output 'AlAnan.ico'
if (-not (Test-Path -LiteralPath $icon)) { & (Join-Path $PSScriptRoot 'Build-BrandAssets.ps1') }
$executable = Join-Path $output 'AlAnanControl.exe'
& $compiler /nologo /target:winexe /out:$executable /win32icon:$icon /reference:System.Windows.Forms.dll (Join-Path $PSScriptRoot 'ControlPanelLauncher.cs')
if ($LASTEXITCODE -ne 0) { throw 'Control panel executable build failed.' }
Write-Host "Created $executable"
