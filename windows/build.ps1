<#
.SYNOPSIS
    build.ps1 - Build (and optionally run) the LanSync Windows client.

.DESCRIPTION
    First build is slow (a few minutes) — Fyne's GLFW/OpenGL bindings are CGO and
    get compiled from source. Later builds are fast (cached). Requires a C
    compiler on PATH (MinGW-w64 works) since CGO_ENABLED=1 is required by Fyne.

.EXAMPLE
    .\build.ps1          # build lansync-windows.exe
    .\build.ps1 -Run     # build, then launch it
#>

param(
    [switch]$Run
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "==> Building LanSync Windows client..."
go build -o lansync-windows.exe .
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
Write-Host "Done: $PSScriptRoot\lansync-windows.exe"

if ($Run) {
    Write-Host "==> Launching..."
    Start-Process ".\lansync-windows.exe"
}
