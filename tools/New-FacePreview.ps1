# Catalog preview with example readings, generated from the face's layout.
Add-Type -AssemblyName System.Drawing
$taskRoot = Split-Path $PSScriptRoot
[xml]$face = Get-Content (Join-Path $taskRoot 'solar-face/src/main/res/raw/watchface.xml')
$image = [System.Drawing.Bitmap]::new(450,450)
$graphics = [System.Drawing.Graphics]::FromImage($image)
$graphics.SmoothingMode = 'AntiAlias'
$graphics.TextRenderingHint = 'AntiAliasGridFit'
$graphics.Clear([System.Drawing.Color]::Transparent)
$graphics.FillEllipse([System.Drawing.Brushes]::Black,0,0,450,450)
function Draw-Label($part,$text,$offsetX=0,$offsetY=0) {
    $fontSpec=$part.Text.Font
    if (-not $fontSpec) { $fontSpec=$part.Font }
    $style=[System.Drawing.FontStyle]::Regular
    $font=[System.Drawing.Font]::new('Segoe UI',[float]$fontSpec.size,$style,[System.Drawing.GraphicsUnit]::Pixel)
    $brush=[System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($fontSpec.color))
    $format=[System.Drawing.StringFormat]::new()
    $format.Alignment='Center'; $format.LineAlignment='Center'
    $rect=[System.Drawing.RectangleF]::new(([float]$part.x+$offsetX),([float]$part.y+$offsetY),[float]$part.width,[float]$part.height)
    $graphics.DrawString($text,$font,$brush,$rect,$format)
    $font.Dispose(); $brush.Dispose(); $format.Dispose()
}
foreach ($part in $face.WatchFace.Scene.ChildNodes) {
    switch ($part.Name) {
        'DigitalClock' { Draw-Label $part.TimeText '10:08' ([float]$part.x) ([float]$part.y) }
        'PartText' {
            $text=$part.Text.Font.InnerText
            if ($part.y -eq '139') { $text='Tue, 6 Oct' }
            if ($part.y -eq '403') { $text='BAT 76%' }
            Draw-Label $part $text
        }
        'ComplicationSlot' {
            $values=@{'1'='230.1V';'2'='3.4kW';'3'='28.6kWh';'4'='Phone · 2m ago'}
            $content=@($part.Complication) | Where-Object { $_.type -ne 'EMPTY' } | Select-Object -First 1
            Draw-Label $content.PartText $values[$part.slotId] ([float]$part.x) ([float]$part.y)
        }
    }
}
$folder=Join-Path $taskRoot 'solar-face/src/main/res/drawable-nodpi'
New-Item -ItemType Directory -Force $folder | Out-Null
$image.Save((Join-Path $folder 'preview.png'),[System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose(); $image.Dispose()
