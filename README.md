# ChatAgent

ChatAgent is a Spring Boot based AI agent project with tool calling, chat memory, SSE streaming, and knowledge-base retrieval.

## Structure

- `chatagent/`: backend service
- `ui/`: frontend application
- `examples/`: supporting examples and assets

## Backend

Key capabilities in the backend:

- Agent runtime built on Spring AI
- Tool calling with manual execution control
- SSE message push for chat progress
- PostgreSQL + pgvector based retrieval
- Document and knowledge-base management

## Local Run

Backend prerequisites:

- Java 17
- Maven
- PostgreSQL with pgvector

Update the datasource in:

- `chatagent/src/main/resources/application.yaml`

Then start the backend from `chatagent/`:

```bash
mvn spring-boot:run
```

## Notes

- The project package name is `com.yulong.chatagent`
- The main application class is `ChatAgentApplication`
