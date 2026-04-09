# ChatAgent

企业内部 AI 工作台：普通用户通过 `/chat` 单入口与系统内部助手对话，管理员在 `/admin/*` 下管理知识库、意图树和助手配置。

## Structure

- `chatagent/` — 后端服务（Java 17 + Spring Boot 3.5 + MyBatis）
- `ui/` — 前端应用（React 18 + Vite + TypeScript）
- `examples/` — 辅助示例和资源

## Tech Stack

| 层面 | 技术 |
|------|------|
| 后端框架 | Java 17 + Spring Boot 3.5 + MyBatis |
| 关系数据库 | PostgreSQL |
| 向量数据库 | Milvus 2.6（混合检索） |
| Embedding | Ollama + bge-m3 (1024维) |
| Reranker | bge-reranker-v2-m3 |
| 缓存 | Redis |
| 认证 | JWT (jjwt) |
| 数据库迁移 | Flyway |
| LLM | DeepSeek / GLM-4 / Ollama (qwen2.5) |
| 前端 | React 18 + Vite + TypeScript + Ant Design |

## Local Run

Backend prerequisites:

- Java 17 + Maven
- PostgreSQL（Flyway 会自动执行 `chatagent/bootstrap/src/main/resources/db/migration/` 下的迁移脚本）
- Redis
- Milvus 2.6
- Ollama（embedding: `bge-m3`）
- 本地 reranker 服务（`tools/bge-reranker-server`）
- 可选：本地 MinerU（knowledge PDF batch VDP）

环境变量配置参考 `chatagent/bootstrap/src/main/resources/application.yaml`。

启动后端：

```bash
cd chatagent
mvn spring-boot:run
```

如果你要把主系统切到本地 GPU 服务栈（Ollama + reranker + MinerU），优先用：

```powershell
.\start-local-gpu-backend.ps1
```

对应运行说明见 [docs/LOCAL_GPU_RUNTIME.md](docs/LOCAL_GPU_RUNTIME.md)。

启动前端：

```bash
cd ui
npm install
npm run dev
```

## Notes

- Package: `com.yulong.chatagent`
- Main class: `ChatAgentApplication`
- Agent 为后台内部运行配置，不作为普通用户产品概念暴露
- 用户侧接口：`/api/chat-sessions`、`/api/chat-messages`
- 管理侧接口：`/api/admin/*`
