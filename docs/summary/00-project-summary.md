# ChatAgent 项目总结 — 面试参考手册

> 本文档是 ChatAgent 项目的完整技术总结，覆盖架构设计、核心模块、技术亮点和面试讲解要点。

---

## 一、项目定位

ChatAgent 是一个**企业级 AI 智能工作台后端系统**，基于 Spring Boot 3.5 + Spring AI 1.1 构建。普通用户通过聊天界面与 AI 助手交互，管理员通过管理后台管理知识库、意图树、Agent 配置和 MCP 工具集成。

**技术栈总览：**

| 层面 | 技术 |
|------|------|
| 框架 | Spring Boot 3.5, Spring AI 1.1 |
| 大模型 | DeepSeek (对话/推理), 智谱AI GLM (对话/推理/视觉) |
| 向量数据库 | Milvus (稠密 + BM25 稀疏混合搜索) |
| 关系数据库 | PostgreSQL + MyBatis + Flyway |
| 消息队列 | RabbitMQ (事务性发件箱) |
| 缓存 | Redis (分布式锁/Token存储/会话状态/Pub/Sub) |
| 本地推理 | Ollama (bge-m3 embedding), BGE Reranker (GPU), MinerU (PDF解析) |
| 前端 | React 19 + Vite + TypeScript + Ant Design 6 + TailwindCSS |
| 构建 | Maven 多模块 (bootstrap / framework / infra) |

---

## 二、系统架构

### 2.1 Maven 模块划分

```
chatagent/
├── chatagent-bootstrap    # Spring Boot 启动 + 所有业务领域
├── chatagent-framework    # 横切关注点 (SSE/异常/追踪/API响应)
└── chatagent-infra        # 基础设施 (LLM路由/邮件)
```

### 2.2 业务模块架构

```
┌──────────────────────────────────────────────────────────────────┐
│                        前端 (React 19)                           │
│   管理后台 (Agent/知识库/意图树/MCP/用户/Dashboard)               │
│   用户界面 (聊天/文件上传/引用面板)                                │
└───────────────────────────────┬──────────────────────────────────┘
                                │ HTTP / SSE
┌───────────────────────────────▼──────────────────────────────────┐
│                     Controller 层                                │
│   Auth / ChatSession / ChatMessage / SSE / Admin/*              │
│   [JWT拦截器] [RBAC拦截器] [TraceId过滤器]                       │
└───────────────────────────────┬──────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│                     编排层 (Orchestration)                        │
│                                                                  │
│  ConversationOrchestratorService                                 │
│    └── ConversationTurnPreparationService (意图路由)              │
│    └── SwitchingChatEventDispatcher (Local / MQ)                 │
│    └── SessionConcurrencyGuard (Redis 分布式锁)                  │
└──────────┬──────────────────────────────────┬───────────────────┘
           │                                  │
┌──────────▼──────────┐          ┌───────────▼────────────────────┐
│   Agent 运行时       │          │   MQ 异步处理                   │
│   (ReAct 循环)       │          │   (RabbitMQ + Outbox)           │
│                     │          │                                │
│  AgentThinkingEngine│          │  AgentRunTaskListener          │
│  AgentToolExecution │          │  KnowledgeIngestTaskListener   │
│  AgentMemoryLoader  │          │  DistributedLockManager        │
│  AgentMessageBridge │          │  OutboxEventPublisher          │
└──────────┬──────────┘          └───────────┬────────────────────┘
           │                                  │
┌──────────▼──────────────────────────────────▼───────────────────┐
│                     基础设施层 (infra)                            │
│                                                                  │
│  RoutingLLMService (首包探测 + 熔断 + 多供应商路由)               │
│  ModelHealthStore (三态熔断器)                                    │
│  ProviderDirectStreamSupport (原始SSE高性能流式)                  │
│  ChatClientRegistry (多模型注册: DeepSeek/GLM)                   │
└──────────┬──────────────────────────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────────────────────────┐
│                     RAG 知识引擎                                 │
│                                                                  │
│  摄取: 解析(PDF/MD/Tika/VLM/MinerU) → 分块 → 增强 → Embedding   │
│  检索: 混合搜索(Dense+BM25) → RRF融合 → 重排序(BGE/LLM/Noop)     │
│  存储: Milvus (双Collection: 会话文件 + 知识库)                   │
└──────────────────────────────────────────────────────────────────┘
```

