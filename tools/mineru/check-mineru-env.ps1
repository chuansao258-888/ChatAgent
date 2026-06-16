$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = Join-Path $scriptDir ".venv\Scripts\python.exe"
$runtimePatchDir = Join-Path $scriptDir "runtime-patches"
$defaultFastTextCache = Join-Path $env:USERPROFILE ".cache\chatagent-mineru\fasttext"
$defaultHuggingFaceHome = Join-Path $env:USERPROFILE ".cache\chatagent-mineru\huggingface"

if (-not (Test-Path $pythonExe)) {
    throw "Cannot find python.exe under $scriptDir\.venv. Create the virtualenv first."
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

& $pythonExe -c @'
import pathlib
import json
import torch
import os
import importlib.metadata

print(f"python={pathlib.Path().resolve()}")
print(f"mineru={importlib.metadata.version('mineru')}")
print(f"torch={torch.__version__}")
print(f"cuda_available={torch.cuda.is_available()}")
print(f"cuda_device_count={torch.cuda.device_count()}")
print(f"ftlang_cache={os.getenv('FTLANG_CACHE', '')}")
print(f"hf_home={os.getenv('HF_HOME', '')}")
print(f"pythonpath={os.getenv('PYTHONPATH', '')}")
config_path = pathlib.Path.home() / "mineru.json"
print(f"mineru_json={config_path}")
print(f"mineru_json_exists={config_path.exists()}")
if config_path.exists():
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        model_dirs = config.get("models-dir", {})
        print(f"pipeline_models_dir={model_dirs.get('pipeline', '')}")
        print(f"vlm_models_dir={model_dirs.get('vlm', '')}")
    except Exception as exc:
        print(f"mineru_json_parse_error={exc}")
'@
