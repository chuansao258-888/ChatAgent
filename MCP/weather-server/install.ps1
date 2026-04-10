param(
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$mcpRoot = Split-Path -Parent $scriptDir
$venvDir = Join-Path $mcpRoot ".venv"
$requirements = Join-Path $scriptDir "requirements.txt"

Write-Host "Preparing local Python environment in $venvDir" -ForegroundColor Cyan

if (-not (Test-Path $venvDir)) {
    & $Python -m venv $venvDir
}

$venvPython = Join-Path $venvDir "Scripts\\python.exe"

if (-not (Test-Path $venvPython)) {
    throw "Virtual environment Python was not created successfully: $venvPython"
}

& $venvPython -m pip install --upgrade pip
& $venvPython -m pip install -r $requirements

Write-Host ""
Write-Host "Weather MCP dependencies are installed." -ForegroundColor Green
Write-Host "Next step: .\\MCP\\weather-server\\start-http.ps1" -ForegroundColor Green