### 2.3 请求处理全流程

```
用户发送消息
    │
    ▼
ChatMessageController (SessionConcurrencyGuard 加锁)
    │
    ▼
ConversationOrchestratorService.handleUserTurn()
    │ 验证 → 组装上下文 → 持久化用户消息 → 加载历史
    │
    ▼
ConversationTurnPreparationService.prepare()
    │ 意图路由 → 澄清检测 → 查询改写
    │
    ├── 直接回复 (SYSTEM/CLARIFY/无意图树)
    │   → 持久化助手消息 + SSE 推送
    │
    └── Agent 调度
        │
        ├── Local: Spring ApplicationEvent (异步)
        └── MQ: OutboxEventPublisher → RabbitMQ → AgentRunTaskListener
            │
            ▼
        ChatEventProcessor.process()
            │
            ▼
        ChatAgentFactory.create() → ChatAgent.run()
            │
            │  ReAct 循环 (最多20步):
            │    AgentThinkingEngine.think() → LLM 推理
            │    AgentToolExecutionEngine.execute() → 工具执行
            │    (SessionFileTools → RAG 检索 → 引用追踪)
            │
            ▼
        结果持久化 + SSE 流式推送 + 指标记录 + 摘要触发
```

---

## 三、核心模块概览

### 3.1 LLM 路由模块 (infra) — [详见 01-llm-routing.md](01-llm-routing.md)

**职责：** 多大模型供应商的接入、路由选择、健康检查、熔断保护、流式响应处理。

**核心组件：**
- `RoutingLLMService` — 核心路由服务，支持同步和流式两条路径
- `ModelHealthStore` — 三态熔断器 (CLOSED/OPEN/HALF_OPEN)
- `FirstPacketAwaiter` — 首包探测等待器
- `ProviderDirectStreamSupport` — 绕过 Spring AI 的原始 SSE 解析
- `ModelSelector` — 候选模型筛选排序

**支持模型：** DeepSeek (chat/reasoner), 智谱AI GLM (4.6/5.1)

### 3.2 Agent 运行时模块 — [详见 02-agent-runtime.md](02-agent-runtime.md)

**职责：** AI Agent 的 ReAct 循环执行，包括推理、工具调用、上下文管理和结果产出。

**核心组件：**
- `ChatAgent` — Agent 编排器，ReAct 循环入口
- `AgentThinkingEngine` — 思考阶段（LLM 推理）
- `AgentToolExecutionEngine` — 工具执行阶段
- `AgentMessageBridge` — 连接 Agent 输出到 DB + SSE
- `DefaultAgentRuntimeContextLoader` — 运行时上下文组装（三层记忆 + 工具 + 系统提示词）

**内置工具：** SessionFileTools (RAG), DataBaseTools (只读SQL), EmailTools, FileSystemTools

### 3.3 会话编排模块 — [详见 03-conversation-orchestration.md](03-conversation-orchestration.md)

**职责：** 用户消息的入口调度，会话/消息生命周期管理，SSE 推送，增量摘要。

**核心组件：**
- `ConversationOrchestratorService` — 四阶段编排（验证→组装→校验→调度）
- `SwitchingChatEventDispatcher` — 双分发策略（Local/MQ 可切换）
- `SessionConcurrencyGuard` — Redis 分布式会话锁
- `IncrementalSummarizer` — 事件驱动增量摘要（LLM + 确定性回退）
- `ChatTurnMetricRecorder` — 轮次指标记录

### 3.4 RAG 检索增强模块 — [详见 04-rag-pipeline.md](04-rag-pipeline.md)

**职责：** 文档摄取和检索的完整流水线。

