# 企业 AI 工作台改造 架构实现文档

> 对应计划：`docs/plans/ENTERPRISE_REFACTOR_PLAN.md`
> 最后更新：2026-04-11
> 子文档：`USER_MANAGEMENT_SPEC.md`（用户管理 V1 详细实现）

## 1. 概述

### 1.1 目标与范围

将 ChatAgent 从"单会话文件检索"改造为"企业内部 AI 工作台"：知识库独立化、分层意图识别、会话记忆管理、引用溯源展示。分为 Phase 0-4 逐步落地。

**产品边界：**
- 普通用户：只保留 `/chat` 单入口，不创建/选择 Agent
- 管理员：在 `/admin/*` 下管理知识库、意图树、入库任务与内部助手配置
- Agent：仅作为后端内部运行配置

### 1.2 核心设计决策

1. **知识库独立于 Session**：`KnowledgeBase` 脱离 `ChatSession` 独立存在，支持跨 session 共享。
2. **分层树形意图路由**：DOMAIN → CATEGORY → TOPIC 三层逐层缩小候选集，避免"拍平所有叶子"。
3. **编排与执行分离**：`ConversationOrchestrator` 负责意图识别/澄清/分流，`ChatAgent` 负责 ReAct 执行。
4. **三层记忆金字塔**：L1 短期（Token 预算制）+ L2 增量摘要 + L3 用户画像。
5. **双 Milvus Collection**：session-file 和 knowledge-base 各自独立 collection，通过 `SearchScopeResolver` 统一融合。
6. **IntentResolution 唯一契约**：编排层交付给执行层的唯一数据契约，解耦编排与执行。
7. **版本化意图树快照**：DRAFT 编辑 → 发布 → 版本切换回滚，线上路由原子性切换。

## 2. 整体架构

### 2.1 改造后整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                          前端 (React)                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │ 对话界面  │  │助手设置  │  │知识库管理│  │意图树管理│        │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘        │
└───────┼──────────────┼─────────────┼─────────────┼──────────────┘
        │              │             │             │
┌───────┼──────────────┼─────────────┼─────────────┼──────────────┐
│       ▼              ▼             ▼             ▼    后端       │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │          1. 编排层 (ConversationOrchestrator)             │    │
│  │  Turn Preparation:                                       │    │
│  │  ├── PendingIntent 检查 / ClarificationResolver          │    │
│  │  ├── IntentRouter (启发式 + LLM 回退)                    │    │
│  │  ├── QueryRewriter (意图感知重写)                         │    │
│  │  └── 分流决策 → IntentResolution                         │    │
│  └──────────────────────┬──────────────────────────────────┘    │
│                         │ IntentResolution                       │
│  ┌──────────────────────┴──────────────────────────────────┐    │
│  │          2. 执行层 (ChatAgent ReAct Loop)                 │    │
│  │  ChatAgentFactory → ChatAgent → AgentThinkingEngine      │    │
│  │  按 IntentResolution 注入工具集合和检索范围               │    │
│  └──────────────────────┬──────────────────────────────────┘    │
│                         │                                        │
│         ┌───────────────┼───────────────┐                       │
│         ▼               ▼               ▼                       │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐                  │
│  │RAG 检索层│    │工具执行层│    │ 会话服务 │                   │
│  │KB + 临时 │    │MCP/邮件/ │    │记忆管理  │                   │
│  │文件联合  │    │终止      │    │摘要压缩  │                   │
│  └────┬─────┘    └──────────┘    └──────────┘                  │
│       │                                                          │
│  ┌────┴──────────────────────────────────────────────────┐      │
│  │                基础设施层                                │      │
│  │  PostgreSQL │ Milvus(双Collection) │ Redis │ Ollama     │     │
│  └────────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 完整对话链路

```
用户发送消息
     │
     ▼
ConversationOrchestrator.handleUserTurn()
     │
     ├── 生成 turn_id (贯穿整个回合)
     │
     ├── 检查 PendingIntentResolutionStore (Redis)
     │   └── 若有待澄清 → ClarificationResolver → 返回追问或命中意图
     │
     ├── IntentRouter (启发式匹配 → LLM 分类回退)
     │   ├── 闲聊 (kind=NONE) → 直接 Agent 执行
     │   ├── 知识库 (kind=KB) → QueryRewrite → 检索 → Agent
     │   ├── 工具 (kind=TOOL) → Agent (注入工具集)
     │   ├── 系统 (kind=SYSTEM) → SystemIntentResponseRenderer 直答
     │   └── 澄清 (kind=CLARIFY) → ClarificationResponseBuilder 追问
     │
     ├── ChatAgentFactory.createAgent(intentResolution)
     │   └── 按 kind 注入不同工具集和检索范围
     │
     └── ChatAgent.run() (ReAct Loop)
          ├── think() → tool call → execute → loop
          └── 返回 AgentRunResult
```

