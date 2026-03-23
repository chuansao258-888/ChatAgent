# Local BGE Reranker Server

This project can call a local HTTP reranker from:

- [BgeHttpRetrievalReranker](/Users/guany/OneDrive%20-%20Nanyang%20Technological%20University/%E6%A1%8C%E9%9D%A2/ChatAgent/chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalReranker.java)

The server script is:

- [rerank_server.py](/Users/guany/OneDrive%20-%20Nanyang%20Technological%20University/%E6%A1%8C%E9%9D%A2/ChatAgent/tools/bge-reranker-server/rerank_server.py)

## 1. Create a Python environment

Recommended Python version:

- `3.11` or `3.12`

Do not use `3.14` for this setup unless you are prepared to compile native dependencies locally.
Some transitive packages in the `FlagEmbedding` dependency chain do not yet provide stable prebuilt wheels for Python 3.14 on Windows.

Also pin `transformers` to `<5`.
`FlagEmbedding 1.3.5` declares `transformers>=4.44.2` but does not set an upper bound, and newer `5.x` releases can break runtime imports.

```powershell
cd "C:\Users\guany\OneDrive - Nanyang Technological University\桌面\ChatAgent\tools\bge-reranker-server"
py -3.11 -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

## 2. Start the server

```powershell
$env:BGE_RERANKER_MODEL="BAAI/bge-reranker-v2-m3"
$env:BGE_RERANKER_PORT="7997"
python .\rerank_server.py
```

Optional environment variables:

- `BGE_RERANKER_DEVICE`
- `BGE_RERANKER_USE_FP16`
- `BGE_RERANKER_QUERY_MAX_LENGTH`
- `BGE_RERANKER_PASSAGE_MAX_LENGTH`

## 3. Verify the server

```powershell
Invoke-RestMethod -Uri "http://localhost:7997/health"
```

## 4. Configure ChatAgent

In [application.yaml](/Users/guany/OneDrive%20-%20Nanyang%20Technological%20University/%E6%A1%8C%E9%9D%A2/ChatAgent/chatagent/bootstrap/src/main/resources/application.yaml), make sure:

```yaml
rag:
  retrieval:
    reranker:
      provider: bge-http
      model-id: BAAI/bge-reranker-v2-m3
      base-url: http://localhost:7997
      path: /rerank
```

## 5. Request format

```json
{
  "model": "BAAI/bge-reranker-v2-m3",
  "query": "技术栈有哪些",
  "documents": [
    "doc 1",
    "doc 2"
  ],
  "top_n": 5,
  "return_documents": false
}
```

## 6. Response format

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
