# ChatAgent 本地 GPU 运行指南

## 1. 目标

本指南用于把 ChatAgent 主系统切到本地 GPU 服务栈：

- Embedding：Ollama `bge-m3`
- Reranker：本地 `bge-reranker-server`
- Knowledge PDF batch VDP：本地 `MinerU`

说明：

- 会话图片 / 单页视觉解析仍默认走 `VlmVdpEngine`
- 本地 `MinerU` 当前只接管 `KNOWLEDGE` 管线的 `PDF_PAGE_BATCH`

## 2. 服务与端口

| 服务 | 端口 | 用途 |
|---|---|---|
| Ollama | `11434` | embedding (`bge-m3`) |
| BGE reranker server | `7997` | cross-encoder rerank |
| MinerU | `8000` | knowledge PDF batch parsing |

## 3. 启动顺序

### 3.1 Ollama

确保 Ollama 已安装并可访问：

```powershell
ollama list
ollama pull bge-m3
```

### 3.2 Reranker

```powershell
.\tools\bge-reranker-server\start-reranker.ps1
```

检查：

```powershell
Invoke-WebRequest http://127.0.0.1:7997/ready
```

### 3.3 MinerU

```powershell
.\tools\mineru\start-mineru-api.ps1
```

检查：

```powershell
Invoke-WebRequest http://127.0.0.1:8000/health
```

## 4. 启动 ChatAgent 后端

现在主配置已经把 `local-gpu` 设为默认 profile，所以普通启动就会默认走本地 GPU 配置。

最简单的方式是直接启动后端：

```powershell
cd .\chatagent
.\mvnw.cmd -pl bootstrap spring-boot:run
```

如果你仍然想用仓库脚本，也可以继续：

```powershell
.\start-local-gpu-backend.ps1
```

默认 profile 现在是：

```text
spring.profiles.default=local-gpu
```

显式 profile 覆盖仍然保留在：

```text
chatagent/bootstrap/src/main/resources/application-local-gpu.yaml
```

## 5. 默认 local-gpu 运行项

```text
rag.embedding.base-url = http://127.0.0.1:11434
rag.embedding.model = bge-m3
rag.retrieval.reranker.provider = bge-http
rag.retrieval.reranker.base-url = http://127.0.0.1:7997
chatagent.rag.vdp.mineru.enabled = true
chatagent.rag.vdp.mineru.base-url = http://127.0.0.1:8000
chatagent.rag.vdp.routing.preferred-batch-engine = mineru
```

## 6. 常用覆盖方式

如果本地端口不是默认值，可以在启动前覆盖环境变量：

```powershell
$env:CHATAGENT_RAG_EMBEDDING_BASE_URL = "http://127.0.0.1:11434"
$env:CHATAGENT_RAG_RERANKER_BASE_URL = "http://127.0.0.1:7997"
$env:CHATAGENT_RAG_VDP_MINERU_BASE_URL = "http://127.0.0.1:8000"
.\start-local-gpu-backend.ps1
```

## 7. 验证建议

启动后建议至少检查：

1. `/ready` 是否返回 200：
   - reranker：`http://127.0.0.1:7997/ready`
2. `MinerU` 是否健康：
   - `http://127.0.0.1:8000/health`
3. 知识库 PDF ingestion 是否走 `mineru` batch：
   - 观察日志中的 `engineId=mineru`
4. 有 GPU 负载时 `nvidia-smi` 是否出现：
   - reranker / mineru / ollama 对应进程

## 8. 相关文件

- `start-local-gpu-backend.ps1`
- `chatagent/bootstrap/src/main/resources/application-local-gpu.yaml`
- `tools/bge-reranker-server/README.md`
- `tools/mineru/README.md`
