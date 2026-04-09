param(
    [ValidateSet("huggingface", "modelscope")]
    [string]$Source = "huggingface",
    [ValidateSet("pipeline", "vlm", "all")]
    [string]$ModelType = "pipeline"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$downloadExe = Join-Path $scriptDir ".venv\Scripts\mineru-models-download.exe"

if (-not (Test-Path $downloadExe)) {
    throw "Cannot find mineru-models-download.exe under $scriptDir\.venv. Install MinerU first."
}

Write-Host "Downloading MinerU models" -ForegroundColor Cyan
Write-Host "  Source     : $Source"
Write-Host "  Model Type : $ModelType"
Write-Host ""
Write-Host "Downloaded model path and mineru.json will be written under your user profile." -ForegroundColor Yellow

Push-Location $scriptDir
try {
    & $downloadExe --source $Source --model_type $ModelType
} finally {
    Pop-Location
}
