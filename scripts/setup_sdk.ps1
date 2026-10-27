$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$sdk = 'C:\Android\sdk'
$jdk = 'C:\Users\Keisha\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:JAVA_HOME = $jdk

New-Item -ItemType Directory -Force $sdk | Out-Null
if (-not (Test-Path "$sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    $tmp = 'C:\Android\cmdline-tools-extract'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive 'C:\Android\cmdline-tools.zip' -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    Move-Item "$tmp\cmdline-tools" "$sdk\cmdline-tools\latest" -Force
    Remove-Item $tmp -Recurse -Force
}

$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
Write-Output 'Accepting licenses...'
$yes = ("y`n" * 40)
$yes | & $sm --sdk_root=$sdk --licenses 2>&1 | Select-Object -Last 5

Write-Output 'Installing packages...'
& $sm --sdk_root=$sdk 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 2>&1 | Select-Object -Last 15

Write-Output 'Installed:'
Get-ChildItem $sdk | Select-Object -ExpandProperty Name
