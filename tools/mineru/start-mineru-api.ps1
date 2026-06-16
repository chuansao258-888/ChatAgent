param(
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8000,
    [ValidateSet("local", "huggingface", "modelscope")]
    [string]$ModelSource = "local",
    [switch]$Reload,
    [switch]$EnableVlmPreload
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = Join-Path $scriptDir ".venv\Scripts\python.exe"
$runtimePatchDir = Join-Path $scriptDir "runtime-patches"
$defaultFastTextCache = Join-Path $env:USERPROFILE ".cache\chatagent-mineru\fasttext"
$defaultHuggingFaceHome = Join-Path $env:USERPROFILE ".cache\chatagent-mineru\huggingface"

if (-not (Test-Path $pythonExe)) {
    throw "Cannot find python.exe under $scriptDir\.venv. Install MinerU first."
}

$env:MINERU_MODEL_SOURCE = $ModelSource
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

Write-Host "Starting MinerU API" -ForegroundColor Cyan
Write-Host "  Host         : $BindHost"
Write-Host "  Port         : $Port"
Write-Host "  Model Source : $env:MINERU_MODEL_SOURCE"
Write-Host "  FTLANG_CACHE : $env:FTLANG_CACHE"
Write-Host "  HF_HOME      : $env:HF_HOME"
if ($env:MINERU_MODEL_SOURCE -eq "local") {
    Write-Host "  Config file  : $HOME\mineru.json"
}

$args = @("-m", "mineru.cli.fast_api", "--host", $BindHost, "--port", "$Port")
if ($Reload) {
    $args += "--reload"
}
if ($EnableVlmPreload) {
    $args += @("--enable-vlm-preload", "true")
}

Push-Location $scriptDir
try {
    & $pythonExe @args
} finally {
    Pop-Location
}
