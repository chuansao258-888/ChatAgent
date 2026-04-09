# Local BGE Reranker Server

This project can call a local HTTP reranker from:

- [BgeHttpRetrievalReranker](C:/Users/guany/OneDrive%20-%20Nanyang%20Technological%20University/%E6%A1%8C%E9%9D%A2/ChatAgent/chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalReranker.java)

The server lives here:

- [rerank_server.py](C:/Users/guany/OneDrive%20-%20Nanyang%20Technological%20University/%E6%A1%8C%E9%9D%A2/ChatAgent/tools/bge-reranker-server/rerank_server.py)
- [start-reranker.ps1](C:/Users/guany/OneDrive%20-%20Nanyang%20Technological%20University/%E6%A1%8C%E9%9D%A2/ChatAgent/tools/bge-reranker-server/start-reranker.ps1)

## 1. Create a Python environment

Recommended Python version:

- `3.11`
- `3.12`

Avoid `3.14` for now. `FlagEmbedding` and its native dependency chain are still much less predictable there on Windows.

```powershell
cd "C:\Users\guany\OneDrive - Nanyang Technological University\桌面\ChatAgent\tools\bge-reranker-server"
py -3.11 -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

## 2. Start the server

Recommended Stage 2 startup:

```powershell
cd "C:\Users\guany\OneDrive - Nanyang Technological University\桌面\ChatAgent\tools\bge-reranker-server"
.\start-reranker.ps1
```

Example with explicit options:

```powershell
.\start-reranker.ps1 -Model "BAAI/bge-reranker-v2-m3" -Port 7997 -Offline
```

The startup script defaults to:

- preload enabled
- warmup enabled
- offline disabled

## 3. Stage 2 environment variables

Core variables:

- `BGE_RERANKER_MODEL`
- `BGE_RERANKER_HOST`
- `BGE_RERANKER_PORT`
- `BGE_RERANKER_DEVICE`
- `BGE_RERANKER_USE_FP16`
- `BGE_RERANKER_QUERY_MAX_LENGTH`
- `BGE_RERANKER_PASSAGE_MAX_LENGTH`

Stage 2 lifecycle variables:

- `BGE_RERANKER_PRELOAD_ON_START=true|false`
- `BGE_RERANKER_PRELOAD_MODELS=BAAI/bge-reranker-v2-m3`
- `BGE_RERANKER_WARMUP_ENABLED=true|false`
- `BGE_RERANKER_WARMUP_QUERY=warmup`
- `BGE_RERANKER_WARMUP_DOCS=<json array or doc1||doc2>`
- `BGE_RERANKER_OFFLINE=true|false`
- `BGE_RERANKER_MAX_CONCURRENT_REQUESTS=2`
- `BGE_RERANKER_CPU_THREADS=<n>`
- `BGE_RERANKER_DEADLINE_HEADER=X-Reranker-Deadline-Epoch-Ms`

When `BGE_RERANKER_OFFLINE=true`, the server also sets:

- `TRANSFORMERS_OFFLINE=1`
- `HF_HUB_OFFLINE=1`

## 4. Verify health and readiness

Health should always reflect process liveness:

```powershell
Invoke-RestMethod -Uri "http://localhost:7997/health"
```

Readiness should return `200` only after preload and warmup have completed:

```powershell
Invoke-RestMethod -Uri "http://localhost:7997/ready"
```

Useful fields in the response:

- `state`
- `status`
- `loaded_models`
- `startup_duration_ms`
- `preload_duration_ms`
- `warmup_duration_ms`
- `last_warmup_at`
- `last_error`
- `memory`

## 5. Configure ChatAgent

In [application.yaml](C:/Users/guany/OneDrive%20-%20Nanyang%20Technological%20University/%E6%A1%8C%E9%9D%A2/ChatAgent/chatagent/bootstrap/src/main/resources/application.yaml), make sure:

```yaml
rag:
  retrieval:
    reranker:
      provider: bge-http
      model-id: BAAI/bge-reranker-v2-m3
      base-url: http://localhost:7997
      path: /rerank
```

## 6. Request format

```json
{
  "model": "BAAI/bge-reranker-v2-m3",
  "query": "What is the leave carry-over rule?",
  "documents": [
    "doc 1",
    "doc 2"
  ],
  "top_n": 5,
  "return_documents": false
}
```

## 7. Response format

```json
{
  "model": "BAAI/bge-reranker-v2-m3",
  "results": [
    {
      "index": 1,
      "relevance_score": 0.98
    },
    {
      "index": 0,
      "relevance_score": 0.34
    }
  ]
}
```
