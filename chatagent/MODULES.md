# ChatAgent Backend Modules

Current backend module layout:

```text
chatagent/
|- pom.xml          # parent reactor
|- framework/       # shared cross-cutting concerns
|- infra/           # provider and outbound integrations
`- bootstrap/       # Spring Boot startup and business domains
```

## Current state

The backend has been simplified from six modules to three functional modules.

- `framework` owns shared cross-cutting code:
  `BizException`, `GlobalExceptionHandler`, `ApiResponse`, `AsyncConfig`, `CorsConfig`,
  `BaseErrorCode`, `TraceContext`, `TraceIdFilter`, `SseService`, `DefaultSseService`,
  `SseEmitterSender`
- `infra` owns infrastructure integrations:
  `chat.ChatClientRegistry`, `chat.ChatModelRouter`, `chat.config.MultiChatClientConfig`,
  `mail.EmailService`, `mail.impl.EmailServiceImpl`
- `bootstrap` now owns startup and all business/runtime code:
  `ChatAgentApplication`, `conversation`, `user`, `admin`, `agent`, `rag`,
  `support` dto/persistence code, `InternalAssistantService`,
  `AgentMessageBridgeImpl`, MyBatis adapters, controller/application services,
  retrieval and ingestion pipelines, Flyway migrations (`db/migration/`),
  and module-level tests

## Build target

When Maven is available, build from the backend root:

```bash
./mvnw -pl bootstrap -am compile
```

## Current package shape inside bootstrap

The active top-level packages under `bootstrap/src/main/java/com/yulong/chatagent` are:

- `agent` — Agent 运行时（ReAct 循环、工具执行、InternalAssistantService）
- `admin` — 后台管理（AgentFacadeService，后续知识库/意图树管理）
- `conversation` — 会话与消息（创建/查询/SSE 流式推送）
- `user` — 用户认证（JWT 登录/注册/刷新/拦截）
- `rag` — 检索增强生成（ingestion、向量检索、rerank）
- `support` — 共享 DTO、持久层适配器、健康检查
  `support.dto`, `support.persistence`, `support.controller`

Agent 为后台内部运行配置，普通用户不直接创建或选择 Agent。
会话创建由后端自动绑定系统默认助手（`InternalAssistantService`）。

## Database migrations

Flyway 迁移脚本位于 `bootstrap/src/main/resources/db/migration/`：

- `V1__baseline_schema.sql` — 基线 schema（t_user, agent, chat_session, chat_message 等）
- `V2__phase0_internal_assistant.sql` — Phase 0 迁移（系统用户、系统助手、session 重绑）
