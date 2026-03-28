param(
    [string]$Model = "BAAI/bge-reranker-v2-m3",
    [int]$Port = 7997,
    [string]$BindHost = "0.0.0.0",
    [string]$Device = "",
    [switch]$DisablePreload,
    [switch]$DisableWarmup,
    [switch]$Offline
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = Join-Path $scriptDir ".venv\Scripts\python.exe"
if (-not (Test-Path $pythonExe)) {
    $pythonExe = "python"
}

$env:BGE_RERANKER_MODEL = $Model
$env:BGE_RERANKER_PORT = "$Port"
$env:BGE_RERANKER_HOST = $BindHost
$env:BGE_RERANKER_PRELOAD_ON_START = $(if ($DisablePreload) { "false" } else { "true" })
$env:BGE_RERANKER_WARMUP_ENABLED = $(if ($DisableWarmup) { "false" } else { "true" })
$env:BGE_RERANKER_OFFLINE = $(if ($Offline) { "true" } else { "false" })

if ($Device) {
    $env:BGE_RERANKER_DEVICE = $Device
}

Write-Host "Starting reranker server" -ForegroundColor Cyan
Write-Host "  Model   : $($env:BGE_RERANKER_MODEL)"
Write-Host "  Host    : $($env:BGE_RERANKER_HOST)"
Write-Host "  Port    : $($env:BGE_RERANKER_PORT)"
Write-Host "  Preload : $($env:BGE_RERANKER_PRELOAD_ON_START)"
Write-Host "  Warmup  : $($env:BGE_RERANKER_WARMUP_ENABLED)"
Write-Host "  Offline : $($env:BGE_RERANKER_OFFLINE)"
if ($Device) {
    Write-Host "  Device  : $Device"
}

Push-Location $scriptDir
try {
    & $pythonExe ".\rerank_server.py"
} finally {
    Pop-Location
}
