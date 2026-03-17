# ChatAgent Refactor And Upgrade Roadmap

## 1. Current Assessment

ChatAgent is already usable as a small single-application AI agent project:

- Spring Boot backend
- React frontend
- Agent loop with tool calling
- PostgreSQL + pgvector retrieval
- SSE based message push
- Basic knowledge base and document management

The current bottleneck is not "missing one more feature". The bottleneck is that core responsibilities are too coupled:

- `ChatAgent` mixes orchestration, memory, prompt assembly, tool execution, persistence, and SSE delivery
- backend modules are not separated by responsibility
- retrieval capability is still single-path and shallow
- ingestion capability is file-type specific and hard-coded
- model access is registry-based but not routing/fallback oriented
- frontend is still a single workbench, not a product with user/admin separation

Compared with `ragent`, the main gap is engineering structure, not only feature count.

## 2. Upgrade Goal

Upgrade ChatAgent from:

- "single-project prototype"

to:

- "modular monolith with clear AI application layers"

Do not jump to microservices. The right intermediate state is:

- one deployable application
- multiple Maven modules
- clear domain boundaries
- configurable pipelines
- observable runtime
- user-facing chat UI plus admin console

## 3. Target Architecture

Recommended backend module split:

```text
ChatAgent/
├─ chatagent-app/                # Spring Boot startup module
├─ chatagent-framework/          # common web/result/exception/trace/threading/config
├─ chatagent-infra-ai/           # model providers, embeddings, rerank, routing, SSE parsing
├─ chatagent-core-agent/         # agent orchestration, tool runtime, memory, prompt planning
├─ chatagent-core-rag/           # ingestion, parsing, chunking, retrieval, rerank, vector store
├─ chatagent-biz/                # session, message, kb, document, admin services
├─ ui/                           # chat UI + admin UI
├─ docs/
└─ resources/
```

If you want a lighter first step, use this temporary split:

```text
chatagent/
├─ app
├─ framework
├─ infra
├─ agent
├─ rag
└─ biz
```

## 4. Backend Layer Responsibilities

### 4.1 `chatagent-framework`

Put only cross-cutting concerns here:

- unified API response
- global exception handling
- trace context
- thread pool configuration
- id generation
- base annotations and utility classes

This layer must not depend on agent, rag, or business modules.

### 4.2 `chatagent-infra-ai`

Put all model/vendor coupling here:

- `ChatClientAdapter`
- `EmbeddingClientAdapter`
- `RerankClientAdapter`
- `RoutingLLMService`
- provider-specific HTTP parsing
- first-packet probe and stream failover
- model health tracking

This replaces the current thin registry pattern in `ChatClientRegistry`.

### 4.3 `chatagent-core-agent`

Split the current `ChatAgent` into smaller services:

- `AgentOrchestrator`
- `AgentPlanner`
- `AgentExecutor`
- `ToolRuntimeService`
- `ConversationMemoryService`
- `PromptAssemblyService`
- `AgentRunContext`

The orchestrator should only coordinate a run. It should not directly own persistence or SSE details.

### 4.4 `chatagent-core-rag`

Split into two pipelines:

- ingestion pipeline
- retrieval pipeline

Suggested subpackages:

```text
rag/
├─ ingestion/
│  ├─ engine/
│  ├─ node/
│  ├─ parser/
│  ├─ chunk/
│  └─ index/
├─ retrieval/
│  ├─ engine/
│  ├─ channel/
│  ├─ postprocessor/
│  ├─ rewrite/
│  ├─ intent/
│  └─ formatter/
└─ vector/
```

### 4.5 `chatagent-biz`

Keep business CRUD and management here:

- agents
- sessions
- messages
- knowledge bases
- documents
- admin queries

This layer should call core services, not reimplement them.

## 5. Key Refactor Targets

### 5.1 Refactor the current agent runtime first

Current issue:

- `ChatAgent` is the biggest coupling point

Target flow:

```text
UserMessageCreated
-> AgentRunService
-> AgentOrchestrator
   -> load memory
   -> build plan
   -> call tools if needed
   -> retrieve context if needed
   -> stream output
   -> persist result
```

Recommended class split:

```text
agent/
├─ orchestrator/
│  ├─ AgentOrchestrator.java
│  ├─ AgentRunLoop.java
│  └─ AgentRunContext.java
├─ planning/
│  ├─ AgentPlanner.java
│  └─ PlanningDecision.java
├─ execution/
│  ├─ ToolExecutionService.java
│  └─ ExecutionResult.java
├─ memory/
│  ├─ ConversationMemoryService.java
│  ├─ SlidingWindowMemoryService.java
│  └─ SummaryMemoryService.java
└─ prompt/
   ├─ PromptAssemblyService.java
   └─ PromptTemplateService.java
```

### 5.2 Replace single-path retrieval with retrieval pipeline

Current issue:

- `RagServiceImpl` does embedding + similarity search only
- retrieval strategy is fixed
- there is no rewrite, intent, rerank, or post-processing chain

Target capability:

- query rewrite
- optional intent routing
- multiple retrieval channels
- deduplication
- rerank
- context formatting

Recommended interfaces:

- `SearchChannel`
- `SearchContext`
- `SearchChannelResult`
- `SearchResultPostProcessor`
- `RetrievalEngine`

Recommended first channels:

- `VectorSearchChannel`
- `KeywordSearchChannel`

Recommended first post-processors:

- `DeduplicationPostProcessor`
- `TopKCutoffPostProcessor`
- `RerankPostProcessor`

### 5.3 Introduce ingestion pipeline

Current issue:

- only markdown is handled
- parsing/chunking/indexing are embedded inside document upload flow

Target ingestion flow:

```text
upload
-> store file
-> create ingestion task
-> parse
-> clean
-> chunk
-> embed
-> index
-> persist logs/status
```

Recommended nodes:

- `FetcherNode`
- `ParserNode`
- `CleanerNode`
- `ChunkerNode`
- `EmbedderNode`
- `IndexerNode`

Do not keep ingestion inside `DocumentFacadeServiceImpl`.

### 5.4 Upgrade model access to routing and fallback

Current issue:

- only simple client lookup by model key
- no health tracking
- no fallback
- no stream failure recovery

Target capabilities:

- candidate model selection
- provider abstraction
- stream first-packet timeout detection
- automatic fallback
- model health record

Suggested core classes:

- `LLMService`
- `RoutingLLMService`
- `ModelSelector`
- `ModelHealthStore`
- `ModelTarget`

### 5.5 Upgrade memory system

Current issue:

- only message window memory
- no summary compression

Target memory design:

- recent messages window
- persisted conversation history
- summary compaction after threshold
- optional role/system memory injection

First implementation can be simple:

- keep last 12 to 20 messages
- summarize after 30 messages
- store one latest summary row per session

### 5.6 Add trace and observability

Current issue:

- there is no real end-to-end trace for rewrite, retrieve, tool use, generation

Target:

- one trace id per conversation request
- node-level timing
- node input/output snapshots with truncation
- admin query page for traces

Suggested trace node types:

- `MEMORY_LOAD`
- `QUERY_REWRITE`
- `INTENT`
- `RETRIEVE_CHANNEL`
- `RERANK`
- `TOOL_EXECUTE`
- `PROMPT_BUILD`
- `LLM_ROUTING`
- `LLM_PROVIDER`
- `STREAM_OUTPUT`

## 6. Frontend Upgrade Direction

Current frontend is a single chat workspace. Upgrade it into two surfaces:

- user chat application
- admin console

Recommended route structure:

```text
/
├─ /login
├─ /chat
├─ /chat/:sessionId
└─ /admin
   ├─ /dashboard
   ├─ /agents
   ├─ /knowledge
   ├─ /knowledge/:kbId
   ├─ /ingestion
   ├─ /traces
   ├─ /models
   └─ /settings
```

Recommended frontend architecture:

- `stores/` for app state
- `services/` for API
- `pages/` for route pages
- `components/chat/`
- `components/admin/`

Recommended first admin pages:

- knowledge base list
- document ingestion status
- trace list/detail
- model settings
- agent config page

## 7. Security And Configuration Baseline

This must be fixed before feature expansion.

### 7.1 Immediate fixes

- rotate all leaked API keys and passwords
- move secrets out of `application.yaml`
- use environment variables or profile-based config
- remove hard-coded frontend URLs
- remove hard-coded Ollama base URL

### 7.2 Add basic auth

Recommended minimum:

- login API
- token session
- admin role
- protected admin routes

### 7.3 Add resource protections

- request rate limit
- upload size limit
- tool whitelist per agent
- path sandboxing for filesystem tool
- audit log for tool calls

## 8. Database Upgrade Suggestions

Current database model is usable but too close to request/response CRUD.