## 3. 文件清单

### 3.1 数据库迁移

| 文件路径 | Phase | 说明 |
|---|---|---|
| `V1__baseline_schema.sql` | 基线 | 核心表创建 |
| `V2__phase0_internal_assistant.sql` | Phase 0 | 内部助手基线 |
| `V3__phase1b_knowledge_base.sql` | Phase 1B | knowledge_base / document / chunk / agent_knowledge_base |
| `V4__phase2_intent_routing.sql` | Phase 2 | intent_node / intent_knowledge_base |
| `V5__phase3_memory_foundation.sql` | Phase 3 | chat_message 加 seq_no + turn_id；chat_session_summary |
| `V6__phase4_assistant_templates.sql` | Phase 4 | agent_template |
| `V7-V9` | Phase 5 | MQ Outbox |
| `V10` | Phase 6 | Knowledge Document Enhancement |
| `V11` | 用户管理 | user.status 字段 |
| `V12` | Dashboard | t_chat_turn_metric |
| `V13-V15` | Phase 7 | MCP management |

### 3.2 知识库系统（Phase 1）

| 文件路径 | 职责 |
|---|---|
| **数据层** | |
| `support/persistence/entity/KnowledgeBase.java` | KnowledgeBase 实体 |
| `support/persistence/entity/KnowledgeDocument.java` | KnowledgeDocument 实体 |
| `support/persistence/entity/KnowledgeDocumentEnhancement.java` | 文档增强实体 |
| `support/persistence/entity/KnowledgeChunk.java` | KnowledgeChunk 实体 |
| `support/persistence/entity/AgentKnowledgeBase.java` | Agent-KB 多对多关联实体 |
| `support/dto/KnowledgeBaseDTO.java` | KnowledgeBase DTO |
| `support/dto/KnowledgeDocumentDTO.java` | KnowledgeDocument DTO |
| `support/dto/KnowledgeDocumentEnhancementDTO.java` | 文档增强 DTO |
| `support/dto/KnowledgeChunkDTO.java` | KnowledgeChunk DTO |
| **Repository** | |
| `knowledge/port/KnowledgeBaseRepository.java` | 知识库 Repository 接口 |
| `knowledge/port/KnowledgeDocumentRepository.java` | 文档 Repository 接口 |
| `knowledge/port/KnowledgeDocumentEnhancementRepository.java` | 文档增强 Repository |
| `knowledge/port/KnowledgeChunkRepository.java` | Chunk Repository 接口 |
| `admin/port/AgentKnowledgeBaseRepository.java` | Agent-KB 关联 Repository |
| `support/persistence/adapter/knowledge/MyBatis*.java` | MyBatis 适配器 |
| `support/persistence/mapper/Knowledge*Mapper.java` | MyBatis Mapper |
| `resources/mapper/Knowledge*Mapper.xml` | MyBatis XML |
| **Service** | |
| `knowledge/application/KnowledgeBaseFacadeService.java` | 知识库 Facade 接口 |
| `knowledge/application/KnowledgeBaseFacadeServiceImpl.java` | 知识库 Facade 实现 |
| `knowledge/application/KnowledgeDocumentFacadeService.java` | 文档 Facade 接口 |
| `knowledge/application/KnowledgeDocumentFacadeServiceImpl.java` | 文档 Facade 实现 |
| `knowledge/application/KnowledgeDocumentStatusSseService.java` | 文档入库状态 SSE 推送 |
| `knowledge/application/AssistantKnowledgeBaseFacadeService.java` | 助手-KB 绑定 Facade |
| **Controller** | |
| `knowledge/controller/KnowledgeBaseController.java` | 知识库 REST 接口 |
| `knowledge/controller/KnowledgeDocumentController.java` | 文档 REST 接口 |
| `knowledge/controller/KnowledgeDocumentStatusSseController.java` | 入库状态 SSE 接口 |
| `admin/controller/AssistantKnowledgeBaseController.java` | 助手-KB 绑定接口 |

### 3.3 RAG 检索系统（Phase 1）

