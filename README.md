# ChatAgent

<p align="right">
  English | <a href="README_ZH.md"><strong>中文</strong></a>
</p>

<p align="center">
  <strong>Enterprise AI Intelligent Workspace</strong>
</p>

<p align="center">
  Multi-Model Routing · RAG Knowledge Retrieval · Intent Routing · MCP Tool Integration · Async Task Processing
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-green" />
  <img src="https://img.shields.io/badge/Spring_AI-1.1-orange" />
  <img src="https://img.shields.io/badge/React-19-61dafb" />
  <img src="https://img.shields.io/badge/PostgreSQL-15+-336791" />
  <img src="https://img.shields.io/badge/Milvus-2.6-00A1EA" />
  <img src="https://img.shields.io/badge/RabbitMQ-3.13-FF6600" />
  <img src="https://img.shields.io/badge/Redis-7+-DC382D" />
</p>

---

## Table of Contents

- [Overview](#overview)
- [Tech Stack](#tech-stack)
- [System Architecture](#system-architecture)
- [Core Flow Diagrams](#core-flow-diagrams)
- [Database Design](#database-design)
- [API Design](#api-design)
- [Project Structure](#project-structure)
- [Technical Highlights](#technical-highlights)
- [Design Patterns](#design-patterns)
- [Quick Start](#quick-start)
- [Configuration](#configuration)

---

## Overview

ChatAgent is an **enterprise-grade AI intelligent workspace backend** built on Spring Boot 3.5 + Spring AI 1.1. Regular users interact with the AI assistant through a chat interface, supporting file uploads, knowledge base retrieval, and external tool calls. Administrators manage knowledge bases, intent routing trees, agent configurations, MCP external tool integrations, and system monitoring through the admin panel.

### Core Capabilities

| Capability | Description |
|-----------|-------------|
| **Multi-Model Intelligent Routing** | DeepSeek / ZhipuAI GLM multi-provider support, first-packet probe auto-switching, three-state circuit breaker protection |
| **RAG Knowledge Retrieval** | Full ingestion pipeline (PDF/Markdown/Tika/VLM/MinerU), Milvus hybrid search (Dense+BM25), BGE reranking |
| **Hierarchical Intent Routing** | DOMAIN → CATEGORY → TOPIC three-level routing, heuristic + LLM dual-stage classification, clarification interaction |
| **AI Agent (ReAct)** | Reasoning-Acting loop, built-in tools (knowledge retrieval / SQL / email / filesystem) + MCP external tool dynamic integration |
| **Async Task Processing** | RabbitMQ transactional outbox, three-state distributed locks, structured retry + DLQ |
| **User Authentication** | JWT + Refresh Token dual-token architecture, RBAC role control, Redis token storage |
| **Operations Dashboard** | Real-time performance metrics, session trends, MCP alerts, model routing status |

---

## Tech Stack

### Backend

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17 |
| Framework | Spring Boot | 3.5.8 |
| AI Framework | Spring AI | 1.1.0 |
| ORM | MyBatis | - |
| Relational DB | PostgreSQL | 15+ |
| Vector DB | Milvus (hybrid search) | 2.6 |
| Message Queue | RabbitMQ | 3.13 |
| Cache | Redis | 7+ |
| DB Migration | Flyway | - |
| Embedding | Ollama + bge-m3 | 1024-dim |
| Reranker | BGE-reranker-v2-m3 (GPU) | - |
| PDF Parsing | MinerU / VLM / Apache Tika / PDFBox | - |
| Authentication | JWT (jjwt) | - |
| Build | Maven multi-module | - |

### Frontend

| Technology | Version |
|-----------|---------|
| React | 19 |
| Vite | 7 |
| TypeScript | 5.9 |
| Ant Design | 6 |
| TailwindCSS | 4 |
| Recharts | 3 |
| @ant-design/x | 2 (AI chat components) |

### LLM Providers

| Provider | Models | Use Case |
|----------|--------|----------|
| DeepSeek | deepseek-chat, deepseek-reasoner | Chat, reasoning, agent |
| ZhipuAI | glm-4.6, glm-5.1 | Chat, reasoning, visual parsing |

---

## System Architecture

### Overall Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Frontend (React 19)                          │
│       Admin Panel (Dashboard/Knowledge/Intent Tree/MCP/Users)       │
│       User Interface (Chat/File Upload/Citation Panel)              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTP / SSE
┌──────────────────────────────▼──────────────────────────────────────┐
│                      Spring Boot Backend                             │
│                                                                      │
│  ┌──────────┐ ┌───────────┐ ┌──────────┐ ┌──────────┐              │
│  │ Auth     │ │Chat       │ │ Admin    │ │ SSE      │              │
│  │ JWT+RBAC │ │Session/Msg│ │ Dashboard│ │ Stream   │              │
│  └────┬─────┘ └─────┬─────┘ └────┬─────┘ └────┬─────┘              │
│       │             │            │             │                     │
│  ┌────▼─────────────▼────────────▼─────────────▼───────────────┐    │
│  │                 Orchestration Layer                           │    │
│  │  ConversationOrchestrator ─ IntentRouter ─ EventDispatcher   │    │
│  │  SessionConcurrencyGuard ─ IncrementalSummarizer             │    │
│  └────────────────────────────┬────────────────────────────────┘    │
│                               │                                      │
│  ┌────────────────────────────▼────────────────────────────────┐    │
│  │                 Agent Runtime (ReAct Loop)                    │    │
│  │  ThinkingEngine ─ ToolExecutionEngine ─ MessageBridge         │    │
│  │  Three-layer Memory (L1 Short-term / L2 Summary / L3 Profile)│    │
│  └────────────┬─────────────────────────┬──────────────────────┘    │
│               │                         │                            │
│  ┌────────────▼──────────┐  ┌───────────▼──────────────────────┐    │
│  │  LLM Routing (infra)  │  │       RAG Knowledge Engine       │    │
│  │  First-packet probe   │  │  Parse → Chunk → Enhance → Embed │    │
│  │  Circuit breaker      │  │  Hybrid Search + RRF + Rerank    │    │
│  │  Raw SSE streaming    │  │  Milvus (Dense + BM25)           │    │
│  └───────────────────────┘  └──────────────────────────────────┘    │
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐     │
│  │  MCP Integration  │  │  MQ Async Proc.  │  │  User Auth     │     │
│  │  HTTP/SSE dual    │  │  Txnal Outbox    │  │  Dual JWT      │     │
│  │  CB+RateLimit+SSRF│  │  Dist. Lock+DLQ  │  │  Redis Token   │     │
│  └──────────────────┘  └──────────────────┘  └────────────────┘     │
└──────────────────────────────────────────────────────────────────────┘
```

### Maven Module Layout

```
chatagent/
├── chatagent-bootstrap   ← Spring Boot startup + all business domains
│   ├── access/           ← RBAC (@RequireRole + ResourceAccessGuard)
│   ├── admin/            ← Admin backend (Dashboard/Users/MCP/Routing)
│   ├── agent/            ← Agent runtime (ReAct loop/tools/memory)
│   ├── conversation/     ← Conversation orchestration (messages/SSE/summary)
│   ├── file/             ← Session file upload management
│   ├── intent/           ← Intent routing (hierarchical tree/classification)
│   ├── knowledge/        ← Knowledge base/document CRUD + ingestion
│   ├── mcp/              ← MCP tool integration (transport/runtime/drift)
│   ├── mq/               ← RabbitMQ (outbox/distributed locks/retry)
│   ├── rag/              ← RAG (parsing/chunking/embedding/retrieval/reranking)
│   ├── support/          ← Shared DTOs/Entities/Mappers/Health
│   └── user/             ← User authentication (JWT/BCrypt/role management)
│
├── chatagent-framework   ← Cross-cutting concerns (SSE/exceptions/tracing/API response)
│
└── chatagent-infra       ← Infrastructure (LLM routing/email)
    └── chat/routing/     ← First-packet probe/circuit breaker/streaming
```

---

## Core Flow Diagrams

### User Message Processing

```
User sends message POST /api/chat-messages
│
▼
SessionConcurrencyGuard (Redis distributed lock)
│
▼
ConversationOrchestratorService.handleUserTurn()
│ ① Validate → ② Build context (session+message+history) → ③ Consistency check
│
▼
ConversationTurnPreparationService.prepare()
│
├─── Check pending clarification (Redis, 5min TTL)
│    └── Match user clarification reply → continue routing
│
├─── IntentRouter.route() — hierarchical intent routing
│    ├── Heuristic scoring (bigram Jaccard, score ≥ 1.2 → pass-through)
│    └── LLM fallback classification (only when heuristic is inconclusive)
│
├─── Needs clarification → Save PendingIntentResolution → return option list
├─── SYSTEM intent → Template rendering → direct reply
└─── KB/TOOL intent → QueryRewriter rewrite → dispatch Agent
     │
     ▼
SwitchingChatEventDispatcher (Local / MQ switchable)
     │
     ▼
ChatEventProcessor.process()
     │
     ▼
ChatAgentFactory.create()
│ Load: Agent config + L1 memory + L2 summary + L3 profile + tools + system prompt
│
▼
ChatAgent.run() — ReAct loop (max 20 steps)
│
│  ┌──────────────────────────────────────────┐
│  │           Single Step Iteration          │
│  │                                          │
│  │  AgentThinkingEngine.think()              │
│  │    → No tools → stream final answer → END│
│  │    → Has tools → stream+buffer            │
│  │       → Pure text → becomes final answer  │
│  │       → Tool calls → rollback → execute   │
│  │                                          │
│  │  AgentToolExecutionEngine.execute()       │
│  │    → SessionFileTools → RAG + citations   │
│  │    → DataBaseTools → read-only SQL        │
│  │    → MCP tools → remote call (CB+limit)   │
│  └──────────────────────────────────────────┘
│
▼
Persist result + SSE push + metric recording + async summary trigger
```

### RAG Ingestion Pipeline

```
File Upload
│
▼
FileSizeGuard (30MB hard limit)
│
▼
FileTypeDetector (Magic-byte + extension + MIME)
│
▼
DocumentParserSelector ────────────────────────────────────────
│                     │            │            │       │       │
▼                     ▼            ▼            ▼       ▼       ▼
PdfDocumentParser MarkdownParser  TikaParser  ImageParser   (rejected)
│                     │            │            │
├─ PDFBox text extract │            │            │
├─ QualityRouter/page │            │            │
│  ├─ High density → Fast-Track    │            │
│  └─ Low density → Visual-Track   │            │
│     ├─ VlmVdpEngine (page VLM)   │            │
│     └─ MinerUVdpEngine (batch)   │            │
└─ SegmentAssembler   │            │            │
│                     │            │            │
└─────────────────────┴────────────┴────────────┘
                      │
                      ▼
            ParseResult + List<ParseSegment>
                      │
        ┌─────────────┼─────────────┐
        ▼             ▼             ▼
 Document        Smart           Chunk-level
 Enhancement     Chunking        Contextual
 (LlmDocument    (SegmentAware   Enrichment
  Enhancer)      ChunkerRouter)  (LlmContextual
        │             │          ChunkEnricher)
        └─────────────┼─────────────┘
                      ▼
            Ollama Embedding (bge-m3, 1024-dim)
                      │
                      ▼
            Milvus Upsert (dual collections)
            ├─ chat_file_chunk (session files)
            └─ chat_knowledge_chunk (knowledge base)
```

### RAG Retrieval Pipeline

```
User Query
│
▼
Ollama Embedding → query vector
│
┌────────────────────────────────────────────┐
│          Milvus Hybrid Search              │
│                                            │
│  ┌─────────────────┐ ┌──────────────┐      │
│  │ Session File     │ │ Knowledge    │      │
│  │ Dense + BM25    │ │ Dense + BM25 │      │
│  │ → RRF fusion    │ │ → RRF fusion │      │
│  └────────┬────────┘ └──────┬───────┘      │
│           │                 │              │
│           └────────┬────────┘              │
│                    ▼                       │
│            Global RRF Fusion                │
│                    │                       │
│                    ▼                       │
│      Document Signal Injection (Redis)     │
└────────────────────┬───────────────────────┘
                     │
                     ▼
          Reranking (degradation chain)
          ├─ BGE HTTP (circuit breaker + confidence filter)
          ├─ LLM Reranker (fallback)
          └─ Noop (final fallback, preserves RRF order)
                     │
                     ▼
          RetrievalHitFormatter (with numbered citations)
                     │
                     ▼
          Return to Agent → generate cited answer
```

### First-Packet Probe Routing

```
Candidates: [glm-5.1 (P:5), deepseek-reasoner (P:10)]
│
▼ ① ModelSelector: filter + sort + pin first-choice
│
▼ ② Iterate candidates:

┌── glm-5.1 ──────────────────────────────────────────┐
│  healthStore.tryAcquire() → CLOSED → allow          │
│  FirstPacketAwaiter + ProbeBufferingCallback         │
│  ProviderDirectStreamSupport.submit() (raw SSE)      │
│  awaiter.await(60s)                                  │
│  ├── Packet arrived → commit() → flush buffer → ✓   │
│  └── Timeout/failure → dispose() → discard → ✗      │
└──────────────────────────────────────────────────────┘
         │ (failed)
         ▼
┌── deepseek-reasoner ────────────────────────────────┐
│  healthStore.tryAcquire() → allow                    │
│  First-packet probe ...                              │
│  → Packet arrived → commit() → ✓                    │
└──────────────────────────────────────────────────────┘
         │
         ▼ (all failed)
  callback.onError()
```

### MQ Async Processing Topology

```
┌──────────────────────────────────────────────────────┐
│  Producer (inside business @Transactional)            │
│                                                      │
│  OutboxEventPublisher.publish()                      │
│    → INSERT t_mq_outbox (PENDING) ← same transaction │
│    → ON CONFLICT DO NOTHING (UUIDv5 deterministic)   │
│                                                      │
│  OutboxPollingPublisher (every 2s)                   │
│    → SELECT ... FOR UPDATE SKIP LOCKED               │
│    → RabbitMQ publish + confirm                      │
│    → markSent                                        │
└───────────────────────────┬──────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────┐
│                    RabbitMQ Topology                  │
│                                                      │
│  chat.direct ─┬─ chat.agent.dispatch (DLX→retry)     │
│               └─ knowledge.ingest.task (DLX→retry)   │
│                                                      │
│  retry.direct ─┬─ retry.agent.10s (TTL=10s→chat)     │
│                └─ retry.ingest.30s (TTL=30s→chat)    │
│                                                      │
│  dlx.direct ─── chat.dlq (final dead letter)         │
└───────────────────────────┬──────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────┐
│  Consumer (AbstractRetryingMqConsumer)                │
│                                                      │
│  ① Read message identity (7 immutable headers)        │
│  ② Task Lock acquire (3-state: ACQUIRED/DUPLICATE/   │
│     WAIT)                                             │
│  ③ Session Exec Lock acquire (for agent.run)          │
│  ④ LockWatchdog start (renew every 20s)              │
│  ⑤ processTask()                                     │
│  ⑥ Success → markCompleted + ack                     │
│  ⑦ Retryable → publish to retry exchange + ack       │
│  ⑧ Terminal → reject to DLQ + markFailed             │
└──────────────────────────────────────────────────────┘
```

---

## Database Design

### ER Diagram

```
┌──────────┐     ┌──────────────┐     ┌────────────────┐
│  t_user  │────<│    agent     │────<│  chat_session  │
│──────────│     │──────────────│     │────────────────│
│ id (PK)  │     │ id (PK)      │     │ id (PK)        │
│ username │     │ user_id (FK) │     │ user_id (FK)   │
│ password │     │ system_prompt│     │ agent_id (FK)  │
│ role     │     │ model        │     │ title          │
│ status   │     │ allowed_tools│     │ metadata       │
│ deleted  │     │ chat_options │     └───────┬────────┘
└────┬─────┘     └──────┬───────┘             │
     │                  │                     │
     │    ┌─────────────┤                     │
     │    ▼             ▼                     ▼
     │ ┌──────────┐ ┌──────────┐     ┌────────────────┐
     │ │user_prof.│ │agent_kb  │     │  chat_message  │
     │ │──────────│ │──────────│     │────────────────│
     │ │user_id(FK│ │agent_id  │     │ id (PK)        │
     │ │ summary  │ │kb_id(FK) │     │ session_id(FK) │
     │ └──────────┘ └──────────┘     │ seq_no (auto)  │
     │                                │ turn_id        │
     │                                │ role           │
     │                                │ content        │
     │                                │ metadata       │
     │                                └───────┬────────┘
     │                                        │
     │                        ┌───────────────┤
     │                        ▼               ▼
     │               ┌──────────────┐ ┌───────────────────┐
     │               │chat_session  │ │chat_session_summary│
     │               │    _file     │ │───────────────────│
     │               │──────────────│ │ session_id (PK,FK)│
     │               │ id (PK)      │ │ last_seq_no       │
     │               │ session_id   │ │ summary           │
     │               │ filename     │ │ anchored_entities │
     │               │ storage_path │ │ version           │
     │               │ parse_status │ └───────────────────┘
     │               └──────┬───────┘
     │                      ▼
     │               ┌──────────────┐
     │               │  file_chunk  │
     │               │──────────────│
     │               │ id (PK)      │
     │               │file_id (FK)  │
     │               │ chunk_index  │
     │               │ content      │
     │               │ metadata     │
     │               └──────────────┘
     │
     │    ┌──────────────────────────────────────────────┐
     │    │            Knowledge System                   │
     │    │                                              │
     │    │  ┌──────────────┐    ┌───────────────────┐   │
     │    │  │knowledge_base│───<│knowledge_document  │   │
     │    │  │──────────────│    │───────────────────│   │
     │    │  │ id (PK)      │    │ id (PK)           │   │
     │    │  │ created_by   │    │ kb_id (FK)        │   │
     │    │  │ name         │    │ filename          │   │
     │    │  │ status       │    │ parse_status      │   │
     │    │  └──────┬───────┘    │ content_hash      │   │
     │    │         │            └───────┬───────────┘   │
     │    │         │                    │               │
     │    │         │            ┌───────▼───────────┐   │
     │    │         │            │ knowledge_chunk    │   │
     │    │         │            │───────────────────│   │
     │    │         │            │ id (PK)            │   │
     │    │         │            │ document_id (FK)   │   │
     │    │         │            │ chunk_index        │   │
     │    │         │            │ content            │   │
     │    │         │            └───────────────────┘   │
     │    │         │                                    │
     │    │  ┌──────▼───────────────┐                    │
     │    │  │ knowledge_document   │                    │
     │    │  │    _enhancement      │                    │
     │    │  │──────────────────────│                    │
     │    │  │ document_id (PK, FK) │                    │
     │    │  │ keywords (JSONB)     │                    │
     │    │  │ questions (JSONB)    │                    │
     │    │  └──────────────────────┘                    │
     │    └──────────────────────────────────────────────┘
     │
     │    ┌──────────────────────────────────────────────┐
     │    │            Intent Routing Tree                │
     │    │                                              │
     │    │  ┌──────────────┐    ┌───────────────────┐   │
     │    │  │ intent_node  │───<│intent_knowledge   │   │
     │    │  │──────────────│    │      _base        │   │
     │    │  │ id (PK)      │    │───────────────────│   │
     │    │  │ agent_id(FK) │    │ node_id (FK)      │   │
     │    │  │ parent_id(FK│    │ kb_id (FK)        │   │
     │    │  │ version      │    └───────────────────┘   │
     │    │  │ node_level   │                            │
     │    │  │ name         │  node_level:               │
     │    │  │ intent_kind  │    DOMAIN → CATEGORY → TOPIC│
     │    │  │ scope_policy │                            │
     │    │  │ allowed_tools│                            │
     │    │  └──────────────┘                            │
     │    └──────────────────────────────────────────────┘
     │
     │    ┌──────────────────────────────────────────────┐
     │    │            MCP + MQ + Operations              │
     │    │                                              │
     │    │  ┌──────────────┐    ┌───────────────────┐   │
     │    │  │ t_mcp_server │───<│t_mcp_tool_catalog │   │
     │    │  │──────────────│    │───────────────────│   │
     │    │  │ slug         │    │ exposed_model_name│   │
     │    │  │ protocol     │    │ schema_json       │   │
     │    │  │ endpoint_url │    │ status            │   │
     │    │  │ credentials  │    └───────────────────┘   │
     │    │  │ status       │                            │
     │    │  └──────┬───────┘    ┌───────────────────┐   │
     │    │         │            │ t_mcp_alert_event  │   │
     │    │         └───────────>│───────────────────│   │
     │    │                      │ alert_type        │   │
     │    │  ┌──────────────┐    │ severity          │   │
     │    │  │ t_mq_outbox  │    │ status            │   │
     │    │  │──────────────│    └───────────────────┘   │
     │    │  │ event_type   │                            │
     │    │  │ payload      │    ┌───────────────────┐   │
     │    │  │ status       │    │t_chat_turn_metric  │   │
     │    │  └──────────────┘    │───────────────────│   │
     │    │                      │ session_id (FK)   │   │
     │    │  ┌──────────────┐    │ status            │   │
     │    │  │agent_template│    │ duration_ms       │   │
     │    │  │──────────────│    │ knowledge_hit     │   │
     │    │  │ code (UQ)    │    └───────────────────┘   │
     │    │  │ system_prompt│                            │
     │    │  │ intent_tree  │                            │
     │    │  └──────────────┘                            │
     │    └──────────────────────────────────────────────┘
```

### Table Summary (21 Tables)

| # | Table | Description | Key Columns |
|---|-------|-------------|-------------|
| 1 | `t_user` | User accounts | username (UQ), password_hash, role (admin/user), status (ACTIVE/DISABLED), deleted |
| 2 | `user_profile` | User profiles | user_id (PK,FK), summary |
| 3 | `agent` | AI assistant configs | user_id (FK), system_prompt, model, allowed_tools (JSONB), chat_options (JSONB) |
| 4 | `chat_session` | Chat sessions | user_id (FK), agent_id (FK), title, metadata (JSONB) |
| 5 | `chat_message` | Chat messages | session_id (FK), seq_no (auto), turn_id, role, content, metadata (JSONB) |
| 6 | `chat_session_file` | Session file attachments | session_id (FK), filename, mime_type, size_bytes, storage_path, parse_status |
| 7 | `file_chunk` | File text chunks | session_file_id (FK), chunk_index, content, metadata (JSONB) |
| 8 | `knowledge_base` | Knowledge bases | created_by (FK), name, visibility (SHARED), status (ACTIVE) |
| 9 | `knowledge_document` | Knowledge documents | knowledge_base_id (FK), filename, parse_status, content_hash (SHA256), retry_count, deleted |
| 10 | `knowledge_chunk` | Knowledge text chunks | knowledge_document_id (FK), chunk_index, content, metadata (JSONB) |
| 11 | `agent_knowledge_base` | Agent-KB binding | agent_id (PK,FK), knowledge_base_id (PK,FK) — many-to-many |
| 12 | `intent_node` | Intent routing nodes | agent_id (FK), parent_id (FK→self), version, node_level, intent_kind, scope_policy, allowed_tools (JSONB) |
| 13 | `intent_knowledge_base` | Intent-KB binding | intent_node_id (FK), knowledge_base_id (FK) |
| 14 | `chat_session_summary` | Rolling summaries | session_id (PK,FK), last_seq_no, summary, anchored_entities (JSONB), version (optimistic lock) |
| 15 | `agent_template` | Assistant templates | code (UQ), system_prompt, model, allowed_tools (JSONB), intent_tree (JSONB), built_in |
| 16 | `t_mq_outbox` | Message outbox | event_type, payload (JSONB), headers (JSONB), status (PENDING/CLAIMED/SENT/FAILED) |
| 17 | `knowledge_document_enhancement` | Document enhancement signals | knowledge_document_id (PK,FK), keywords (JSONB), questions (JSONB) |
| 18 | `t_chat_turn_metric` | Per-turn metrics | session_id (FK), user_id (FK), turn_id, status (SUCCESS/ERROR), duration_ms, knowledge_hit |
| 19 | `t_mcp_server` | MCP servers | slug (UQ-soft), protocol (HTTP/SSE), endpoint_url, encrypted_credentials, status (ACTIVE/DISABLED/FAILED/STALE) |
| 20 | `t_mcp_tool_catalog` | MCP tool catalog | server_id (FK), exposed_model_name (UQ-soft), schema_json, status (ENABLED/DISABLED/STALE) |
| 21 | `t_mcp_alert_event` | MCP alerts | server_id (FK), alert_type (SERVER_FAILED/SCHEMA_DRIFT/UNRESOLVED_REFERENCE), severity, status (OPEN/RESOLVED) |

---

## API Design

### Authentication (`/api/auth/*`)

No authentication required.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/register` | Register (auto-login) |
| POST | `/api/auth/login` | Login |
| POST | `/api/auth/refresh` | Refresh token (from cookie) |
| POST | `/api/auth/logout` | Logout (revoke refresh token) |
| GET | `/api/user/me` | Get current user info |

### User Profile

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/user/profile` | Get user profile |
| PUT | `/api/user/profile` | Update user profile |

### Chat Sessions

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/chat-sessions` | List sessions |
| GET | `/api/chat-sessions/{id}` | Get session |
| POST | `/api/chat-sessions` | Create session |
| DELETE | `/api/chat-sessions/{id}` | Delete session |
| PATCH | `/api/chat-sessions/{id}` | Update session |

### Chat Messages

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/chat-messages/session/{sessionId}` | List messages |
| POST | `/api/chat-messages` | Send message (triggers AI response) |
| DELETE | `/api/chat-messages/{id}` | Delete message |
| PATCH | `/api/chat-messages/{id}` | Update message |

### SSE Streaming

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/sse/connect/{sessionId}` | Establish SSE connection (receive AI streaming) |
| GET | `/api/sse/admin/knowledge-bases/{kbId}/documents` | Document status SSE stream (Admin) |

### File Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/chat-sessions/{sessionId}/files` | List session files |
| POST | `/api/chat-sessions/{sessionId}/files/upload` | Upload file (multipart) |
| DELETE | `/api/chat-sessions/{sessionId}/files/{fileId}` | Detach file |

### Tools

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/tools` | List available optional tools |

### Admin APIs (`/api/admin/*`)

All require `@RequireRole(ADMIN)`.

#### Knowledge Base Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/knowledge-bases` | List knowledge bases |
| GET | `/api/admin/knowledge-bases/{id}` | Get knowledge base |
| POST | `/api/admin/knowledge-bases` | Create knowledge base |
| PATCH | `/api/admin/knowledge-bases/{id}` | Update knowledge base |
| DELETE | `/api/admin/knowledge-bases/{id}` | Delete knowledge base (cascading) |
| GET | `/api/admin/knowledge-bases/{kbId}/documents` | List documents |
| POST | `/api/admin/knowledge-bases/{kbId}/documents/upload` | Upload document |
| POST | `/api/admin/knowledge-bases/{kbId}/documents/{docId}/replace` | Replace document |
| DELETE | `/api/admin/knowledge-bases/{kbId}/documents/{docId}` | Delete document |

#### Assistant Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/assistant/knowledge-bases` | Get assistant's bound knowledge bases |
| PUT | `/api/admin/assistant/knowledge-bases` | Set assistant's knowledge base bindings |
| GET | `/api/admin/assistant/templates` | List templates |
| POST | `/api/admin/assistant/templates` | Create template |
| GET | `/api/admin/assistant/templates/{id}` | Get template |
| PATCH | `/api/admin/assistant/templates/{id}` | Update template |
| DELETE | `/api/admin/assistant/templates/{id}` | Delete template |
| POST | `/api/admin/assistant/templates/{id}/initialize` | Initialize assistant from template |

#### Intent Routing Tree

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/assistant/intent-tree` | Get intent tree (draft + versions) |
| POST | `/api/admin/assistant/intent-tree/nodes` | Create node |
| PATCH | `/api/admin/assistant/intent-tree/nodes/{id}` | Update node |
| DELETE | `/api/admin/assistant/intent-tree/nodes/{id}` | Delete node + subtree |
| PUT | `/api/admin/assistant/intent-tree/nodes/{id}/knowledge-bases` | Bind knowledge bases |
| POST | `/api/admin/assistant/intent-tree/publish` | Publish as new version |
| GET | `/api/admin/assistant/intent-tree/versions` | List published versions |
| PUT | `/api/admin/assistant/intent-tree/versions/{ver}/activate` | Activate version |

#### MCP Server Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/mcp-servers` | List MCP servers |
| GET | `/api/admin/mcp-servers/{id}` | Get server details (with tool catalog) |
| POST | `/api/admin/mcp-servers` | Create MCP server |
| PATCH | `/api/admin/mcp-servers/{id}` | Update MCP server |
| DELETE | `/api/admin/mcp-servers/{id}?force=false` | Delete MCP server |
| POST | `/api/admin/mcp-servers/{id}/test` | Test connectivity |
| POST | `/api/admin/mcp-servers/{id}/sync` | Sync tool catalog |

#### Model Routing Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/chat-routing/state` | Get routing state (includes circuit breaker states) |
| PUT | `/api/admin/chat-routing/candidates/override` | Apply runtime candidate override |
| DELETE | `/api/admin/chat-routing/candidates/{id}/override` | Clear override |

#### Dashboard

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/dashboard/overview?window=24h` | KPI overview |
| GET | `/api/admin/dashboard/performance?window=24h` | Performance metrics |
| GET | `/api/admin/dashboard/trends?metric=sessions&window=7d` | Trend data |
| GET | `/api/admin/dashboard/mcp-alerts?limit=20` | MCP alerts |

#### User Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/users?page=1&size=10` | List users |
| POST | `/api/admin/users` | Create user (returns initial password) |
| PUT | `/api/admin/users/{id}` | Update user (role/avatar) |
| PUT | `/api/admin/users/{id}/status` | Enable/disable user |
| PUT | `/api/admin/users/{id}/password/reset` | Reset password |
| DELETE | `/api/admin/users/{id}` | Soft-delete user |

#### MQ Management (conditionally enabled)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/mq/outbox/retry` | View outbox retry state |
| POST | `/api/admin/mq/dlq/replay` | Replay dead-letter messages |

### SSE Event Types

| Event | Payload | Description |
|-------|---------|-------------|
| `AI_GENERATED_CONTENT` | ChatMessageVO | AI content snapshot |
| `AI_THINKING` | thinking text | Reasoning/thinking process |
| `AI_DONE` | done=true | Turn completed |
| `AI_ERROR` | error message | Error notification |
| `TURN_ROLLBACK` | turnId | Rollback streamed content |
| `DOCUMENT_STATUS_UPDATED` | document status | Document ingestion status change |

---

## Project Structure

```
ChatAgent/
├── chatagent/                          # Backend Maven multi-module
│   ├── pom.xml                         # Parent POM (Spring Boot 3.5.8)
│   ├── bootstrap/                      # Business module
│   │   ├── pom.xml
│   │   └── src/main/java/com/yulong/chatagent/
│   │       ├── access/                 # RBAC access control
│   │       ├── admin/                  # Admin services
│   │       ├── agent/                  # Agent runtime (ReAct)
│   │       ├── conversation/           # Conversation orchestration
│   │       ├── file/                   # File management
│   │       ├── intent/                 # Intent routing
│   │       ├── knowledge/              # Knowledge base management
│   │       ├── mcp/                    # MCP tool integration
│   │       ├── mq/                     # Message queue
│   │       ├── rag/                    # RAG pipeline
│   │       ├── support/                # Shared infrastructure (DTOs/Entities/Mappers)
│   │       └── user/                   # User authentication
│   ├── framework/                      # Cross-cutting concerns
│   │   └── src/main/java/com/yulong/chatagent/
│   │       ├── config/                 # Async/CORS config
│   │       ├── context/                # UserContext
│   │       ├── errorcode/              # Error codes
│   │       ├── exception/              # Exception hierarchy
│   │       ├── model/                  # ApiResponse
│   │       ├── sse/                    # SSE infrastructure
│   │       └── trace/                  # Distributed tracing
│   ├── infra/                          # Infrastructure
│   │   └── src/main/java/com/yulong/chatagent/
│   │       ├── chat/                   # ChatClient registry/routing
│   │       └── mail/                   # Email service
│   └── bootstrap/src/main/resources/
│       ├── application.yaml            # Main config (340+ lines)
│       ├── application-local-gpu.yaml  # Local GPU profile
│       ├── prompts/                    # Centralized prompts (24 .md files)
│       │   ├── agent/                  # Agent core prompts + sections
│       │   ├── intent/                 # Intent classification + query rewrite
│       │   ├── rag/                    # RAG ingestion + retrieval prompts
│       │   ├── vlm/                    # VLM visual parsing
│       │   ├── summarizer/             # Rolling memory summary
│       │   └── fallbacks/              # Default fallback text
│       ├── db/migration/               # Flyway migrations (V1-V16)
│       └── mapper/                     # MyBatis XML (23 mappers)
│
├── ui/                                 # Frontend
│   ├── src/
│   │   ├── api/                        # HTTP client
│   │   ├── auth/                       # Token management
│   │   ├── components/
│   │   │   ├── admin/                  # Admin pages
│   │   │   ├── auth/                   # Login page
│   │   │   └── views/agentChatView/    # Chat view
│   │   ├── contexts/                   # React Contexts
│   │   ├── hooks/                      # useAuth, useChatSessions
│   │   ├── layout/                     # Layout components
│   │   └── types/                      # TypeScript types
│   └── package.json
│
├── tools/                              # External tools
│   ├── bge-reranker-server/            # BGE reranker HTTP service
│   └── mineru/                         # MinerU PDF parsing service
│
├── MCP/                                # MCP tool server examples
│   └── weather-server/                 # Weather tool (HTTP+SSE)
│
├── docs/                               # Documentation
│   ├── arch/                           # Architecture docs (17 files)
│   ├── plans/                          # Design plans (13 files)
│   └── summary/                        # Module summaries (13 files)
│
├── postman/                            # Postman API collection
├── data/                               # Runtime data
│   ├── documents/                      # Uploaded file storage
│   ├── milvus/                         # Milvus data
│   └── rabbitmq/                       # RabbitMQ data
│
├── docker-compose-rabbitmq.yml         # RabbitMQ Docker
├── start-local-gpu-backend.ps1         # Local GPU startup script
├── README.md                           # English README (default)
└── README_ZH.md                        # Chinese README
```

---

## Technical Highlights

### 1. First-Packet Probe Routing

In streaming scenarios, candidates are tried serially by priority, waiting for the first valid data packet. On timeout/failure, the buffer is discarded and the next candidate is tried. `ProbeBufferingCallback` ensures the client never receives incomplete data.

### 2. Three-Layer Circuit Breaker Protection

| Layer | Breaker | Protected Target |
|-------|---------|-----------------|
| LLM Provider | `ModelHealthStore` (3-state: CLOSED/OPEN/HALF_OPEN) | DeepSeek / GLM |
| Reranker | `RerankerCircuitBreaker` (sliding window 100s) | BGE Reranker |
| MCP Tools | `McpServerCircuitBreaker` (per-server independent) | Third-party tools |

All support probeGeneration anti-pollution and flight timeout protection.

### 3. Transactional Outbox Pattern

Business operations and message writes share the same database transaction, guaranteeing at-least-once delivery. `OutboxPollingPublisher` uses `SELECT ... FOR UPDATE SKIP LOCKED` for multi-instance-safe claiming, publisher confirm before markSent.

### 4. Three-State Distributed Locks

RUNNING / COMPLETED / FAILED states ensure idempotent consumption. `LockWatchdog` renews every 20s for long-running tasks. Per-task-type Fail-Open / Fail-Fast policy selection.

### 5. Hybrid Search + RRF Fusion

Milvus supports both Dense vector search and BM25 sparse search simultaneously. RRF (Reciprocal Rank Fusion) merges results from both, with a full reranking degradation chain (BGE → LLM → Noop).

### 6. Dual-Track PDF Parsing

`QualityRouter` evaluates each page: high text density uses Fast-Track (PDFBox native extraction), low density uses Visual-Track (render to image → VLM/MinerU parsing).

### 7. Single Routed Stream

Model output is simultaneously streamed to the UI and buffered. If tool calls are detected, the streamed content is rolled back. If pure text, it becomes the final answer directly — no second model call needed.

### 8. Three-Layer Memory System

L1 Short-term (token budget + turn-based sliding window) / L2 Incremental Summary (event-driven + LLM + deterministic fallback) / L3 User Profile (cross-session persistent).

### 9. Secure MCP Integration

SSRF protection + AES-256-GCM credential encryption + Schema drift detection + Token-bucket rate limiting + Prompt safety warnings.

### 10. Centralized Prompt Management

All 46 AI prompts are extracted from Java source code into 24 `.md` files under `resources/prompts/`, organized by domain (agent/intent/rag/vlm/summarizer/fallbacks). A `PromptLoader` component handles loading with `{{variable}}` template substitution and `ConcurrentHashMap` in-memory caching. Every prompt follows an enterprise-grade structure with unified Role / Rules / Guardrails / Output Format sections. The V16 migration sets the default agent's `system_prompt` to NULL to enable centralized loading. See [Prompt Management Module Summary](docs/summary/11-prompt-management.md) for details.

---

## Design Patterns

The project applies classic design patterns at key architectural points to ensure extensibility and maintainability.

### Creational

| Pattern | Implementation | Description |
|---------|---------------|-------------|
| **Factory** | `ChatAgentFactory`, `AgentToolCallbackFactory`, `McpToolDefinitionFactory` | Assemble fully-configured Agent / tool lists / MCP metadata from runtime context |
| **Builder** | 30+ DTO/VO classes (`ChatMessageVO`, `AgentDTO`, `McpServerMetricsSnapshot`, etc.) | Lombok `@Builder` generates fluent construction APIs |

### Structural

| Pattern | Implementation | Description |
|---------|---------------|-------------|
| **Facade** | 15+ facade services (`ConversationOrchestratorService`, `AssistantTemplateFacadeServiceImpl`, `DashboardFacadeServiceImpl`, etc.) | One `*FacadeServiceImpl` per business domain — simplified external API orchestrating multiple internal Ports/Services |
| **Adapter** | `McpToolCallbackAdapter`, `ChatModelProviderRegistry` | Adapts MCP remote calls to Spring AI's `ToolCallback`; unifies DeepSeek / ZhiPu behind a `ProviderBinding` interface |
| **Proxy** | `ProbeBufferingCallback`, `SwitchingChatEventDispatcher` | First-packet probe proxy for streaming; local/MQ dual-channel switching proxy |
| **Bridge** | `ChatModelRouter` → `ChatClient` | Decouples LLM provider selection from invocation — routes to different `ChatClient` instances at runtime |

### Behavioral

| Pattern | Implementation | Description |
|---------|---------------|-------------|
| **Strategy** | `RetrievalReranker` (BGE / LLM / Noop), `VdpEngine` (VLM / MinerU / Noop), `DocumentParser` (PDF / Markdown / Tika / Image) | Algorithm families encapsulated independently; selected at runtime |
| **Chain of Responsibility** | Reranker degradation chain: BGE → LLM → Noop; LLM routing: priority-ordered probing | Request passes along a chain; each node decides to handle or forward |
| **Template Method** | `AbstractRetryingMqConsumer<T>` | Defines MQ consumption skeleton (lock → execute → retry/DLQ); subclasses implement `processTask()` and other hooks |
| **Observer** | `ChatEventDispatcher` → `ChatEventListener` / `AsyncSummaryListener` + `SseService` | Event-driven conversation processing and front-end SSE push |
| **State** | `ModelHealthStore` (CLOSED/OPEN/HALF_OPEN), `MqTaskLockState` (RUNNING/COMPLETED/FAILED) | Three-state machines driving circuit breaker and distributed lock behavior transitions |

### Architectural

| Pattern | Implementation | Description |
|---------|---------------|-------------|
| **Circuit Breaker** | `ModelHealthStore`, `RerankerCircuitBreaker`, `McpServerCircuitBreaker` | Three independent circuit breaker layers preventing cascading failures |
| **Transactional Outbox** | `OutboxEventPublisher` + `OutboxPollingPublisher` | Business writes and message intent share the same database transaction |
| **Port/Adapter (Hexagonal)** | `ChatSessionSummaryRepository`, `AgentRepository` Ports + `MyBatis*Adapter` implementations | Domain logic decoupled from persistence technology |
| **Registry** | `ChatClientRegistry`, `McpRuntimeToolRegistry`, `McpServerCircuitBreakerRegistry` | Centrally managed named instances with runtime dynamic lookup |

---

## Quick Start

### Prerequisites

| Dependency | Version | Notes |
|-----------|---------|-------|
| Java | 17 | JDK 17 |
| Maven | 3.8+ | Build tool |
| PostgreSQL | 15+ | Main database (Flyway auto-migration) |
| Redis | 7+ | Cache + distributed locks + Pub/Sub |
| Milvus | 2.6 | Vector database |
| Ollama | - | Embedding (pull `bge-m3` model) |
| Node.js | 18+ | Frontend build |

### Optional GPU Services

| Service | Port | Description |
|---------|------|-------------|
| BGE Reranker | 7997 | Cross-encoder reranking (GPU) |
| MinerU | 8000 | PDF batch parsing (GPU) |

### Configure Environment Variables

Set the required environment variables before starting the backend. `chatagent/.env.example` can be used as a local template, but do not commit real API keys.

```bash
# Required
CHATAGENT_DB_URL=jdbc:postgresql://localhost:5432/chatagent
CHATAGENT_DB_USERNAME=app
CHATAGENT_DB_PASSWORD=your_password
CHATAGENT_DEEPSEEK_API_KEY=your_deepseek_key
CHATAGENT_ZHIPUAI_API_KEY=your_zhipuai_key
CHATAGENT_JWT_SECRET=your-random-secret-key-at-least-32-chars

# Optional (local GPU defaults are in application.yaml)
CHATAGENT_RAG_EMBEDDING_BASE_URL=http://localhost:11434
CHATAGENT_RAG_RERANKER_BASE_URL=http://localhost:7997
```

### Start RabbitMQ

```bash
docker compose -f docker-compose-rabbitmq.yml up -d
```

Start PostgreSQL, Redis, Milvus, Ollama, and any optional GPU services separately according to your local environment.

### Start Backend

```powershell
cd chatagent
.\mvnw.cmd -pl framework,infra -am -DskipTests install
.\mvnw.cmd -pl bootstrap spring-boot:run
```

Or use the local GPU startup script from the repository root:

```powershell
.\start-local-gpu-backend.ps1
```

### Start Frontend

```bash
cd ui
npm install
npm run dev
```

### Access

| URL | Description |
|-----|-------------|
| `http://localhost:5173` | Frontend UI |
| `http://localhost:8080/health` | Backend health check |
| `http://localhost:15672` | RabbitMQ management (guest/guest) |

---

## Configuration

All configuration can be overridden via environment variables with the `CHATAGENT_` prefix. See `chatagent/bootstrap/src/main/resources/application.yaml` for details.

### Key Configuration Groups

| Prefix | Description |
|--------|-------------|
| `CHATAGENT_DB_*` | PostgreSQL connection |
| `CHATAGENT_DEEPSEEK_*` / `CHATAGENT_ZHIPUAI_*` | LLM provider API keys |
| `CHATAGENT_RAG_*` | RAG (Embedding/Reranker/VDP) |
| `CHATAGENT_MILVUS_*` | Milvus vector database |
| `CHATAGENT_MQ_*` | RabbitMQ + outbox + distributed locks |
| `CHATAGENT_MCP_*` | MCP tool integration (transport/runtime/drift) |
| `CHATAGENT_JWT_*` | JWT authentication |
| `chat.routing.*` | Model routing (first-packet timeout/circuit breaker/candidates) |
| `chatagent.intent.*` | Intent routing (classification threshold/clarification/rewrite model) |
| `chatagent.memory.*` | Memory management (L1 window/token budget/summary model) |
| `chatagent.session-guard.*` | Session concurrency guard (Redis lock TTL/Fail-Open) |

---

## License

This project is licensed under the terms of the [LICENSE](LICENSE) file.
