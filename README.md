# ChatAgent

<p align="right">
  <a href="README_EN.md"><strong>English</strong></a> | 中文
</p>

<p align="center">
  <strong>企业级 AI 智能工作台</strong>
</p>

<p align="center">
  多模型路由 · RAG 知识检索 · 意图路由 · MCP 工具集成 · 异步任务处理
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

## 目录

- [项目简介](#项目简介)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [核心流程图](#核心流程图)
- [数据库设计](#数据库设计)
- [接口设计](#接口设计)
- [项目结构](#项目结构)
- [技术亮点](#技术亮点)
- [设计模式](#设计模式)
- [快速开始](#快速开始)
- [配置说明](#配置说明)

---

## 项目简介

ChatAgent 是一个**企业级 AI 智能工作台后端系统**，基于 Spring Boot 3.5 + Spring AI 1.1 构建。普通用户通过聊天界面与 AI 助手交互，支持上传文件、检索知识库、调用外部工具；管理员通过管理后台管理知识库、意图路由树、Agent 配置、MCP 外部工具集成和系统监控。

### 核心能力

| 能力 | 说明 |
|------|------|
| **多模型智能路由** | 支持 DeepSeek / 智谱AI GLM 多供应商，首包探测自动切换，三态熔断器保护 |
| **RAG 知识检索** | 完整摄取流水线（PDF/Markdown/Tika/VLM/MinerU），Milvus 混合检索（Dense+BM25），BGE 重排序 |
| **树状意图路由** | DOMAIN→CATEGORY→TOPIC 三级层级路由，启发式+LLM 双阶段分类，澄清交互 |
| **AI Agent (ReAct)** | 思考-行动循环，内置工具（知识检索/SQL/邮件/文件系统）+ MCP 外部工具动态集成 |
| **异步任务处理** | RabbitMQ 事务性发件箱，三态分布式锁，结构化重试+DLQ |
| **用户认证** | JWT + Refresh Token 双令牌架构，RBAC 角色控制，Redis Token 存储 |
| **运维仪表盘** | 实时性能指标、会话趋势、MCP 告警、模型路由状态 |

---

## 技术栈

### 后端

| 层面 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.8 |
| AI 框架 | Spring AI | 1.1.0 |
| ORM | MyBatis | - |
| 关系数据库 | PostgreSQL | 15+ |
| 向量数据库 | Milvus (混合检索) | 2.6 |
| 消息队列 | RabbitMQ | 3.13 |
| 缓存 | Redis | 7+ |
| 数据库迁移 | Flyway | - |
| Embedding | Ollama + bge-m3 | 1024 维 |
| Reranker | BGE-reranker-v2-m3 (GPU) | - |
| PDF 解析 | MinerU / VLM / Apache Tika / PDFBox | - |
| 认证 | JWT (jjwt) | - |
| 构建 | Maven 多模块 | - |

### 前端

| 技术 | 版本 |
|------|------|
| React | 19 |
| Vite | 7 |
| TypeScript | 5.9 |
| Ant Design | 6 |
| TailwindCSS | 4 |
| Recharts | 3 |
| @ant-design/x | 2 (AI 聊天组件) |

### LLM 供应商

| 供应商 | 模型 | 用途 |
|--------|------|------|
| DeepSeek | deepseek-chat, deepseek-reasoner | 对话、推理、Agent |
| 智谱AI | glm-4.6, glm-5.1 | 对话、推理、视觉解析 |

---

## 系统架构

### 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                          前端 (React 19)                            │
│       管理后台 (Dashboard/知识库/意图树/MCP/用户)                    │
│       用户界面 (聊天/文件上传/引用面板)                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTP / SSE
┌──────────────────────────────▼──────────────────────────────────────┐
│                        Spring Boot 后端                             │
│                                                                     │
│  ┌──────────┐ ┌───────────┐ ┌──────────┐ ┌──────────┐             │
│  │ Auth     │ │Chat       │ │ Admin    │ │ SSE      │             │
│  │ JWT+RBAC │ │Session/Msg│ │ Dashboard│ │ Stream   │             │
│  └────┬─────┘ └─────┬─────┘ └────┬─────┘ └────┬─────┘             │
│       │             │            │             │                    │
│  ┌────▼─────────────▼────────────▼─────────────▼──────────────┐    │
│  │                   编排层 (Orchestration)                     │    │
│  │  ConversationOrchestrator ─ IntentRouter ─ EventDispatcher  │    │
│  │  SessionConcurrencyGuard ─ IncrementalSummarizer            │    │
│  └────────────────────────────┬───────────────────────────────┘    │
│                               │                                     │
│  ┌────────────────────────────▼───────────────────────────────┐    │
│  │                   Agent 运行时 (ReAct Loop)                  │    │
│  │  ThinkingEngine ─ ToolExecutionEngine ─ MessageBridge       │    │
│  │  三层记忆 (L1 短期 / L2 摘要 / L3 用户画像)                   │    │
│  └────────────┬─────────────────────────┬──────────────────────┘    │
│               │                         │                           │
│  ┌────────────▼──────────┐  ┌───────────▼──────────────────────┐   │
│  │   LLM 路由层 (infra)   │  │        RAG 知识引擎              │   │
│  │  首包探测 + 熔断器      │  │  解析 → 分块 → 增强 → Embedding  │   │
│  │  多供应商自动切换       │  │  混合检索 + RRF + 重排序          │   │
│  │  原始 SSE 高性能流式    │  │  Milvus (Dense + BM25)           │   │
│  └───────────────────────┘  └──────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐   │
│  │   MCP 工具集成    │  │   MQ 异步处理     │  │  用户认证       │   │
│  │  HTTP/SSE 双协议  │  │  事务性发件箱     │  │  双 JWT 架构    │   │
│  │  熔断+限流+SSRF   │  │  分布式锁+DLQ    │  │  Redis Token   │   │
│  └──────────────────┘  └──────────────────┘  └────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Maven 模块划分

```
chatagent/
├── chatagent-bootstrap   ← Spring Boot 启动 + 所有业务模块
│   ├── access/           ← RBAC (@RequireRole + ResourceAccessGuard)
│   ├── admin/            ← 管理后台 (Dashboard/用户/MCP/路由管理)
│   ├── agent/            ← Agent 运行时 (ReAct循环/工具/记忆)
│   ├── conversation/     ← 会话编排 (消息/SSE/摘要/并发保护)
│   ├── file/             ← 会话文件上传管理
│   ├── intent/           ← 意图路由 (层级树/分类/澄清/改写)
│   ├── knowledge/        ← 知识库/文档 CRUD + 摄取调度
│   ├── mcp/              ← MCP 工具集成 (传输/运行时保护/Schema漂移)
│   ├── mq/               ← RabbitMQ (发件箱/分布式锁/重试)
│   ├── rag/              ← RAG (解析/分块/Embedding/检索/重排序)
│   ├── support/          ← 共享 DTO/Entity/Mapper/Health
│   └── user/             ← 用户认证 (JWT/BCrypt/角色管理)
│
├── chatagent-framework   ← 横切关注点 (SSE/异常/追踪/API响应)
│
└── chatagent-infra       ← 基础设施 (LLM路由/邮件)
    └── chat/routing/     ← 首包探测/熔断器/流式响应
```

---

## 核心流程图

### 用户消息处理全流程

```
用户发送消息 POST /api/chat-messages
│
▼
SessionConcurrencyGuard (Redis 分布式锁)
│
▼
ConversationOrchestratorService.handleUserTurn()
│ ① 验证请求 → ② 组装上下文(会话+消息+历史) → ③ 一致性校验
│
▼
ConversationTurnPreparationService.prepare()
│
├─── 检查待澄清状态 (Redis, 5min TTL)
│    └── 匹配用户澄清回复 → 继续路由
│
├─── IntentRouter.route() — 层级意图路由
│    ├── 启发式评分 (bigram Jaccard, score ≥ 1.2 直接通过)
│    └── LLM 回退分类 (启发式不确定时才调用)
│
├─── 需要澄清 → 保存 PendingIntentResolution → 返回选项列表
├─── SYSTEM 意图 → 模板渲染 → 直接回复
└─── KB/TOOL 意图 → QueryRewriter 改写 → 调度 Agent
     │
     ▼
SwitchingChatEventDispatcher (Local / MQ 可切换)
     │
     ▼
ChatEventProcessor.process()
     │
     ▼
ChatAgentFactory.create()
│ 加载: Agent配置 + L1记忆 + L2摘要 + L3画像 + 工具列表 + 系统Prompt
│
▼
ChatAgent.run() — ReAct 循环 (最多 20 步)
│
│  ┌──────────────────────────────────────────┐
│  │           单步迭代 (step)                 │
│  │                                          │
│  │  AgentThinkingEngine.think()              │
│  │    → 无工具 → 流式输出最终答案 → 结束      │
│  │    → 有工具 → 流式+缓冲                    │
│  │       → 纯文本 → 直接成为最终答案          │
│  │       → 工具调用 → 回滚 → 执行工具         │
│  │                                          │
│  │  AgentToolExecutionEngine.execute()       │
│  │    → SessionFileTools → RAG 检索 + 引用   │
│  │    → DataBaseTools → 只读 SQL 查询        │
│  │    → MCP 工具 → 远程调用 (熔断+限流)      │
│  └──────────────────────────────────────────┘
│
▼
结果持久化 + SSE 推送 + 指标记录 + 异步摘要触发
```

### RAG 摄取流水线

```
文件上传
│
▼
FileSizeGuard (30MB 硬限制)
│
▼
FileTypeDetector (Magic-byte + 扩展名 + MIME)
│
▼
DocumentParserSelector ──────────────────────────────────────
│                       │              │              │       │
▼                       ▼              ▼              ▼       ▼
PdfDocumentParser   Markdown      Tika         Image       (拒绝)
│                   Parser        Parser       Parser
│                       │              │              │
├─ PDFBox 提取文本        │              │              │
├─ QualityRouter 逐页    │              │              │
│  ├─ 文本密度高 → Fast-Track   │              │
│  └─ 文本密度低 → Visual-Track  │              │
│     ├─ VlmVdpEngine (单页VLM)  │              │
│     └─ MinerUVdpEngine (批量)   │              │
└─ SegmentAssembler 组装   │              │              │
│                       │              │              │
└───────────────────────┴──────────────┴──────────────┘
                        │
                        ▼
              ParseResult + List<ParseSegment>
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
   文档增强         智能分块        块级上下文增强
 (LlmDocument     (SegmentAware   (LlmContextual
  Enhancer)        ChunkerRouter)  ChunkEnricher)
          │             │             │
          └─────────────┼─────────────┘
                        ▼
              Ollama Embedding (bge-m3, 1024维)
                        │
                        ▼
              Milvus Upsert (双 Collection)
              ├─ chat_file_chunk (会话文件)
              └─ chat_knowledge_chunk (知识库)
```

### RAG 检索流水线

```
用户查询
│
▼
Ollama Embedding → 查询向量
│
┌────────────────────────────────────────┐
│          Milvus 混合搜索                │
│                                        │
│  ┌─────────────────┐ ┌──────────────┐  │
│  │ Session File     │ │ Knowledge    │  │
│  │ Dense + BM25    │ │ Dense + BM25 │  │
│  │ → RRF 融合      │ │ → RRF 融合   │  │
│  └────────┬────────┘ └──────┬───────┘  │
│           │                 │          │
│           └────────┬────────┘          │
│                    ▼                   │
│             RRF 全局融合                │
│                    │                   │
│                    ▼                   │
│          知识库信号注入 (Redis MGET)     │
└────────────────────┬───────────────────┘
                     │
                     ▼
          重排序 (降级链)
          ├─ BGE HTTP (熔断器保护 + 置信度过滤)
          ├─ LLM Reranker (降级)
          └─ Noop (最终降级, 保持RRF顺序)
                     │
                     ▼
          RetrievalHitFormatter (带引用编号)
                     │
                     ▼
          返回给 Agent → 生成带引用的回答
```

### 首包探测路由流程

```
候选: [glm-5.1 (P:5), deepseek-reasoner (P:10)]
│
▼ ① ModelSelector: 过滤+排序+首选提升
│
▼ ② 遍历候选模型:

┌── glm-5.1 ──────────────────────────────────────────┐
│  healthStore.tryAcquire() → CLOSED → 允许           │
│  FirstPacketAwaiter + ProbeBufferingCallback         │
│  ProviderDirectStreamSupport.submit() (原始SSE)      │
│  awaiter.await(60s)                                  │
│  ├── 首包到达 → commit() → 刷出缓冲 → ✓ 采纳         │
│  └── 超时/失败 → dispose() → 丢弃缓冲 → ✗ 尝试下一个  │
└──────────────────────────────────────────────────────┘
         │ (失败)
         ▼
┌── deepseek-reasoner ────────────────────────────────┐
│  healthStore.tryAcquire() → 允许                     │
│  首包探测 ...                                        │
│  → 首包到达 → commit() → ✓ 采纳                      │
└──────────────────────────────────────────────────────┘
         │
         ▼ (全部失败)
  callback.onError()
```

### MQ 异步处理拓扑

```
┌──────────────────────────────────────────────────────┐
│  生产者 (业务 @Transactional 内)                       │
│                                                      │
│  OutboxEventPublisher.publish()                      │
│    → INSERT t_mq_outbox (PENDING) ← 同一事务         │
│    → ON CONFLICT DO NOTHING (UUIDv5 确定性ID)        │
│                                                      │
│  OutboxPollingPublisher (定时 2s)                     │
│    → SELECT ... FOR UPDATE SKIP LOCKED               │
│    → RabbitMQ publish + confirm                      │
│    → markSent                                        │
└───────────────────────────┬──────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────┐
│                    RabbitMQ 拓扑                      │
│                                                      │
│  chat.direct ─┬─ chat.agent.dispatch (DLX→retry)     │
│               └─ knowledge.ingest.task (DLX→retry)   │
│                                                      │
│  retry.direct ─┬─ retry.agent.10s (TTL=10s→chat)     │
│                └─ retry.ingest.30s (TTL=30s→chat)    │
│                                                      │
│  dlx.direct ─── chat.dlq (终极死信)                   │
└───────────────────────────┬──────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────┐
│  消费者 (AbstractRetryingMqConsumer)                   │
│                                                      │
│  ① 读取消息身份头 (7个不可变header)                     │
│  ② Task Lock 获取 (三态: ACQUIRED/DUPLICATE/WAIT)     │
│  ③ Session Exec Lock 获取 (Agent Run 专用)            │
│  ④ LockWatchdog 启动 (每20s续期)                      │
│  ⑤ processTask()                                     │
│  ⑥ 成功 → markCompleted + ack                        │
│  ⑦ 可重试 → publish to retry exchange + ack           │
│  ⑧ 不可重试 → reject to DLQ + markFailed             │
└──────────────────────────────────────────────────────┘
```

---

## 数据库设计

### ER 关系图

```
┌──────────┐     ┌──────────────┐     ┌────────────────┐
│  t_user  │────<│    agent     │────<│  chat_session  │
│──────────│     │──────────────│     │────────────────│
│ id (PK)  │     │ id (PK)      │     │ id (PK)        │
│ username │     │ user_id (FK) │     │ user_id (FK)   │
│ password │     │ name         │     │ agent_id (FK)  │
│ role     │     │ system_prompt│     │ title          │
│ status   │     │ model        │     │ metadata       │
│ deleted  │     │ allowed_tools│     └───────┬────────┘
└────┬─────┘     │ chat_options │             │
     │           └──────┬───────┘             │
     │                  │                     │
     │    ┌─────────────┤                     │
     │    │             │                     ▼
     │    ▼             ▼             ┌────────────────┐
     │ ┌──────────┐ ┌──────────┐     │  chat_message  │
     │ │user_prof.│ │agent_kb  │     │────────────────│
     │ │──────────│ │──────────│     │ id (PK)        │
     │ │user_id(FK│ │agent_id  │     │ session_id(FK) │
     │ │ summary  │ │kb_id(FK) │     │ seq_no (auto)  │
     │ └──────────┘ └──────────┘     │ turn_id        │
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
     │    │              知识库体系                        │
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
     │    │              意图路由树                        │
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
     │    │              MCP + MQ + 运维                  │
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

### 数据表总览 (21 张表)

| # | 表名 | 说明 | 关键字段 |
|---|------|------|---------|
| 1 | `t_user` | 用户账户 | username(UQ), password_hash, role(admin/user), status(ACTIVE/DISABLED), deleted |
| 2 | `user_profile` | 用户画像 | user_id(PK,FK), summary |
| 3 | `agent` | AI 助手配置 | user_id(FK), system_prompt, model, allowed_tools(JSONB), chat_options(JSONB) |
| 4 | `chat_session` | 聊天会话 | user_id(FK), agent_id(FK), title, metadata(JSONB) |
| 5 | `chat_message` | 聊天消息 | session_id(FK), seq_no(auto), turn_id, role, content, metadata(JSONB) |
| 6 | `chat_session_file` | 会话附件文件 | session_id(FK), filename, mime_type, size_bytes, storage_path, parse_status |
| 7 | `file_chunk` | 文件分块 | session_file_id(FK), chunk_index, content, metadata(JSONB) |
| 8 | `knowledge_base` | 知识库 | created_by(FK), name, visibility(SHARED), status(ACTIVE) |
| 9 | `knowledge_document` | 知识文档 | knowledge_base_id(FK), filename, parse_status, content_hash(SHA256), retry_count, deleted |
| 10 | `knowledge_chunk` | 知识分块 | knowledge_document_id(FK), chunk_index, content, metadata(JSONB) |
| 11 | `agent_knowledge_base` | Agent-KB 绑定 | agent_id(PK,FK), knowledge_base_id(PK,FK) — 多对多 |
| 12 | `intent_node` | 意图路由节点 | agent_id(FK), parent_id(FK→self), version, node_level, intent_kind, scope_policy, allowed_tools(JSONB) |
| 13 | `intent_knowledge_base` | 意图-KB 绑定 | intent_node_id(FK), knowledge_base_id(FK) |
| 14 | `chat_session_summary` | 会话增量摘要 | session_id(PK,FK), last_seq_no, summary, anchored_entities(JSONB), version(乐观锁) |
| 15 | `agent_template` | 助手模板 | code(UQ), system_prompt, model, allowed_tools(JSONB), intent_tree(JSONB), built_in |
| 16 | `t_mq_outbox` | 消息发件箱 | event_type, payload(JSONB), headers(JSONB), status(PENDING/CLAIMED/SENT/FAILED) |
| 17 | `knowledge_document_enhancement` | 文档增强信号 | knowledge_document_id(PK,FK), keywords(JSONB), questions(JSONB) |
| 18 | `t_chat_turn_metric` | 轮次指标 | session_id(FK), user_id(FK), turn_id, status(SUCCESS/ERROR), duration_ms, knowledge_hit |
| 19 | `t_mcp_server` | MCP 服务器 | slug(UQ-soft), protocol(HTTP/SSE), endpoint_url, encrypted_credentials, status(ACTIVE/DISABLED/FAILED/STALE) |
| 20 | `t_mcp_tool_catalog` | MCP 工具目录 | server_id(FK), exposed_model_name(UQ-soft), schema_json, status(ENABLED/DISABLED/STALE) |
| 21 | `t_mcp_alert_event` | MCP 告警 | server_id(FK), alert_type(SERVER_FAILED/SCHEMA_DRIFT/UNRESOLVED_REFERENCE), severity, status(OPEN/RESOLVED) |

---

## 接口设计

### 认证接口 (`/api/auth/*`)

无需认证。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册（自动登录） |
| POST | `/api/auth/login` | 用户登录 |
| POST | `/api/auth/refresh` | 刷新 Token (Cookie 中的 refresh_token) |
| POST | `/api/auth/logout` | 登出（撤销 Refresh Token） |
| GET | `/api/user/me` | 获取当前用户信息 |

### 用户接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/user/profile` | 获取用户画像 |
| PUT | `/api/user/profile` | 更新用户画像 |

### 会话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/chat-sessions` | 获取会话列表 |
| GET | `/api/chat-sessions/{id}` | 获取单个会话 |
| POST | `/api/chat-sessions` | 创建会话 |
| DELETE | `/api/chat-sessions/{id}` | 删除会话 |
| PATCH | `/api/chat-sessions/{id}` | 更新会话 |

### 消息接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/chat-messages/session/{sessionId}` | 获取会话消息列表 |
| POST | `/api/chat-messages` | 发送消息（触发 AI 回复） |
| DELETE | `/api/chat-messages/{id}` | 删除消息 |
| PATCH | `/api/chat-messages/{id}` | 更新消息 |

### SSE 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sse/connect/{sessionId}` | 建立 SSE 连接（接收 AI 流式响应） |
| GET | `/api/sse/admin/knowledge-bases/{kbId}/documents` | 文档状态 SSE 流 (Admin) |

### 文件接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/chat-sessions/{sessionId}/files` | 获取会话文件列表 |
| POST | `/api/chat-sessions/{sessionId}/files/upload` | 上传文件 (multipart) |
| DELETE | `/api/chat-sessions/{sessionId}/files/{fileId}` | 移除文件 |

### 工具接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tools` | 获取可选工具列表 |

### 管理接口 (`/api/admin/*`)

全部需要 `@RequireRole(ADMIN)`。

#### 知识库管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/knowledge-bases` | 获取知识库列表 |
| GET | `/api/admin/knowledge-bases/{id}` | 获取知识库详情 |
| POST | `/api/admin/knowledge-bases` | 创建知识库 |
| PATCH | `/api/admin/knowledge-bases/{id}` | 更新知识库 |
| DELETE | `/api/admin/knowledge-bases/{id}` | 删除知识库（级联） |
| GET | `/api/admin/knowledge-bases/{kbId}/documents` | 获取文档列表 |
| POST | `/api/admin/knowledge-bases/{kbId}/documents/upload` | 上传文档 |
| POST | `/api/admin/knowledge-bases/{kbId}/documents/{docId}/replace` | 替换文档 |
| DELETE | `/api/admin/knowledge-bases/{kbId}/documents/{docId}` | 删除文档 |

#### 助手管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/assistant/knowledge-bases` | 获取助手绑定的知识库 |
| PUT | `/api/admin/assistant/knowledge-bases` | 设置助手绑定的知识库 |
| GET | `/api/admin/assistant/templates` | 获取模板列表 |
| POST | `/api/admin/assistant/templates` | 创建模板 |
| GET | `/api/admin/assistant/templates/{id}` | 获取模板详情 |
| PATCH | `/api/admin/assistant/templates/{id}` | 更新模板 |
| DELETE | `/api/admin/assistant/templates/{id}` | 删除模板 |
| POST | `/api/admin/assistant/templates/{id}/initialize` | 从模板初始化助手 |

#### 意图路由树

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/assistant/intent-tree` | 获取意图树（草稿+版本） |
| POST | `/api/admin/assistant/intent-tree/nodes` | 创建节点 |
| PATCH | `/api/admin/assistant/intent-tree/nodes/{id}` | 更新节点 |
| DELETE | `/api/admin/assistant/intent-tree/nodes/{id}` | 删除节点+子树 |
| PUT | `/api/admin/assistant/intent-tree/nodes/{id}/knowledge-bases` | 绑定知识库 |
| POST | `/api/admin/assistant/intent-tree/publish` | 发布为新版本 |
| GET | `/api/admin/assistant/intent-tree/versions` | 获取版本列表 |
| PUT | `/api/admin/assistant/intent-tree/versions/{ver}/activate` | 激活版本 |

#### MCP 服务器管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/mcp-servers` | 获取 MCP 服务器列表 |
| GET | `/api/admin/mcp-servers/{id}` | 获取服务器详情（含工具目录） |
| POST | `/api/admin/mcp-servers` | 创建 MCP 服务器 |
| PATCH | `/api/admin/mcp-servers/{id}` | 更新 MCP 服务器 |
| DELETE | `/api/admin/mcp-servers/{id}?force=false` | 删除 MCP 服务器 |
| POST | `/api/admin/mcp-servers/{id}/test` | 测试连通性 |
| POST | `/api/admin/mcp-servers/{id}/sync` | 同步工具目录 |

#### 模型路由管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/chat-routing/state` | 获取路由状态（含熔断状态） |
| PUT | `/api/admin/chat-routing/candidates/override` | 运行时覆盖候选配置 |
| DELETE | `/api/admin/chat-routing/candidates/{id}/override` | 清除覆盖 |

#### Dashboard

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/dashboard/overview?window=24h` | KPI 概览 |
| GET | `/api/admin/dashboard/performance?window=24h` | 性能指标 |
| GET | `/api/admin/dashboard/trends?metric=sessions&window=7d` | 趋势数据 |
| GET | `/api/admin/dashboard/mcp-alerts?limit=20` | MCP 告警 |

#### 用户管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/users?page=1&size=10` | 获取用户列表 |
| POST | `/api/admin/users` | 创建用户（返回初始密码） |
| PUT | `/api/admin/users/{id}` | 更新用户（角色/头像） |
| PUT | `/api/admin/users/{id}/status` | 启用/禁用用户 |
| PUT | `/api/admin/users/{id}/password/reset` | 重置密码 |
| DELETE | `/api/admin/users/{id}` | 软删除用户 |

#### MQ 管理（条件激活）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/mq/outbox/retry` | 查看 Outbox 重试状态 |
| POST | `/api/admin/mq/dlq/replay` | 重放死信消息 |

### SSE 事件类型

| 事件 | 数据 | 说明 |
|------|------|------|
| `AI_GENERATED_CONTENT` | ChatMessageVO | AI 内容快照 |
| `AI_THINKING` | thinking text | 推理/思考过程 |
| `AI_DONE` | done=true | 轮次完成 |
| `AI_ERROR` | error message | 错误通知 |
| `TURN_ROLLBACK` | turnId | 回滚已展示内容 |
| `DOCUMENT_STATUS_UPDATED` | document status | 文档摄取状态变化 |

---

## 项目结构

```
ChatAgent/
├── chatagent/                          # 后端 Maven 多模块
│   ├── pom.xml                         # 父 POM (Spring Boot 3.5.8)
│   ├── bootstrap/                      # 业务模块
│   │   ├── pom.xml
│   │   └── src/main/java/com/yulong/chatagent/
│   │       ├── access/                 # RBAC 权限控制
│   │       ├── admin/                  # 管理后台服务
│   │       ├── agent/                  # Agent 运行时 (ReAct)
│   │       ├── conversation/           # 会话编排
│   │       ├── file/                   # 文件管理
│   │       ├── intent/                 # 意图路由
│   │       ├── knowledge/              # 知识库管理
│   │       ├── mcp/                    # MCP 工具集成
│   │       ├── mq/                     # 消息队列
│   │       ├── rag/                    # RAG 流水线
│   │       ├── support/                # 共享基础设施 (DTO/Entity/Mapper)
│   │       └── user/                   # 用户认证
│   ├── framework/                      # 横切关注点
│   │   └── src/main/java/com/yulong/chatagent/
│   │       ├── config/                 # Async/CORS 配置
│   │       ├── context/                # UserContext
│   │       ├── errorcode/              # 错误码
│   │       ├── exception/              # 异常体系
│   │       ├── model/                  # ApiResponse
│   │       ├── sse/                    # SSE 基础设施
│   │       └── trace/                  # 链路追踪
│   ├── infra/                          # 基础设施
│   │   └── src/main/java/com/yulong/chatagent/
│   │       ├── chat/                   # ChatClient 注册/路由
│   │       └── mail/                   # 邮件服务
│   └── bootstrap/src/main/resources/
│       ├── application.yaml            # 主配置 (350行)
│       ├── application-local-gpu.yaml  # 本地GPU配置
│       ├── prompts/                    # Prompt 集中管理 (24个.md)
│       │   ├── agent/                  # Agent 核心 prompt + sections
│       │   ├── intent/                 # 意图分类 + 查询重写
│       │   ├── rag/                    # RAG 摄取 + 检索 prompt
│       │   ├── vlm/                    # VLM 视觉解析
│       │   ├── summarizer/             # 滚动摘要
│       │   └── fallbacks/              # 缺省文本
│       ├── db/migration/               # Flyway 迁移 (V1-V16)
│       └── mapper/                     # MyBatis XML (22个)
│
├── ui/                                 # 前端
│   ├── src/
│   │   ├── api/                        # HTTP 客户端
│   │   ├── auth/                       # Token 管理
│   │   ├── components/
│   │   │   ├── admin/                  # 管理后台页面
│   │   │   ├── auth/                   # 登录页
│   │   │   └── views/agentChatView/    # 聊天视图
│   │   ├── contexts/                   # React Context
│   │   ├── hooks/                      # useAuth, useChatSessions
│   │   ├── layout/                     # 布局组件
│   │   └── types/                      # TypeScript 类型
│   └── package.json
│
├── tools/                              # 外部工具
│   ├── bge-reranker-server/            # BGE 重排序 HTTP 服务
│   └── mineru/                         # MinerU PDF 解析服务
│
├── MCP/                                # MCP 工具服务器示例
│   └── weather-server/                 # 天气工具 (HTTP+SSE)
│
├── docs/                               # 文档
│   ├── arch/                           # 架构文档 (17个)
│   ├── plans/                          # 设计方案 (13个)
│   └── summary/                        # 模块总结 (11个)
│
├── postman/                            # Postman API 集合
├── data/                               # 运行时数据
│   ├── documents/                      # 上传文件存储
│   ├── milvus/                         # Milvus 数据
│   └── rabbitmq/                       # RabbitMQ 数据
│
├── docker-compose-rabbitmq.yml         # RabbitMQ Docker
├── start-local-gpu-backend.ps1         # 本地GPU启动脚本
└── README.md
```

---

## 技术亮点

### 1. 首包探测路由 (First Packet Detection)

流式场景下按优先级串行尝试多个 LLM 供应商，等待第一个有效数据包。超时/失败则丢弃缓冲并尝试下一个。`ProbeBufferingCallback` 确保客户端不收到不完整数据。

### 2. 三层熔断保护

| 层级 | 熔断器 | 保护对象 |
|------|--------|---------|
| LLM 供应商 | `ModelHealthStore` (三态: CLOSED/OPEN/HALF_OPEN) | DeepSeek / GLM |
| 重排序服务 | `RerankerCircuitBreaker` (滑动窗口 100s) | BGE Reranker |
| MCP 外部工具 | `McpServerCircuitBreaker` (每服务器独立) | 第三方工具 |

全部支持 probeGeneration 防污染和飞行超时保护。

### 3. 事务性发件箱 (Transactional Outbox)

业务操作和消息写入同一数据库事务，保证至少一次投递。`OutboxPollingPublisher` 使用 `SELECT ... FOR UPDATE SKIP LOCKED` 多实例安全 claim，publisher confirm 确认后 markSent。

### 4. 三态分布式锁

RUNNING / COMPLETED / FAILED 三态锁保证幂等消费。`LockWatchdog` 每 20s 续期支持长时间任务。按任务类型选择 Fail-Open / Fail-Fast 策略。

### 5. 混合检索 + RRF 融合

Milvus 同时支持 Dense 向量搜索和 BM25 稀疏搜索，RRF (Reciprocal Rank Fusion) 融合两种结果，重排序有完整降级链 (BGE → LLM → Noop)。

### 6. 双轨 PDF 解析

QualityRouter 逐页评估：文本密度高用 Fast-Track (PDFBox 原生提取)，文本密度低用 Visual-Track (渲染为图片 → VLM/MinerU 解析)。

### 7. 单次路由流 (Single Routed Stream)

模型输出同时流式展示+缓冲。有工具调用则回滚已展示内容，无工具调用则直接成为最终答案，避免第二次模型调用。

### 8. 三层记忆体系

L1 短期记忆 (Token 预算 + 轮次滑动窗口) / L2 增量摘要 (事件驱动 + LLM + 确定性回退) / L3 用户画像 (跨会话持久化)。

### 9. MCP 安全集成

SSRF 防护 + AES-256-GCM 凭据加密 + Schema 漂移检测 + 令牌桶限流 + Prompt 安全警告。

### 10. Prompt 集中管理

所有 46 个 AI prompt 从 Java 源码中提取，统一存放在 `resources/prompts/` 目录（24 个 `.md` 文件），按业务域分类（agent/intent/rag/vlm/summarizer/fallbacks）。通过 `PromptLoader` 组件加载，支持 `{{variable}}` 模板变量替换和 `ConcurrentHashMap` 内存缓存。每个 prompt 均按企业级标准重写，具备统一的 Role / Rules / Guardrails / Output Format 结构。V16 迁移将默认 agent 的 `system_prompt` 设为 NULL 以启用集中加载。详见 [Prompt 集中管理模块总结](docs/summary/11-prompt-management.md)。

---

## 设计模式

本项目在关键架构节点大量运用经典设计模式，确保可扩展性和可维护性。

### 创建型

| 模式 | 实现类 | 说明 |
|------|--------|------|
| **Factory** | `ChatAgentFactory`, `AgentToolCallbackFactory`, `McpToolDefinitionFactory` | 按运行时上下文组装完全配置的 Agent / 工具列表 / MCP 元数据 |
| **Builder** | 30+ DTO/VO 类 (`ChatMessageVO`, `AgentDTO`, `McpServerMetricsSnapshot` 等) | Lombok `@Builder` 生成流畅构建 API |

### 结构型

| 模式 | 实现类 | 说明 |
|------|--------|------|
| **Facade** | 15+ 门面服务 (`ConversationOrchestratorService`, `AssistantTemplateFacadeServiceImpl`, `DashboardFacadeServiceImpl` 等) | 每个业务域一个 `*FacadeServiceImpl`，对外暴露简化 API，内部编排多个 Port/Service |
| **Adapter** | `McpToolCallbackAdapter`, `ChatModelProviderRegistry` | 将 MCP 远程调用适配为 Spring AI `ToolCallback`；将 DeepSeek / ZhiPu 统一为 `ProviderBinding` 接口 |
| **Proxy** | `ProbeBufferingCallback`, `SwitchingChatEventDispatcher` | 流式首包探测代理；本地/MQ 双通道切换代理 |
| **Bridge** | `ChatModelRouter` → `ChatClient` | 解耦 LLM 供应商选择与具体调用，运行时路由到不同 `ChatClient` 实例 |

### 行为型

| 模式 | 实现类 | 说明 |
|------|--------|------|
| **Strategy** | `RetrievalReranker` (BGE / LLM / Noop), `VdpEngine` (VLM / MinerU / Noop), `DocumentParser` (PDF / Markdown / Tika / Image) | 算法族独立封装，运行时按策略选择 |
| **Chain of Responsibility** | 重排序降级链: BGE → LLM → Noop；LLM 路由: 优先级逐个探测 | 请求沿链传递，每节点决定处理或传递 |
| **Template Method** | `AbstractRetryingMqConsumer<T>` | 定义 MQ 消费骨架（锁 → 执行 → 重试/DLQ），子类实现 `processTask()` 等 hook |
| **Observer** | `ChatEventDispatcher` → `ChatEventListener` / `AsyncSummaryListener` + `SseService` | 事件驱动的对话处理和前端 SSE 推送 |
| **State** | `ModelHealthStore` (CLOSED/OPEN/HALF_OPEN), `MqTaskLockState` (RUNNING/COMPLETED/FAILED) | 熔断器和分布式锁的三态状态机驱动行为切换 |

### 架构型

| 模式 | 实现类 | 说明 |
|------|--------|------|
| **Circuit Breaker** | `ModelHealthStore`, `RerankerCircuitBreaker`, `McpServerCircuitBreaker` | 三层独立熔断，防止级联故障 |
| **Outbox** | `OutboxEventPublisher` + `OutboxPollingPublisher` | 事务性发件箱，业务写与消息发放在同一事务内 |
| **Port/Adapter (六边形)** | `ChatSessionSummaryRepository`, `AgentRepository` 等 Port + `MyBatis*Adapter` 实现 | 领域逻辑与持久化技术解耦 |
| **Registry** | `ChatClientRegistry`, `McpRuntimeToolRegistry`, `McpServerCircuitBreakerRegistry` | 集中管理命名实例，支持运行时动态查询 |

---

## 快速开始

### 环境依赖

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17 | JDK 17 |
| Maven | 3.8+ | 构建工具 |
| PostgreSQL | 15+ | 主数据库 (Flyway 自动迁移) |
| Redis | 7+ | 缓存 + 分布式锁 + Pub/Sub |
| Milvus | 2.6 | 向量数据库 |
| Ollama | - | Embedding (拉取 `bge-m3` 模型) |
| Node.js | 18+ | 前端构建 |

### 可选 GPU 服务

| 服务 | 端口 | 说明 |
|------|------|------|
| BGE Reranker | 7997 | 交叉编码器重排序 (GPU) |
| MinerU | 8000 | PDF 批量解析 (GPU) |

### 配置环境变量

复制 `chatagent/.env.example` 并填写：

```bash
# 必填
CHATAGENT_DB_URL=jdbc:postgresql://localhost:5432/chatagent
CHATAGENT_DB_USERNAME=app
CHATAGENT_DB_PASSWORD=your_password
CHATAGENT_DEEPSEEK_API_KEY=your_deepseek_key
CHATAGENT_ZHIPUAI_API_KEY=your_zhipuai_key
CHATAGENT_JWT_SECRET=your-random-secret-key-at-least-32-chars

# 可选 (本地GPU默认值已在application.yaml中配置)
CHATAGENT_RAG_EMBEDDING_BASE_URL=http://localhost:11434
CHATAGENT_RAG_RERANKER_BASE_URL=http://localhost:7997
```

### 启动 RabbitMQ

```bash
docker compose -f docker-compose-rabbitmq.yml up -d
```

### 启动后端

```bash
cd chatagent
mvn spring-boot:run
```

或使用本地 GPU 全栈启动脚本：

```powershell
.\start-local-gpu-backend.ps1
```

### 启动前端

```bash
cd ui
npm install
npm run dev
```

### 访问

| 地址 | 说明 |
|------|------|
| `http://localhost:5173` | 前端界面 |
| `http://localhost:8080/health` | 后端健康检查 |
| `http://localhost:15672` | RabbitMQ 管理后台 (guest/guest) |

---

## 配置说明

所有配置通过环境变量覆盖，前缀为 `CHATAGENT_`。详见 `chatagent/bootstrap/src/main/resources/application.yaml`。

### 关键配置分组

| 配置前缀 | 说明 |
|---------|------|
| `CHATAGENT_DB_*` | PostgreSQL 连接 |
| `CHATAGENT_DEEPSEEK_*` / `CHATAGENT_ZHIPUAI_*` | LLM 供应商 API Key |
| `CHATAGENT_RAG_*` | RAG (Embedding/Reranker/VDP) |
| `CHATAGENT_MILVUS_*` | Milvus 向量数据库 |
| `CHATAGENT_MQ_*` | RabbitMQ + 发件箱 + 分布式锁 |
| `CHATAGENT_MCP_*` | MCP 工具集成 (传输/运行时保护/Schema漂移) |
| `CHATAGENT_JWT_*` | JWT 认证 |
| `chat.routing.*` | 模型路由 (首包超时/熔断参数/候选列表) |
| `chatagent.intent.*` | 意图路由 (分类阈值/澄清/改写模型) |
| `chatagent.memory.*` | 记忆管理 (L1窗口/Token预算/摘要模型) |
| `chatagent.session-guard.*` | 会话并发保护 (Redis锁TTL/Fail-Open) |

---

## License

This project is licensed under the terms of the [LICENSE](LICENSE) file.
