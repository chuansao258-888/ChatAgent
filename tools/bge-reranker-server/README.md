# BGE Reranker Local Deployment

This directory contains the local HTTP reranker service used by ChatAgent.

## Current Status

- `torch 2.6.0+cu124`
- `torch.cuda.is_available() = True`
- GPU-capable on this machine: `NVIDIA GeForce RTX 3070 Laptop GPU`
- Default endpoint: `http://127.0.0.1:7997`
- Exposed routes:
  - `GET /health`
  - `GET /ready`
  - `POST /rerank`

The model is loaded through `FlagReranker`. When CUDA is available and `BGE_RERANKER_DEVICE` is left empty, the service auto-selects GPU.

## Start

From the repo root:

```powershell
.\tools\bge-reranker-server\start-reranker.ps1
```

Useful variants:

```powershell
.\tools\bge-reranker-server\start-reranker.ps1 -Device cuda:0
.\tools\bge-reranker-server\start-reranker.ps1 -DisableWarmup
.\tools\bge-reranker-server\start-reranker.ps1 -DisablePreload
```

## Check

```powershell
Invoke-WebRequest http://127.0.0.1:7997/health | Select-Object -Expand Content
Invoke-WebRequest http://127.0.0.1:7997/ready
```

Example rerank request:

```powershell
$payload = @{
  query = "什么是多模态解析"
  documents = @(
    "多模态解析用于从图片和 PDF 中提取结构化内容。",
    "系统支持向量检索和 rerank。"
  )
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri http://127.0.0.1:7997/rerank `
  -Method Post `
  -ContentType "application/json" `
  -Body $payload
```

## Connect ChatAgent

The backend local GPU profile already points ChatAgent to this service:

```text
rag.retrieval.reranker.base-url = http://127.0.0.1:7997
rag.retrieval.reranker.path = /rerank
rag.retrieval.reranker.ready-path = /ready
```

If you need to override the address:

```powershell
$env:CHATAGENT_RAG_RERANKER_BASE_URL = "http://127.0.0.1:7997"
```