| 文件路径 | 职责 |
|---|---|
| `rag/model/RetrievalHit.java` | 统一检索结果结构 |
| `rag/model/CitationMetadata.java` | 引用元数据结构 |
| `rag/service/RetrievalHitFormatter.java` | 检索结果格式化（含引用编号） |
| `rag/service/FormattedRetrievalPrompt.java` | 格式化后的检索 Prompt |
| `rag/SearchScopeResolver.java` | 双 collection 检索范围解析 |
| `rag/retrieve/KnowledgeBaseSimilaritySearcher.java` | 知识库向量检索 |
| `rag/retrieve/SessionFileSimilaritySearcher.java` | 临时文件向量检索 |
| `rag/vector/milvus/KnowledgeBaseMilvusIndexer.java` | 知识库 Milvus 索引器 |
| `rag/vector/milvus/KnowledgeBaseMilvusChunkMapper.java` | 知识库 Chunk-Milvus 映射 |
| `rag/ingestion/KnowledgeDocumentIngestionService.java` | 知识文档入库接口 |
| `rag/ingestion/KnowledgeDocumentIngestionServiceImpl.java` | 知识文档入库实现 |
| `rag/ingestion/KnowledgeBaseIndexedChunkAssembler.java` | 知识库索引组装器 |

### 3.4 意图系统（Phase 2）

| 文件路径 | 职责 |
|---|---|
| **数据层** | |
| `support/persistence/entity/IntentNode.java` | 意图节点实体 |
| `support/persistence/entity/IntentKnowledgeBase.java` | 意图-KB 关联实体 |
| `support/dto/IntentNodeDTO.java` | IntentNode DTO |
| `intent/model/IntentNodeLevel.java` | 节点层级枚举 (DOMAIN/CATEGORY/TOPIC) |
| `intent/model/IntentNodeStatus.java` | 节点状态枚举 |
| `intent/port/IntentNodeRepository.java` | 意图节点 Repository |
| `intent/port/IntentKnowledgeBaseRepository.java` | 意图-KB Repository |
| **核心逻辑** | |
| `intent/application/IntentRouter.java` | 分层意图路由器（启发式 + LLM 回退） |
| `intent/application/IntentResolution.java` | 编排层→执行层唯一契约 |
| `intent/application/IntentRoutingResult.java` | 路由结果 |
| `intent/application/IntentTreeSnapshot.java` | 意图树快照 |
| `intent/application/ConversationTurnPreparationService.java` | Turn Preparation 服务 |
| `intent/application/TurnPreparationResult.java` | Turn Preparation 结果 |
| `intent/application/QueryRewriter.java` | 意图感知问题重写 |
| **澄清系统** | |
| `intent/application/PendingIntentResolutionStore.java` | 待澄清状态接口 |
| `intent/application/RedisPendingIntentResolutionStore.java` | Redis 实现 |
| `intent/application/PendingIntentResolution.java` | 待澄清状态模型 |
| `intent/application/ClarificationResolver.java` | 澄清续接解析器 |
| `intent/application/ClarificationResponseBuilder.java` | 追问响应构建器 |
| `intent/application/SystemIntentResponseRenderer.java` | SYSTEM 意图直答渲染 |
| **缓存与版本** | |
| `intent/application/IntentTreeCacheManager.java` | 意图树缓存接口 |
| `intent/application/DefaultIntentTreeCacheManager.java` | Redis 缓存实现 |
| `intent/application/IntentTreeFacadeService.java` | 意图树管理 Facade |
| `intent/application/IntentTreeFacadeServiceImpl.java` | 意图树管理实现 |
| **Controller** | |
| `admin/controller/IntentTreeController.java` | 意图树 REST 接口 |

### 3.5 会话记忆系统（Phase 3）

| 文件路径 | 职责 |
|---|---|
| **数据层** | |
| `support/persistence/entity/ChatSessionSummary.java` | 会话摘要实体 |
| `support/persistence/mapper/ChatSessionSummaryMapper.java` | 摘要 Mapper |
| `resources/mapper/ChatSessionSummaryMapper.xml` | 摘要 XML |
| **核心逻辑** | |
| `conversation/summary/IncrementalSummarizer.java` | 增量摘要引擎 |
| `conversation/summary/AsyncSummaryListener.java` | 异步摘要监听器 |
| `conversation/summary/AtomicConversationTurn.java` | 原子对话回合 |
| `conversation/summary/ConversationTurnCompletionPublisher.java` | 轮次完成事件发布 |
| `conversation/summary/RedisLockManager.java` | Redis 分布式锁 |
| `conversation/summary/SummaryWatermarkRange.java` | 水位范围模型 |
| `conversation/summary/SummaryWatermarkService.java` | 水位管理服务 |
| `conversation/summary/TurnBasedContextExtractor.java` | 基于 turn_id 的上下文提取 |