**核心组件：**
- `PdfDocumentParser` — PDF 解析（质量路由 + VDP 双轨道）
- `SegmentAwareChunkerRouter` — Segment 感知智能分块
- `LlmContextualChunkEnricher` — Anthropic 风格上下文增强
- `SearchScopeResolver` — 双 Collection 检索编排 + RRF 融合
- `BgeHttpRetrievalReranker` — BGE 重排序（熔断器 + 置信度过滤）
- `RerankerCircuitBreaker` — 滑动窗口熔断器

**双 Collection：** `chat_file_chunk` (会话文件) + `chat_knowledge_chunk` (知识库)

### 3.5 意图路由模块 — [详见 05-intent-routing.md](05-intent-routing.md)

**职责：** 树状层级意图分类，将用户输入映射到知识库/工具/系统响应。

**核心组件：**
- `IntentRouter` — 层级路由引擎（启发式 + LLM 回退）
- `ConversationTurnPreparationService` — 编排入口（澄清+路由+改写）
- `ClarificationResolver` — 中文澄清解析（序号/名称匹配）
- `QueryRewriter` — 查询改写（LLM + 回退）
- `IntentTreeCacheManager` — 两层缓存（Redis + DB）

**三级结构：** DOMAIN → CATEGORY → TOPIC

### 3.6 MCP 集成模块 — [详见 06-mcp-integration.md](06-mcp-integration.md)

**职责：** 外部第三方工具的安全动态集成。

**核心组件：**
- `WebClientMcpTransportClient` — 双协议传输 (HTTP + Legacy SSE)
- `McpToolCallbackAdapter` — Spring AI ToolCallback 桥接
- `McpServerCircuitBreaker` — 每服务器熔断器
- `McpServerRateLimiter` — 令牌桶限流
- `McpSchemaDriftDetector` — Schema 漂移检测
- `McpEndpointValidator` — SSRF 防护

### 3.7 MQ 异步处理模块 — [详见 07-mq-async.md](07-mq-async.md)

**职责：** 分布式异步任务处理，确保消息不丢失和幂等消费。

**核心组件：**
- `OutboxEventPublisher` / `OutboxPollingPublisher` — 事务性发件箱
- `DistributedLockManager` — 三态分布式锁 (RUNNING/COMPLETED/FAILED)
- `AbstractRetryingMqConsumer` — 重试/DLQ 消费者框架
- `LockWatchdog` — 锁看门狗自动续期

**拓扑：** 主队列 → TTL延迟重试队列 → 回到主队列 → DLQ

### 3.8 用户认证模块 — [详见 08-user-auth.md](08-user-auth.md)

**职责：** 用户注册/登录、Token 管理、RBAC 角色控制、用户管理。

**核心组件：**
- `JwtTokenService` — JWT Access Token 生成/解析
- `RedisRefreshTokenStore` — Redis 存储 Refresh Token (SHA-256 哈希)
- `JwtAuthenticationInterceptor` — 请求认证拦截
- `AuthenticatedUserSnapshotCache` — Caffeine 快照缓存

### 3.9 Framework 公共基础设施 — [详见 09-framework-infra.md](09-framework-infra.md)

**职责：** SSE 推送、异常体系、链路追踪、API 响应、异步配置。

### 3.10 知识库管理 & Admin — [详见 10-knowledge-admin.md](10-knowledge-admin.md)

**职责：** 知识库/文档 CRUD、会话文件管理、管理后台 Dashboard。

---

## 四、技术亮点深度解析

### 4.1 首包探测 (First Packet Detection) ★★★

**问题：** 流式场景下，一旦选择了某个 LLM 供应商，即使响应极慢也只能等到超时才能切换。

**解决方案：**

