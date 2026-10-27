param(
    [string]$Task = 'testDebugUnitTest'
)

# Gradle on Windows writes test JVM arguments to an argfile in the system
# encoding and cannot find classes when the project path contains non-ASCII
# characters. Run the build through an ASCII junction instead.
$project = Split-Path -Parent $PSScriptRoot
$link = 'C:\tusur-work'

if (-not (Test-Path $link)) {
    cmd /c mklink /J $link "$project" | Out-Null
}

$jdk = 'C:\Users\Keisha\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
if (-not $env:JAVA_HOME -and (Test-Path $jdk)) {
    $env:JAVA_HOME = $jdk
}

Push-Location $link
try {
    & .\gradlew.bat $Task --console=plain
}
finally {
    Pop-Location
}
