param(
    [ValidateSet("huggingface", "modelscope")]
    [string]$Source = "huggingface",
    [ValidateSet("pipeline", "vlm", "all")]
    [string]$ModelType = "pipeline"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$downloadExe = Join-Path $scriptDir ".venv\Scripts\mineru-models-download.exe"
$runtimePatchDir = Join-Path $scriptDir "runtime-patches"
$defaultFastTextCache = Join-Path $env:USERPROFILE ".cache\chatagent-mineru\fasttext"
$defaultHuggingFaceHome = Join-Path $env:USERPROFILE ".cache\chatagent-mineru\huggingface"

if (-not (Test-Path $downloadExe)) {
    throw "Cannot find mineru-models-download.exe under $scriptDir\.venv. Install MinerU first."
}

$env:FTLANG_CACHE = if ($env:FTLANG_CACHE) { $env:FTLANG_CACHE } else { $defaultFastTextCache }
$env:HF_HOME = if ($env:HF_HOME) { $env:HF_HOME } else { $defaultHuggingFaceHome }
$env:HF_HUB_DISABLE_SYMLINKS_WARNING = if ($env:HF_HUB_DISABLE_SYMLINKS_WARNING) { $env:HF_HUB_DISABLE_SYMLINKS_WARNING } else { "1" }
if (Test-Path $runtimePatchDir) {
    $env:PYTHONPATH = if ($env:PYTHONPATH) {
        "$runtimePatchDir;$env:PYTHONPATH"
    } else {
        $runtimePatchDir
    }
}
New-Item -ItemType Directory -Force -Path $env:FTLANG_CACHE | Out-Null
New-Item -ItemType Directory -Force -Path $env:HF_HOME | Out-Null

Write-Host "Downloading MinerU models" -ForegroundColor Cyan
Write-Host "  Source     : $Source"
Write-Host "  Model Type : $ModelType"
Write-Host "  HF_HOME    : $env:HF_HOME"
Write-Host ""
Write-Host "Downloaded model path and mineru.json will be written under your user profile." -ForegroundColor Yellow

Push-Location $scriptDir
try {
    & $downloadExe --source $Source --model_type $ModelType
} finally {
    Pop-Location
}