```
                    首包探测流程
                    ════════════

候选: glm-5.1 (优先级1), deepseek-chat (优先级2), deepseek-reasoner (优先级3)

Step 1: 向 glm-5.1 发起流式请求
        │
        ├── 等待 firstPacketTimeoutSeconds (默认60s)
        │
        ├── 首包到达 → commit() → 刷出缓冲 → glm-5.1 承担本次请求 ✓
        │
        └── 超时/失败 → dispose() → 丢弃缓冲 → 尝试下一个

Step 2: 向 deepseek-chat 发起流式请求
        │
        ├── 首包到达 → commit() ✓
        │
        └── 超时/失败 → 尝试下一个

Step 3: ... (继续尝试)

全部失败 → callback.onError()
```

**关键实现细节：**
1. **ProbeBufferingCallback：** 首包探测期间，所有流事件被缓冲而非发送给客户端。探测失败时缓冲被丢弃，客户端不会收到不完整数据。
2. **ProviderDirectStreamSupport：** 绕过 Spring AI 抽象层，直接用 WebClient 解析原始 SSE，获得最高性能。
3. **RawToolCallAccumulator：** 工具调用参数跨 chunk 增量累积，在 `finishReason=TOOL_CALLS` 时刷出。

**面试话术：** "我实现了一个首包探测路由机制。在流式场景下，系统会按优先级串行尝试多个 LLM 供应商。对于每个候选，我们启动流式请求并等待第一个有效数据包。如果在超时时间内收到首包，就提交这个供应商，将缓冲的数据刷给客户端。如果超时或失败，就丢弃缓冲并尝试下一个供应商。这避免了绑定到慢速供应商的问题，同时通过探测缓冲机制确保客户端不会收到不完整数据。"

---

### 4.2 熔断机制 (Circuit Breaker) ★★★

项目中存在**三种熔断器**，分别保护不同层级：

#### 4.2.1 ModelHealthStore — LLM 供应商熔断

```
CLOSED (正常) ──连续失败>=3──► OPEN (熔断)
    ▲                              │
    │ 探测成功                      │ 等待5分钟
    │                              ▼
    └────────── HALF_OPEN (半开) ◄──┘
                    │ 探测失败
                    ▼
                  OPEN (重新计时)
```

**防污染机制：** probeGeneration 计数器防止过期探测结果影响状态。飞行超时 (2分钟) 防止探测卡死。

#### 4.2.2 RerankerCircuitBreaker — 重排序服务熔断

滑动窗口 (100秒)，双阈值触发（失败率 >= 50% AND 失败数 >= 5 AND 请求数 >= 10）。

**降级链：** BGE HTTP → LLM Reranker → Noop (保持 RRF 顺序)

#### 4.2.3 McpServerCircuitBreaker — MCP 外部工具熔断

每服务器独立熔断，双阈值（失败率 + 慢调用率），独立连接池隔离故障域。

**面试话术：** "我设计了三层熔断保护。LLM 供应商层有三态熔断器，连续失败3次自动开启，5分钟后进入半开状态允许探测请求。重排序层有滑动窗口熔断器，带完整的降级链：BGE → LLM → Noop。MCP 工具层每服务器独立熔断，还叠加了令牌桶限流和响应截断保护。所有熔断器都有探测防污染机制和飞行超时保护。"

---

### 4.3 高可用设计 ★★★

#### 4.3.1 多供应商冗余

DeepSeek 和智谱AI 互为备份，通过首包探测自动切换。ModelSelector 按优先级排序，RoutingLLMService 逐个尝试。

#### 4.3.2 事务性发件箱 (Transactional Outbox)

```
业务操作 + 消息写入同一事务 → 至少一次投递
OutboxPollingPublisher 轮询发布 (SKIP LOCKED 多实例安全)
Publisher Confirm 确认后 markSent
```

#### 4.3.3 Fail-Open 设计

| 组件 | Fail-Open 行为 |
|------|---------------|
| SessionConcurrencyGuard | Redis 故障时返回空锁，不阻塞 |
| KnowledgeDocumentSignalService | Redis/DB 故障时不附加信号 |
| 分布式锁 (Agent Run) | Redis 故障时继续执行 |
| Micrometer 指标 | MeterRegistry 缺失时 no-op |

#### 4.3.4 SSE 集群广播

