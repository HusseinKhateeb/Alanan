$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$public = Join-Path $root 'frontend\public'
$sourcePath = Join-Path $root 'assets\branding\alanan-logo.png'
if (-not (Test-Path -LiteralPath $sourcePath)) { throw 'assets/branding/alanan-logo.png is missing.' }

function Save-Logo {
    param([Drawing.Image]$Source, [int]$Size, [string]$Path, [switch]$Maskable)
    $bitmap = New-Object Drawing.Bitmap($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::HighQuality
            if ($Maskable) {
                $graphics.Clear([Drawing.Color]::FromArgb(251, 243, 230))
                $margin = [int]($Size * 0.11)
                $graphics.DrawImage($Source, $margin, $margin, ($Size - 2 * $margin), ($Size - 2 * $margin))
            } else {
                $graphics.Clear([Drawing.Color]::Transparent)
                $graphics.DrawImage($Source, 0, 0, $Size, $Size)
            }
        } finally { $graphics.Dispose() }
        $bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
    } finally { $bitmap.Dispose() }
}

$source = [Drawing.Image]::FromFile($sourcePath)
try {
    Save-Logo $source 64 (Join-Path $public 'favicon-64.png')
    Save-Logo $source 180 (Join-Path $public 'apple-touch-icon.png')
    Save-Logo $source 192 (Join-Path $public 'icon-192.png')
    Save-Logo $source 512 (Join-Path $public 'icon-512.png')
    Save-Logo $source 512 (Join-Path $public 'icon-512-maskable.png') -Maskable
    $iconPng = Join-Path $root '.tools\control-panel\icon-256.png'
    [IO.Directory]::CreateDirectory((Split-Path $iconPng -Parent)) | Out-Null
    Save-Logo $source 256 $iconPng
    $png = [IO.File]::ReadAllBytes($iconPng)
    $stream = New-Object IO.MemoryStream
    $writer = New-Object IO.BinaryWriter($stream)
    try {
        $writer.Write([uint16]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]1)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]32)
        $writer.Write([uint32]$png.Length)
        $writer.Write([uint32]22)
        $writer.Write($png)
        [IO.File]::WriteAllBytes((Join-Path $root '.tools\control-panel\AlAnan.ico'), $stream.ToArray())
    } finally { $writer.Dispose(); $stream.Dispose() }
} finally { $source.Dispose() }
Write-Host 'Updated website, PWA, and desktop logo assets.'
