Add-Type -AssemblyName System.Drawing

# Icon generator. Uses script location, no non-ASCII paths in source.
$root = Join-Path $PSScriptRoot '..\app\src\main\res'
$sizes = @{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

$purple = [System.Drawing.Color]::FromArgb(255, 60, 56, 141)
$white = [System.Drawing.Color]::White
$green = [System.Drawing.Color]::FromArgb(255, 183, 214, 122)
$dotColor = [System.Drawing.Color]::FromArgb(255, 190, 186, 224)

function New-RoundedRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

foreach ($dir in $sizes.Keys) {
    $size = $sizes[$dir]
    $outDir = Join-Path $root $dir
    New-Item -ItemType Directory -Force $outDir | Out-Null

    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)

    $radius = [float]($size * 0.22)
    $bg = New-RoundedRect 0 0 $size $size $radius
    $bgBrush = New-Object System.Drawing.SolidBrush($purple)
    $g.FillPath($bgBrush, $bg)

    # Calendar page
    $pad = [float]($size * 0.22)
    $pageW = [float]($size - 2 * $pad)
    $pageH = [float]($pageW * 0.92)
    $pageY = [float](($size - $pageH) / 2 + $size * 0.03)
    $page = New-RoundedRect $pad $pageY $pageW $pageH ([float]($size * 0.05))
    $whiteBrush = New-Object System.Drawing.SolidBrush($white)
    $g.FillPath($whiteBrush, $page)

    # Header
    $headerH = [float]($pageH * 0.26)
    $header = New-RoundedRect $pad $pageY $pageW $headerH ([float]($size * 0.05))
    $greenBrush = New-Object System.Drawing.SolidBrush($green)
    $g.FillPath($greenBrush, $header)

    # Rings
    $loopW = [float]($size * 0.045)
    $loopH = [float]($size * 0.12)
    $g.FillRectangle($whiteBrush, [float]($size * 0.34), [float]($pageY - $loopH * 0.4), $loopW, $loopH)
    $g.FillRectangle($whiteBrush, [float]($size * 0.62), [float]($pageY - $loopH * 0.4), $loopW, $loopH)

    # Day dots
    $dot = [float]($size * 0.075)
    $startX = [float]($pad + $pageW * 0.18)
    $startY = [float]($pageY + $headerH + $pageH * 0.24)
    for ($row = 0; $row -lt 2; $row++) {
        for ($col = 0; $col -lt 3; $col++) {
            if ($row -eq 1 -and $col -eq 2) { continue }
            $x = $startX + $col * ($dot * 2.2)
            $y = $startY + $row * ($dot * 2.0)
            if ($row -eq 0 -and $col -eq 0) {
                $brush = $greenBrush
            } else {
                $brush = New-Object System.Drawing.SolidBrush($dotColor)
            }
            $g.FillEllipse($brush, $x, $y, $dot, $dot)
        }
    }

    $bmp.Save((Join-Path $outDir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save((Join-Path $outDir 'ic_launcher_round.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    Write-Output "$dir done"
}