Redis Pub/Sub 实现多实例部署下的跨节点 SSE 推送。

**面试话术：** "高可用体现在多个层面：LLM 层面通过多供应商冗余和首包探测实现自动故障切换；消息层面通过事务性发件箱保证至少一次投递；Redis 依赖的组件大多采用 Fail-Open 策略，Redis 故障不阻塞主流程；SSE 推送通过 Redis Pub/Sub 支持多实例部署。"

---

### 4.4 高并发设计 ★★★

#### 4.4.1 SessionConcurrencyGuard

Redis 分布式锁防止同一会话的并发请求重叠：
- Lua 脚本原子 compare-and-delete
- try-with-resources 自动释放
- Fail-Open：Redis 故障不阻塞

#### 4.4.2 三态分布式锁 (MQ)

- Task Lock：幂等消费，防止同一任务重复执行
- Session Exec Lock：会话互斥，防止同一会话的 Agent 并发执行
- LockWatchdog：每 20s 续期，防止长时间任务锁过期

#### 4.4.3 MCP 令牌桶限流

每服务器独立的令牌桶限流器，防止突发流量冲垮外部工具。

#### 4.4.4 线程池隔离

| 线程池 | 用途 | 规格 |
|--------|------|------|
| taskExecutor | 事件处理 | 4/10/100 |
| summaryExecutor | 摘要生成 | 1/2/8 (DiscardOldest) |
| modelStreamExecutor | LLM 流式 | 20/100/200 |

**面试话术：** "高并发方面，我通过 SessionConcurrencyGuard 实现了基于 Redis 的会话级并发控制，使用 Lua 脚本保证原子释放。MQ 层面设计了双锁模型（任务锁+会话执行锁），配合 LockWatchdog 自动续期支持长时间任务。MCP 工具调用有令牌桶限流。线程池做了隔离，不同类型的工作使用不同线程池，互不影响。"

---

### 4.5 高性能设计 ★★

#### 4.5.1 ProviderDirectStreamSupport

绕过 Spring AI 的抽象层，直接用 WebClient 接收供应商原始 SSE 文本，手动解析。避免了 Spring AI 的 chunk 合并和类型转换开销。

#### 4.5.2 混合检索 + RRF 融合

Milvus 同时支持稠密向量搜索和 BM25 稀疏搜索，RRF (Reciprocal Rank Fusion) 融合两种检索结果：

```
score = 1.0 / (rrfK + rank + 1)    // rrfK = 60
双来源命中累加分数
```

#### 4.5.3 知识库信号热路径

重排序前的文档信号加载使用 Redis MGET，延迟预算 < 20ms，严格禁止 MySQL JOIN。

#### 4.5.4 上下文增强缓存

- VLM 解析结果：SHA-256 内容摘要缓存
- PDF 页面渲染：Caffeine 缓存
- MCP 工具注册表：Caffeine 30s TTL

**面试话术：** "性能优化方面，流式响应我绕过 Spring AI 直接解析原始 SSE，减少抽象层开销。检索使用 Milvus 混合搜索（稠密+BM25），RRF 融合两种结果取长补短。热路径的文档信号加载用 Redis MGET 控制在 20ms 以内。多处使用 Caffeine 缓存减少重复计算。"

---

### 4.6 ReAct Agent 循环 ★★

```
Agent.run():
    for step in 1..20:
        think() → ChatResponse
            │ 无工具 → FINISHED (直接回答)
            │ 有工具 → execute() → 结果写回记忆
            └── → 继续循环
```

**单次路由流设计：** 有工具时，模型输出同时流式展示+缓冲。如果模型最终产出工具调用，回滚已展示的内容；如果产出纯文本，直接成为最终答案，不需要第二次模型调用。

**三层记忆：**
- L1：最近 N 轮完整对话（Token 预算 + 80% 安全系数）
- L2：增量摘要（事件驱动，LLM + 确定性回退）
- L3：用户画像（跨会话持久化）

---

### 4.7 意图路由 — 启发式 + LLM 双阶段 ★★

