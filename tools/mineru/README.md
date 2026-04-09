# MinerU Local Deployment

This directory contains a local Windows deployment of MinerU for ChatAgent integration work.

## Layout

```text
tools/mineru/
  .venv/                  Python 3.11 virtual environment
  requirements.txt        Pinned package entry
  start-mineru-api.ps1    Start local MinerU API service
  stop-mineru-api.ps1     Stop local MinerU API service on port 8000
  download-models.ps1     Download MinerU local models
  check-mineru-env.ps1    Print runtime and model config status
  runtime-patches/        Startup-time Python patch hooks
```

## Current Status

- MinerU package installed in `.venv`
- `mineru-api` command available
- `mineru-models-download` command available
- Current PyTorch build is CUDA-enabled (`torch 2.6.0+cu124`)
- `mineru.json` is used from `%USERPROFILE%\mineru.json`
- Startup script forces `FTLANG_CACHE` to an ASCII-only path under `%USERPROFILE%\.cache\chatagent-mineru\fasttext`
- Startup script injects `runtime-patches/sitecustomize.py` to avoid Windows Unicode-path failures in `fast_langdetect`
- Local smoke test against `http://127.0.0.1:8000` has passed

## First-Time Setup

Open PowerShell in the repo root and run:

```powershell
.\tools\mineru\check-mineru-env.ps1
.\tools\mineru\download-models.ps1 -Source huggingface -ModelType pipeline
```

After the download finishes, MinerU writes the local model path to:

```text
%USERPROFILE%\mineru.json
```

## Start Local API

```powershell
.\tools\mineru\start-mineru-api.ps1
```

Stop the service:

```powershell
.\tools\mineru\stop-mineru-api.ps1
```

Useful variants:

```powershell
.\tools\mineru\start-mineru-api.ps1 -BindHost 0.0.0.0 -Port 8000
.\tools\mineru\start-mineru-api.ps1 -ModelSource local
.\tools\mineru\start-mineru-api.ps1 -Reload
```

The startup script now launches MinerU via:

```powershell
python -m mineru.cli.fast_api
```

instead of the `mineru-api.exe` wrapper, because that path has been more reliable on Windows during local smoke tests.

Then verify:

- [http://127.0.0.1:8000/docs](http://127.0.0.1:8000/docs)
- [http://127.0.0.1:8000/health](http://127.0.0.1:8000/health)

## Connect ChatAgent

Use these environment variables before running the Java smoke test or backend:

```powershell
$env:CHATAGENT_RAG_VDP_MINERU_ENABLED = "true"
$env:CHATAGENT_RAG_VDP_MINERU_BASE_URL = "http://127.0.0.1:8000"
$env:CHATAGENT_RAG_VDP_MINERU_BEARER_TOKEN = ""
```

Smoke test:

```powershell
$env:CHATAGENT_RAG_VDP_MINERU_SMOKE = "true"
$env:JAVA_HOME = "C:\Users\guany\.jdks\ms-17.0.18"
cd .\chatagent
.\mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=MinerUVdpEngineSmoke" test
```

## GPU Note

This local environment is now CUDA-enabled and ready to use the laptop GPU:

- `torch = 2.6.0+cu124`
- `torchvision = 0.21.0+cu124`
- `torch.cuda.is_available() = True`

If you want to confirm the service is actually consuming GPU during parsing, run `nvidia-smi` while a PDF task is in flight.