### 3.6 引用溯源系统（Phase 4）

| 文件路径 | 职责 |
|---|---|
| `rag/model/CitationMetadata.java` | 引用元数据结构 |
| `rag/service/RetrievalHitFormatter.java` | 检索结果格式化（含引用编号与 CitationMetadata 对齐） |
| `agent/runtime/CurrentTurnCitationHolder.java` | Turn 级别引用持有器（非 ThreadLocal） |
| `support/dto/ChatMessageDTO.java` | DTO 中携带 citations 字段 |

### 3.7 助手模板（Phase 4）

| 文件路径 | 职责 |
|---|---|
| `support/persistence/entity/AgentTemplate.java` | Agent 模板实体 |
| `support/dto/AssistantTemplateDTO.java` | 模板 DTO |
| `admin/port/AssistantTemplateRepository.java` | 模板 Repository |
| `admin/application/AssistantTemplateFacadeService.java` | 模板 Facade |
| `admin/application/AssistantTemplateFacadeServiceImpl.java` | 模板 Facade 实现 |
| `admin/controller/AssistantTemplateController.java` | 模板 REST 接口 |

### 3.8 编排与运行时

| 文件路径 | 职责 |
|---|---|
| `conversation/application/ConversationOrchestratorService.java` | 编排接口 |
| `conversation/application/ConversationOrchestratorServiceImpl.java` | 编排实现 |
| `agent/ChatAgentFactory.java` | Agent 工厂（按 IntentResolution 注入工具） |
| `agent/ChatAgent.java` | ReAct Agent 实现 |
| `agent/AgentRuntimeContext.java` | 运行时上下文 |
| `agent/DefaultAgentRuntimeContextLoader.java` | 默认上下文装载器 |
| `agent/AgentMessageBridgeImpl.java` | 消息桥接（含引用附着） |
| `agent/AgentThinkingEngine.java` | Agent 思考引擎 |
| `agent/runtime/AgentToolCallbackFactory.java` | 工具回调工厂 |
| `agent/runtime/AgentMemoryLoader.java` | Agent 记忆装载（Token 预算制） |
| `agent/runtime/CurrentIntentResolutionHolder.java` | 当前 IntentResolution 持有器 |
| `access/ResourceAccessGuard.java` | 资源级访问守卫接口 |
| `access/DefaultResourceAccessGuard.java` | 默认资源访问守卫实现 |

### 3.9 配置文件

| 配置项 | 说明 |
|---|---|
| `chatagent.memory.summary-model` | 摘要专用模型路由 |
| `chatagent.intent.tool-scope-mode` | Tool Scope 模式（STRICT / AGENT_DEFAULT_WITH_INTENT_NARROWING） |
| Redis Key `chatagent:intent:tree:{agentId}` | 意图树缓存（TTL 7天） |
| Redis Key `chatagent:memory:lock:{sessionId}` | 摘要分布式锁（TTL 5分钟） |

## 4. 核心功能实现

### 4.1 编排层 — ConversationOrchestrator

**实现位置：** `conversation/application/ConversationOrchestratorServiceImpl.java`

**核心流程：**

1. `handleUserTurn()` 入口生成 `turn_id`（UUID），贯穿该回合所有消息
2. 检查 `PendingIntentResolutionStore`（Redis）是否有待澄清状态
3. 调用 `ConversationTurnPreparationService` 执行意图路由
4. 根据 `IntentResolution.kind` 分流：
   - `KB`：QueryRewrite → Agent（注入 SearchTool + 知识库范围）
   - `TOOL`：Agent（注入工具集，不注入 SearchTool）
   - `SYSTEM`：`SystemIntentResponseRenderer` 直答，不实例化 Agent
   - `CLARIFY`：`ClarificationResponseBuilder` 构建追问，存入 PendingIntentResolutionStore
5. 直答路径在消息 persist 后发布 `ConversationTurnCompletedEvent`

```java
// Turn Preparation 流程
TurnPreparationResult preparation = turnPreparationService.prepare(
    agentId, sessionId, userMessage, pendingIntent
);
IntentResolution resolution = preparation.getResolution();
```