```
启发式评分 (快速):
    - 节点名称精确/子串匹配 (权重 1.2)
    - bigram Jaccard 相似度 (权重 0.7)
    - 高置信度直接通过 (score ≥ 1.2, gap > 0.5)

LLM 回退 (精确):
    - 启发式不确定时才调用
    - 返回: 节点ID / NONE / AMBIGUOUS
```

**澄清系统：** 模糊匹配触发澄清，Redis 5分钟 TTL 暂存状态，支持中文序号/阿拉伯数字/名称匹配。

---

### 4.8 RAG 流水线 ★★

**摄取流程：** 文件上传 → 30MB 大小防护 → 类型检测 → 解析 → 分块 → 上下文增强 → Embedding → Milvus upsert

**PDF 解析双轨道：**
- Fast-Track：文本密度高，PDFBox 原生提取
- Visual-Track：文本密度低，渲染为图片 → VLM/MinerU 解析

**重排序降级链：** BGE HTTP → LLM Reranker → Noop (RRF 顺序)

**置信度过滤：** Top-1 分数 < 0.15 时过滤所有结果，从 Prompt 中排除但保留在 UI 引用中。

---

## 五、面试常见问题参考

### Q1: 请介绍一下你的项目

"ChatAgent 是一个企业级 AI 智能工作台，支持多模型路由、RAG 知识检索、意图路由、MCP 工具集成和异步任务处理。后端基于 Spring Boot 3.5 + Spring AI 1.1，使用 PostgreSQL + Milvus + Redis + RabbitMQ。我负责了整个后端的架构设计和核心实现。"

### Q2: 首包探测是怎么实现的

[见 4.1 节]

### Q3: 熔断机制怎么设计的

[见 4.2 节]

### Q4: 如何保证消息不丢失

"使用事务性发件箱模式。业务操作和消息写入同一个数据库事务，由独立的轮询发布器异步发送到 RabbitMQ。消费端使用三态分布式锁（RUNNING/COMPLETED/FAILED）保证幂等。不可恢复的消息进入死信队列，管理员可以手动重放。"

### Q5: RAG 是怎么做的

"摄取阶段支持多种文档格式，PDF 使用质量路由双轨道（原生提取 vs VLM 视觉解析），分块后使用 Anthropic 风格的上下文增强。检索阶段使用 Milvus 混合搜索（稠密向量 + BM25），RRF 融合后经过 BGE 重排序（带熔断器保护）。重排序有完整的降级链：BGE → LLM → Noop。"

### Q6: 意图路由是怎么做的

"三级树状结构（DOMAIN→CATEGORY→TOPIC），采用启发式+LLM 双阶段分类。启发式使用 bigram Jaccard 相似度，高置信度直接通过不调用 LLM。模糊匹配触发澄清系统，使用 Redis 暂存状态。路由后进行查询改写，利用意图路径上下文扩展代词和补充省略信息。"

### Q7: 如何处理高并发

"会话级 Redis 分布式锁防止同一用户并发请求重叠。MQ 消费端使用双锁模型（任务锁+会话执行锁），LockWatchdog 自动续期支持长时间任务。MCP 工具调用有令牌桶限流。线程池做了隔离，不同类型工作使用不同线程池。"

### Q8: MCP 工具集成是怎么做的

"实现了完整的 Model Context Protocol 客户端，支持 HTTP 和 Legacy SSE 双协议。管理端有 SSRF 防护、凭据 AES-256-GCM 加密、Schema 漂移定时检测。运行时每服务器独立熔断器+令牌桶限流，工具响应截断到 64KB。通过 McpToolCallbackAdapter 桥接到 Spring AI 的 ToolCallback 接口，与内置工具统一治理。"

---

## 六、项目规模

| 指标 | 数量 |
|------|------|
| Java 源文件 | 549 |
| 测试文件 | 108 |
| 测试用例 | 351+ |
| MyBatis Mapper XML | 22 |
| Flyway 数据库迁移 | 15 |
| 业务模块 | 10+ |
| REST API 端点 | 50+ |
