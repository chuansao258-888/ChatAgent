param(
    [int]$Port = 8090,
    [string]$HostName = "localhost"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$mcpRoot = Split-Path -Parent $scriptDir
$venvPython = Join-Path $mcpRoot ".venv\\Scripts\\python.exe"

if (-not (Test-Path $venvPython)) {
    throw "Missing Python environment. Run .\\MCP\\weather-server\\install.ps1 first."
}

Write-Host "Starting mcp_weather_server in streamable HTTP mode..." -ForegroundColor Cyan
Write-Host "Endpoint: http://$HostName`:$Port/mcp" -ForegroundColor Yellow
Write-Host ""

& $venvPython -m mcp_weather_server --mode streamable-http --host $HostName --port $Port
