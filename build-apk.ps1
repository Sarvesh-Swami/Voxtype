# Builds the Speaker APK with the portable (no-admin) toolchain in %USERPROFILE%\devtools.
# Usage:  .\build-apk.ps1            -> dist\Speaker-<version>-debug.apk
#         .\build-apk.ps1 -Install   -> also installs on a phone connected via adb (USB or wireless debugging)
param([switch]$Install)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = Get-ChildItem "$env:USERPROFILE\devtools" -Directory -Filter 'jdk-17*' | Select-Object -First 1 -ExpandProperty FullName
if (-not $env:JAVA_HOME) { throw "Portable JDK not found in $env:USERPROFILE\devtools (see README.md, 'Toolchain')" }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location $PSScriptRoot

.\gradlew.bat assembleDevDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force dist | Out-Null
$apk = "dist\Speaker-$version-debug.apk"
Copy-Item app\build\outputs\apk\dev\debug\app-dev-debug.apk $apk -Force
Write-Host "APK: $((Resolve-Path $apk).Path)"

if ($Install) {
    & "$env:USERPROFILE\devtools\android-sdk\platform-tools\adb.exe" install -r $apk
}
