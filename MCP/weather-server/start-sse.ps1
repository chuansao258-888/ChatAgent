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

Write-Host "Starting mcp_weather_server in SSE mode..." -ForegroundColor Cyan
Write-Host "Endpoints:" -ForegroundColor Yellow
Write-Host "  GET  http://$HostName`:$Port/sse" -ForegroundColor Yellow
Write-Host "  POST http://$HostName`:$Port/messages/" -ForegroundColor Yellow
Write-Host ""

& $venvPython -m mcp_weather_server --mode sse --host $HostName --port $Port