Recommended additional tables:

- `conversation_summary`
- `ingestion_task`
- `ingestion_task_log`
- `rag_trace_run`
- `rag_trace_node`
- `model_config`
- `model_health`
- `message_feedback`

Optional later:

- `intent_node`
- `intent_example`
- `sample_question`

Keep PostgreSQL + pgvector at first. No need to switch vector database early unless scale actually demands it.

## 9. Recommended Phase Plan

## Phase 0: Stabilize Foundations

Goal:

- remove immediate engineering risk

Tasks:

- rotate keys and passwords
- externalize config
- remove hard-coded URLs
- make `ChatAgentFactory` stateless
- add Flyway or Liquibase
- add basic test profile

Acceptance:

- secrets are no longer in repo
- app can start from environment config
- concurrent session creation has no shared factory state

## Phase 1: Decouple Agent Runtime

Goal:

- make agent loop maintainable

Tasks:

- split `ChatAgent` into orchestrator/planner/executor/memory/prompt services
- separate persistence from runtime loop
- separate SSE publishing from orchestration
- introduce `AgentRunContext`

Acceptance:

- each service has focused tests
- adding a new planning strategy no longer changes persistence or SSE code

## Phase 2: Build RAG Ingestion And Retrieval Pipelines

Goal:

- upgrade from demo retrieval to configurable RAG

Tasks:

- create ingestion engine and node interfaces
- support markdown, txt, pdf in first version
- implement chunker strategy abstraction
- add retrieval engine
- add vector + keyword channels
- add dedup and rerank hooks

Acceptance:

- one document upload produces task logs
- retrieval can combine at least two channels
- retrieval post-processors can be extended without changing engine code

## Phase 3: Upgrade Model Access

Goal:

- improve reliability and provider abstraction

Tasks:

- add `LLMService`
- create provider adapters
- add model candidate selection
- add stream first-packet timeout handling
- add health store and fallback

Acceptance:

- one provider failure does not immediately fail the user request if fallback exists
- stream routing metrics are recorded

## Phase 4: Memory, Feedback, And Trace

Goal:

- make long conversations and debugging practical

Tasks:

- add summary memory
- add message feedback
- add rag trace run/node persistence
- add trace query API

Acceptance:

- long conversations remain bounded in token size
- each request can be inspected in admin trace view

## Phase 5: Productize Frontend

Goal:

- turn project into usable product

Tasks:

- separate chat and admin routes
- add auth store and login page
- add knowledge admin pages
- add ingestion status page
- add trace pages
- add model config page

Acceptance:

- non-admin users only see chat
- admin users can inspect kb, tasks, and traces

## 10. Prioritization

If time is limited, do this order:

1. security/config cleanup
2. factory stateless refactor
3. split agent runtime
4. ingestion + retrieval pipeline
5. model routing
6. summary memory
7. trace/admin console

If you skip the first three and jump straight to "more features", the codebase will get slower to change every week.

## 11. Suggested First Concrete Refactor Batch

This is the best next coding batch for ChatAgent:

1. Create `docs/` and architecture notes
2. Introduce env-based config
3. Remove runtime state from `ChatAgentFactory`
4. Extract `AgentRunContext`
5. Extract `ConversationMemoryService`
6. Extract `ToolExecutionService`
7. Extract `StreamPublishService`
8. Replace `RagServiceImpl` with `RetrievalEngine` + `VectorSearchChannel`
9. Move document parsing/indexing into `ingestion/`

That batch gives structural return immediately without requiring a full rewrite.

## 12. What To Borrow From Ragent, And What Not To Borrow Yet

Borrow now:

- module boundaries
- retrieval channel abstraction
- post-processor chain
- ingestion engine
- model routing abstraction
- trace model
- admin/user UI split

Do not copy immediately:

- every advanced business page
- every infrastructure dependency
- full enterprise-grade concurrency setup
- remote MCP ecosystem

For ChatAgent, the right path is:

- copy the architecture principles
- reimplement only the minimum useful subset

## 13. Final Recommendation

The right strategic move is not "make ChatAgent look as big as ragent".

The right move is:

- keep ChatAgent smaller
- make its boundaries much cleaner
- upgrade the weak core paths first
- add only the platform features that directly reduce coupling or improve quality

If done well, ChatAgent can become:

- a cleaner learning project than a huge platform
- a solid personal AI engineering showcase
- a maintainable base for later multi-agent or MCP expansion
