# Build AgentBridge (Windows PowerShell)
# Usage: .\build.ps1 [-SkipTests]

param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Locate Maven: PATH first, then the temporary distribution used during development
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue)
if ($mvn) {
    $mvnCmd = $mvn.Source
} else {
    $candidates = @(
        "$env:LOCALAPPDATA\Temp\maven\apache-maven-3.9.9\bin\mvn.cmd",
        "$env:LOCALAPPDATA\Temp\maven\apache-maven-3.9.6\bin\mvn.cmd",
        "C:\Users\sfclo\AppData\Local\Temp\maven\apache-maven-3.9.9\bin\mvn.cmd"
    ) | Where-Object { Test-Path $_ }

    if (-not $candidates) {
        throw "Maven not found. Install Maven 3.9+ or add mvn.cmd to PATH."
    }
    $mvnCmd = $candidates[0]
}

Write-Host "Using Maven: $mvnCmd" -ForegroundColor Cyan

$args = @('-B', 'install')
if ($SkipTests) { $args += '-DskipTests' }

Push-Location $root
try {
    & $mvnCmd @args
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Artifacts:" -ForegroundColor Green
@(
    'server\target\AgentBridgeServer.jar',
    'plugin\target\AgentBridgePlugin.jar',
    'client\target\AgentBridge.jar'
) | ForEach-Object {
    $p = Join-Path $root $_
    if (Test-Path $p) {
        $mb = [math]::Round((Get-Item $p).Length / 1MB, 2)
        Write-Host ("  {0}  ({1} MB)" -f $p, $mb)
    }
}
