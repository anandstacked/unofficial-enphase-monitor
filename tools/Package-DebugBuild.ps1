param([string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$signer = Join-Path $SdkRoot 'build-tools/35.0.0/apksigner.bat'
$aapt = Join-Path $SdkRoot 'build-tools/35.0.0/aapt.exe'
$packages = @(
    @{ Module='app'; Prefix='Unofficial-Enphase-Monitor'; Id='com.anand.envoymonitor'; Min=29 },
    @{ Module='wear'; Prefix='Unofficial-Enphase-Wear'; Id='com.anand.envoymonitor'; Min=33 },
    @{ Module='solar-face'; Prefix='Unofficial-Enphase-Face'; Id='com.anand.envoymonitor.face'; Min=33 }
)
$certificates = @()
foreach ($package in $packages) {
    $package.Source = Join-Path $taskRoot ($package.Module+'/build/outputs/apk/debug/'+$package.Module+'-debug.apk')
    if (-not (Test-Path -LiteralPath $package.Source)) { throw ('Build the APK first: '+$package.Module) }
    $metadata = Get-Content -LiteralPath (Join-Path (Split-Path $package.Source) 'output-metadata.json') -Raw | ConvertFrom-Json
    $package.Version = $metadata.elements[0].versionName
    $package.Code = $metadata.elements[0].versionCode
    if ($package.Version -notmatch '^\d+\.\d+\.\d+$' -or $package.Code -lt 1) { throw 'Invalid build version metadata' }
    $package.Name = $package.Prefix+'-'+$package.Version+'.apk'
    $verified = & $signer verify --print-certs $package.Source
    if ($LASTEXITCODE -ne 0) { throw ('Invalid signature: '+$package.Name) }
    $digest = @($verified | Where-Object { $_ -match 'certificate SHA-256 digest:' })
    if ($digest.Count -ne 1) { throw 'Expected one signer' }
    $certificates += $digest[0]
    $badging = (& $aapt dump badging $package.Source) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch ("package: name='"+[regex]::Escape($package.Id)+"' versionCode='"+$package.Code+"' versionName='"+[regex]::Escape($package.Version)+"'")) { throw ('Wrong package/version: '+$package.Name) }
    if ($badging -notmatch ("sdkVersion:'"+$package.Min+"'") -or $badging -notmatch "targetSdkVersion:'35'") { throw ('Wrong SDK requirement: '+$package.Name) }
}
if (@($certificates | Select-Object -Unique).Count -ne 1) { throw 'Phone and watch signing certificates must match' }
$previous = Join-Path $taskRoot 'dist/latest/Unofficial-Enphase-Monitor.apk'
if (Test-Path -LiteralPath $previous) {
    $old = & $signer verify --print-certs $previous
    if ($LASTEXITCODE -ne 0 -or @($old | Where-Object { $_ -match 'certificate SHA-256 digest:' })[0] -ne $certificates[0]) { throw 'Phone update must retain the previous signing certificate' }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$face = [System.IO.Compression.ZipFile]::OpenRead($packages[2].Source)
try { if (@($face.Entries | Where-Object { $_.FullName -match '\.dex$' }).Count -gt 0) { throw 'The face must contain no executable DEX code' } }
finally { $face.Dispose() }
$facePermissions = (& $aapt dump permissions $packages[2].Source) -join "`n"
if ($facePermissions -match 'android.permission.INTERNET') { throw 'The face must not access the network' }
$destination = Join-Path $taskRoot 'dist'
New-Item -ItemType Directory -Force $destination | Out-Null
$latest = Join-Path $destination 'latest'
New-Item -ItemType Directory -Force $latest | Out-Null
$hashes = @()
$latestHashes = @()
$latestPackages = @()
foreach ($package in $packages) {
    $output = Join-Path $destination $package.Name
    Copy-Item -LiteralPath $package.Source -Destination $output -Force
    $hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    $hashes += $hash+'  '+$package.Name
    $latestName = $package.Prefix+'.apk'
    $latestOutput = Join-Path $latest $latestName
    Copy-Item -LiteralPath $output -Destination $latestOutput -Force
    if ((Get-FileHash -LiteralPath $latestOutput -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw ('Latest copy does not match: '+$latestName) }
    $latestHashes += $hash+'  '+$latestName
    $latestPackages += [ordered]@{ file=$latestName; version=$package.Version; versionCode=$package.Code; sha256=$hash }
    Write-Output ('Packaged '+$package.Name+' ('+(Get-Item -LiteralPath $output).Length+' bytes)')
}
$hashes | Set-Content -LiteralPath (Join-Path $destination ('SHA256SUMS-'+$packages[0].Version+'.txt')) -Encoding utf8
$latestHashes | Set-Content -LiteralPath (Join-Path $latest 'SHA256SUMS.txt') -Encoding utf8
[ordered]@{ packagedAtUtc=[DateTime]::UtcNow.ToString('o'); buildType='debug'; packages=$latestPackages } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $latest 'build-info.json') -Encoding utf8
Write-Output ('Latest named APK copies: '+$latest)
Write-Output 'Package IDs, SDK levels, signatures, upgrade key and resource-only face verified.'