### 4.2 分层意图路由 — IntentRouter

**实现位置：** `intent/application/IntentRouter.java`

**实现逻辑：**

1. 从 `IntentTreeCacheManager` 加载 active_intent_version 对应的意图树快照
2. 分层路由：DOMAIN → CATEGORY → TOPIC
3. 先用启发式评分（关键词 + description 匹配）尝试匹配
4. 启发式不足时回退到 LLM 分类器
5. 支持 top1/top2 分差判断 → 触发澄清
6. LLM 失败时优雅降级到启发式结果

```java
// IntentResolution 契约
public record IntentResolution(
    IntentKind kind,              // KB / TOOL / SYSTEM / CLARIFY / NONE
    List<IntentNode> path,        // 命中路径
    List<String> scopedKbIds,     // 圈定的知识库范围
    ScopePolicy scopePolicy,      // STRICT / FALLBACK_ALLOWED
    List<String> allowedTools,    // 允许调用的工具白名单
    String systemPromptOverride   // SYSTEM 意图下的回复模板
)
```

### 4.3 澄清续接系统

**实现位置：** `intent/application/ClarificationResolver.java`, `RedisPendingIntentResolutionStore.java`

**实现逻辑：**

1. 当 IntentRouter 判断需要澄清时，`ClarificationResponseBuilder` 构建追问选项
2. 将 `PendingIntentResolution`（含 candidateNodeIds、originalQuery、parentPath）存入 Redis（TTL 5分钟）
3. 下一轮用户消息到来时，`ClarificationResolver` 解析用户回复，匹配候选节点
4. 命中则直接产出 IntentResolution，不再重新路由

### 4.4 意图感知问题重写 — QueryRewriter

**实现位置：** `intent/application/QueryRewriter.java`

**实现逻辑：**

- 输入：原始问题 + 对话历史 + 已命中意图路径
- 将命中路径（如：HR > 考勤 > 请假）作为事实注入 LLM
- 输出：重写后的问题，补全多轮对话上下文

### 4.5 双 Collection 检索 — SearchScopeResolver

**实现位置：** `rag/SearchScopeResolver.java`

**实现逻辑：**

```
SearchScopeResolver
├── 从 IntentResolution 读取 scopedKbIds 和 scopePolicy
├── KnowledgeBaseSimilaritySearcher → 查询 knowledge-base collection
├── SessionFileSimilaritySearcher → 查询 session-file collection
└── 统一融合两路 RetrievalHit → RRF + Rerank
```

**关键设计：**
- session-file collection：按 session_id/file_id 管理生命周期
- knowledge-base collection：按 kb_id/document_id 管理，从第一天就带 section_path
- 两路查询返回统一的 `RetrievalHit`，在 SearchScopeResolver 做融合

### 4.6 三层记忆金字塔

**实现位置：** `conversation/summary/` 目录

**L1：短期强记忆（Token 预算制）**

- `AgentMemoryLoader` 按 Token 预算（默认 4000 tokens，80% 安全水位）倒序填充最近完整回合
- 回合定义：1 个 User 消息 + 引发的所有 Tool 调用/响应 + 最终 Assistant 响应
- 使用 `turn_id` 精确圈定回合边界（`GROUP BY turn_id`）

**L2：中期增量摘要**

- `IncrementalSummarizer`：`New Summary = Summarize(Old Summary + Stable New Turns)`
- 结构化 Prompt（500 字硬上限，KV 降级提示）
- 实体锚定校验：正则提取关键实体 → 存入 `anchored_entities` → 摘要后校验留存率
- 确定性降级：LLM 失败时使用非 LLM 摘要策略

**L3：长期画像**

- 已有的 `userProfileSummary`

**异步触发机制：**

```java
// AsyncSummaryListener 核心流程
@Async("summaryExecutor")
public void onTurnCompleted(ConversationTurnCompletedEvent event) {
    // 1. 前置检查：totalTurns <= L1_WINDOW_SIZE 则跳过
    // 2. 获取 Redis 锁
    // 3. 再次校验水位线 last_seq_no >= 事件锚点（幂等保障）
    // 4. TurnBasedContextExtractor 提取新回合（按 turn_id 分组，过滤噪声）
    // 5. IncrementalSummarizer 执行增量摘要
    // 6. 实体锚定校验
}
```

