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
  `ChatAgentApplication`, `conversation`, `knowledge`, `admin`, `agent`, `rag`,
  `support` dto/persistence code, `DefaultAgentRuntimeContextLoader`,
  `AgentMessageBridgeImpl`, MyBatis adapters, controller/application services,
  retrieval and ingestion pipelines, and module-level tests

## Build target

When Maven is available, build from the backend root:

```bash
./mvnw -pl bootstrap -am compile
```

## Current package shape inside bootstrap

The active top-level packages under `bootstrap/src/main/java/com/yulong/chatagent` are:

- `agent`
- `admin`
- `conversation`
- `knowledge`
- `rag`
- `support`
  `support.dto`, `support.persistence`, `support.controller`

This is intentionally closer to `ragent/bootstrap`: startup and domain code now live
together, while `framework` and `infra` remain separate.

## Remaining cleanup

The large module merge is complete. Remaining work is structural slimming inside
`bootstrap` rather than more Maven splitting:

1. Merge a few over-split runtime helpers such as the small `agent/runtime/*` loader classes.
2. Decide whether `support.controller.HealthController` should stay under `support` or move to a more explicit ops package.
3. Expand unit tests into integration-level checks once a stable local Maven path is available.
