<#
.SYNOPSIS
    build-install.ps1 - Build LanSync's Android app and install it on a connected
    device via adb (Windows counterpart of build-release.ps1 in sibling projects).

.DESCRIPTION
    Defaults to the Debug variant, since it's signed with the auto-generated debug
    keystore and installs out of the box. Release is not signed in this project yet
    (no signingConfig in app/build.gradle.kts) - installRelease will fail with an
    "APK signature" error until that's set up. Pass -Release once it is.

.EXAMPLE
    .\build-install.ps1              # build + install Debug (default)
    .\build-install.ps1 -Release     # build + install Release (needs signing set up first)
#>

param(
    [switch]$Release
)

$ErrorActionPreference = "Stop"

# Activate Android dev environment (quietly)
$envScript = "F:\Android\source-android-dev.ps1"
if (Test-Path $envScript) {
    . $envScript -Quiet
}

Set-Location $PSScriptRoot

$task = if ($Release) { "installRelease" } else { "installDebug" }
$variant = if ($Release) { "Release" } else { "Debug" }

Write-Host "==> Building & installing $variant on connected device..."
& .\gradlew.bat $task
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Done. $variant APK installed."