**线程池配置：** `summaryExecutor`：核心 1-2，最大队列 20，`DiscardOldestPolicy`

### 4.7 引用溯源穿透链路

**实现位置：** `RetrievalHitFormatter.java` → `CurrentTurnCitationHolder.java` → `AgentMessageBridgeImpl.java`

```
RetrievalHit（结构化）
    ↓
RetrievalHitFormatter.formatWithCitations()
    ↓ 输出：编号文本 [1][2] + 对齐的 CitationMetadata 列表
    ↓
SessionFileTools.knowledgeQuery()
    ↓ citations 写入 CurrentTurnCitationHolder (sessionId + turnId 级别)
    ↓
AgentMessageBridgeImpl.persistAndPublish()
    ↓ 按 sessionId + turnId 取出 citations 附着到 metadata
    ↓
ChatMessageDTO.MetaData.citations → chat_message.metadata JSONB → SSE 推送
```

**关键约束：** 引用元数据不靠 ThreadLocal，使用 `CurrentTurnCitationHolder`（sessionId + turnId 级别），确保 citations 与产生它们的消息严格绑定。

### 4.8 资源级访问守卫 — ResourceAccessGuard

**实现位置：** `access/DefaultResourceAccessGuard.java`

**设计原则：** 资源级权限校验统一收口到一个组件，不散落在各个 Facade。

```java
@Component
public class DefaultResourceAccessGuard implements ResourceAccessGuard {
    // USER 只能访问自己创建的 session；ADMIN 可访问所有
    public void assertCanReadSession(LoginUser user, String sessionId) { ... }
    // 当前阶段只有 ADMIN 可管理知识库
    public void assertCanManageKnowledgeBase(LoginUser user, String kbId) { ... }
    // ADMIN 可管理意图树
    public void assertCanManageIntentTree(LoginUser user, String agentId) { ... }
}
```

### 4.9 Agent 工厂 — ChatAgentFactory

**实现位置：** `agent/ChatAgentFactory.java`

**实现逻辑：** 接收 `IntentResolution`，按运行时能力矩阵注入工具集合：

| 意图类型 | 实例化 Agent | 注入 SearchTool | 检索 Scope 守卫 |
|---|---|---|---|
| KB | 是 | 是（强制注入） | FALLBACK_ALLOWED |
| TOOL | 是 | 否 | 否 |
| SYSTEM | 否 | 否 | 否 |

## 5. 配置说明

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `chatagent.memory.summary-model` | — | 摘要专用轻量模型 |
| `chatagent.memory.l1-token-budget` | `4000` | L1 记忆 Token 预算 |
| `chatagent.intent.tool-scope-mode` | `AGENT_DEFAULT_WITH_INTENT_NARROWING` | Tool Scope 模式 |
| `chatagent:intent:tree:{agentId}` TTL | 7 天 | 意图树 Redis 缓存 |
| `chatagent:memory:lock:{sessionId}` TTL | 5 分钟 | 摘要分布式锁 |

## 6. 各 Phase 完成状态

| Phase | 内容 | 状态 |
|---|---|---|
| Phase 0 | 基线收敛 + 安全清理 + 单助手入口 | 已完成 |
| Phase 1A | RetrievalHit 契约 + source-aware indexer | 已完成 |
| Phase 1B | 知识库数据面 + 双 collection 检索 | 已完成 |
| Phase 1C | 后台管理面收口 + 单助手联调 + RBAC | 已完成 |
| Phase 2A | 编排契约 + 运行时意图边界 | 已完成 |
| Phase 2B | 意图树数据面 + 版本化快照发布 | 已完成 |
| Phase 2C | 意图树管理面 + 联调 | 已完成 |
| Phase 3A | 记忆基建（seq_no + turn_id + Redis 锁） | 已完成 |
| Phase 3B | 增量摘要引擎 + 异步调度 | 已完成 |
| Phase 3C | Agent Memory Loader Token 预算制 | 已完成 |
| Phase 4 | 助手模板 + 引用溯源 | 已完成 |

## 7. 已知限制与后续规划

- **不做部门体系**：ADMIN/USER 两个角色足够当前场景
- **不做工作流编排**：保持 ReAct Agent 模式
- **不做多租户隔离**：单企业场景
- **不做 SSO/LDAP**：先用自有 JWT
- **不做问题拆分**：当前只做问题重写
- **用户名不复用**：软删除后用户名仍占唯一约束
- **批量操作未实现**：用户管理暂不支持批量导入/删除
