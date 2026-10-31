param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $projectRoot 'launcher')
try {
    & .\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Gradle checks/build failed' }
} finally { Pop-Location }
$apkDir = Join-Path $projectRoot 'distribution/apk'
New-Item -ItemType Directory -Force $apkDir | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'launcher/app/build/outputs/apk/release/app-release.apk') -Destination (Join-Path $apkDir 'CleanYourTV.apk')
& $Python (Join-Path $projectRoot 'tools/update_custom_manifest.py')
if ($LASTEXITCODE -ne 0) { throw 'Bundle manifest update failed' }
Write-Host "Ready: $apkDir/CleanYourTV.apk"
