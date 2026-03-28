# ChatAgent 企业 AI 工作台改造方案 v4

## 一、产品定位

**企业内部 AI 工作台**：为企业提供统一的 AI 助手入口，后台可按业务需要预配置内部 Agent、知识库和工具，员工默认只通过一个聊天入口使用系统能力。

### 典型场景

| 角色 | Agent | 知识库 | 工具 |
|------|-------|--------|------|
| 新员工 | HR助手 | 员工手册、薪酬制度、入职指南 | 邮件 |
| 运维 | IT运维Agent | 运维手册、故障排查、网络拓扑 | 数据库查询、邮件 |
| 客服 | 客服Agent | 产品FAQ、退换货政策、话术模板 | 邮件 |
| 管理层 | 数据分析Agent | 业务报表说明、KPI定义 | 数据库查询 |

> **当前重建阶段的产品边界**：
> - 普通用户：只保留 `/chat` 单入口，对话、查看历史、上传临时文件，不创建/选择 Agent
> - 管理员：在 `/admin/*` 下管理知识库、意图树、入库任务、追踪与内部助手配置
> - `Agent`：当前只作为后端内部运行配置，不作为普通用户产品概念暴露

---

## 二、技术选型确认

### 2.1 现有技术栈（保持不变）

| 层面 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Java 17 + Spring Boot 3.5 + MyBatis | 已有 |
| 关系数据库 | PostgreSQL | 已有，支持 JSONB |
| 向量数据库 | Milvus 2.6 | 已有，混合检索 |
| Embedding | Ollama + bge-m3 (1024维) | 已有 |
| Reranker | bge-reranker-v2-m3 | 已有 |
| 缓存 | Redis | 已有 |
| 前端 | React 18 + Vite + TypeScript | 已有 |
| 认证 | JWT (jjwt) | 已有 |
| LLM | DeepSeek / GLM-4.6 / Ollama (qwen2.5) | 已有 |

### 2.2 新增技术选型

| 能力 | 技术方案 | 选型理由 |
|------|----------|----------|
| 意图识别 | LLM-based 分层树形意图路由 (DeepSeek) | 树的价值在逐层缩小候选集，不是把所有叶子一次性拍平 |
| 问题重写 | LLM-based (DeepSeek) | 多轮对话上下文补全 |
| 会话记忆压缩 | LLM-based (Ollama qwen2.5) | 本地模型做摘要，省 API 费用 |
| 意图树缓存 | Redis String + TTL | 树不常变，缓存避免每次查 DB |
| 摘要压缩锁 | Redis SETNX 分布式锁 | 防止并发摘要同一 session |
| 权限框架 | 现有 JWT 拦截 + 自定义 `@RequireRole` | 现有代码已有 JWT 与 role claim，先补 RBAC，不额外引入安全框架迁移成本 |
| 检索结果契约 | `RetrievalHit` 结构化返回 | 为知识库检索、临时文件检索、引用溯源统一数据契约 |

### 2.3 Redis 使用规划

| 用途 | Key 格式 | Value | TTL |
|------|----------|-------|-----|
| 意图树缓存 | `chatagent:intent:tree:{agentId}` | JSON 序列化的意图树 | 7 天 |
| 摘要压缩锁 | `chatagent:memory:lock:{sessionId}` | 锁标识 | 5 分钟 |
| 已有的 Redis 用途 | Spring Data Redis 默认 | session 等 | - |

---

## 三、现状问题分析

### 3.1 知识库与会话强绑定（最核心）

```
现在：User → ChatSession → ChatSessionFile → FileChunk → Milvus
问题：session 删了知识就没了，无法跨 session 共享
```

### 3.2 无意图识别（全靠 LLM 猜）

用户说什么都直接进 Agent think() 循环，LLM 自己判断要不要调工具。可能检索错、可能瞎编。

### 3.3 无问题重写（多轮检索质量差）

"额度是多少？"这 4 个字直接拿去检索，缺上下文。

### 3.4 无会话记忆管理（Token 会爆）

取最近 N 条消息全量塞给 LLM，对话多了 Token 飙升。

### 3.5 无角色权限、产品边界未收敛

虽然当前代码里已经有 `role` 字段和 JWT `role claim`，但还没有真正的 RBAC 拦截；同时还残留“用户自有 Agent / 用户管理 Agent”的旧模型，与当前企业知识库场景下“普通用户只聊天、管理员配置内部助手”的产品边界不一致。

---

## 四、分阶段改造方案

---

### Phase 0：基线收敛 + 安全清理 + 单助手入口

> **目标**：统一当前架构基线与文档描述；清理敏感日志；收敛用户侧为单固定助手入口；为后续数据库迁移和后台管理接口打基础。
> **预估**：后端 2 天，前端 1 天

- 统一文档基线：以当前三模块后端结构、Milvus 检索、环境变量配置为准，移除过期描述
- 清理认证链路中的敏感日志：密码、access token、refresh token 不再打印
- 建立数据库迁移基线（Flyway / Liquibase 或版本化 SQL 目录）
- 统一 `t_user.role` 枚举迁移策略，兼容历史 `user/admin` 数据
- 收敛用户侧产品边界：移除用户创建/选择 Agent 的前端依赖，聊天页固定 `/chat`
- 建立 `/chat` 与 `/admin/*` 路由骨架，后台能力统一收口到 `/api/admin/*`
- 保留 `agent` 作为内部运行配置，但不再把 `/api/agents` 作为普通用户 CRUD 目标接口

### Phase 1：知识库独立化 + 基础 RBAC + 单助手知识绑定（拆分为 1A / 1B / 1C）

> **目标**：知识库脱离 session 独立存在；角色权限真正生效；系统内部助手可绑定知识库；检索结果统一为结构化契约。
> **预估**：后端 7 天，前端 3 天

#### 4.1.0 执行拆分

> Phase 1 不再作为一个大包执行，而是按 `Phase 1A -> Phase 1B -> Phase 1C` 顺序推进。后续小节默认按这个拆分理解。

| 子阶段 | 目标 | 主要产出 |
|------|------|----------|
| **Phase 1A** | 先把检索契约与 source-aware indexer 抽出来，保持现有 session-file 检索可用 | `RetrievalHit`、`RagSourceType`、`IndexedChunkDocument`、`SessionFileIndexedChunkAssembler`、`SessionScopedMilvusChunkMapper` |
| **Phase 1B** | 落地知识库数据面、后台 API、独立 knowledge-base collection、联合检索 | `knowledge_base / knowledge_document / knowledge_chunk / agent_knowledge_base`、`KnowledgeBaseIndexedChunkAssembler`、`KnowledgeBaseMilvusChunkMapper`、`KnowledgeBaseSimilaritySearcher`、`SearchScopeResolver` |
| **Phase 1C** | 收口后台管理入口与单助手知识绑定联调，完成用户侧产品边界切换，抽统一资源级访问守卫 | `/api/admin/knowledge-bases`、`/api/admin/assistant/knowledge-bases`、`ResourceAccessGuard`、后台知识库管理页、单助手聊天主链路联调 |

#### 4.1.1 数据模型

```sql
-- 知识库
CREATE TABLE knowledge_base (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by  UUID NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    visibility  VARCHAR(20) DEFAULT 'SHARED',
    status      VARCHAR(20) DEFAULT 'ACTIVE',
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- 知识库文档
CREATE TABLE knowledge_document (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    filename          VARCHAR(500) NOT NULL,
    original_filename VARCHAR(500),
    mime_type         VARCHAR(200),
    size_bytes        BIGINT,
    storage_path      VARCHAR(1000),
    parse_status      VARCHAR(20) DEFAULT 'PENDING',
    metadata          JSONB DEFAULT '{}',
    deleted           BOOLEAN DEFAULT FALSE,
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

-- 知识库切片
CREATE TABLE knowledge_chunk (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_document_id UUID NOT NULL REFERENCES knowledge_document(id),
    chunk_index           INT NOT NULL,
    content               TEXT NOT NULL,
    token_count           INT,
    metadata              JSONB DEFAULT '{}',
    enabled               BOOLEAN DEFAULT TRUE,
    created_at            TIMESTAMP DEFAULT NOW(),
    updated_at            TIMESTAMP DEFAULT NOW()
);

-- Agent 绑定知识库（多对多）
CREATE TABLE agent_knowledge_base (
    agent_id          UUID NOT NULL REFERENCES agent(id),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    created_at        TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (agent_id, knowledge_base_id)
);
```

**说明**：

- 当前代码已经有 `t_user.role`、JWT `role claim` 和请求上下文 `role`，本阶段不再重复做“给 user 加 role 字段”。
- 本阶段只统一角色枚举（`ADMIN` / `USER`），并补齐接口级 RBAC 拦截。
- 除接口级 RBAC 外，需要补齐 `Session / KnowledgeBase` 这类资源级访问策略，不能只停留在注解层。资源级校验统一收口到 `ResourceAccessGuard`，不散落在各个 Facade 中（详见 4.1.3.1）。
- 当前阶段不再扩展 `owner/member/visibility` 这套用户侧 Agent 模型，`agent` 仅作为后台内部运行配置。
- 知识库和文档优先做归档，不默认物理删除，避免历史引用失效。

#### 4.1.2 数据关系

```
KnowledgeBase（独立实体，管理员维护）
├── KnowledgeDocument → KnowledgeChunk → Milvus 向量
│
InternalAssistant（后台内部运行配置，普通用户不可见）
├── agent_knowledge_base（当前按单助手实现，保留多对多扩展位）
│
└── ChatSession → ChatMessage
    └── ChatSessionFile（临时文件，保留现有功能）
```

#### 4.1.3 后端改动清单

| 任务 | 新增/改动 | 涉及目录 |
|------|-----------|----------|
| KnowledgeBase/Document/Chunk DTO | 新增 | support/dto |
| 3 个 Knowledge Repository + 1 个 AgentKnowledgeBaseRepository | 新增 | knowledge/port, admin/port, persistence |
| KnowledgeBaseController | 新增 | knowledge/controller |
| KnowledgeDocumentController | 新增 | knowledge/controller |
| KnowledgeIngestionService（复用现有 ingestion 逻辑） | 新增 | rag/ingestion |
| KnowledgeBaseMilvusIndexer（独立 knowledge-base collection，复用 source-aware 契约） | 新增 | rag/vector |
| RagService / SimilaritySearcher 返回 `RetrievalHit` | 改动 | rag/service, rag/retrieve |
| SearchScopeResolver：分别查询 knowledge-base collection / session-file collection 后统一融合 | 新增 | rag |
| SessionFileSearchTool：支持知识库 + 临时文件联合检索 | 改动 | agent/tools |
| AgentSessionFileSummaryResolver：补充知识库摘要入口 | 改动 | agent/runtime |
| 后台内部助手绑定知识库 API | 新增 | admin/controller |
| 角色枚举统一 + `@RequireRole` + 鉴权拦截 | 改动 | user, framework |
| 后台管理接口统一收口到 `/api/admin/*` | 改动 | admin, user |
| ChatSession 创建入口收敛为单助手模式（前端不再传 `agentId`） | 改动 | conversation, ui |
| `ResourceAccessGuard`：统一资源级访问守卫 | 新增 | framework/security |

#### 4.1.3.1 ResourceAccessGuard（资源级访问守卫）

> 资源级权限校验不散落在各个 Facade 中，统一收口到一个组件。所有 Facade / Controller 通过调用 guard 方法完成资源归属校验，审计和测试也只看这一个类。

```java
@Component
public class ResourceAccessGuard {

    /**
     * 校验当前用户是否有权访问该 session。
     * USER 只能访问自己创建的 session；ADMIN 可访问所有。
     */
    public void assertCanReadSession(LoginUser user, String sessionId) { ... }

    /**
     * 校验当前用户是否有权管理该知识库。
     * 当前阶段只有 ADMIN 可管理。
     */
    public void assertCanManageKnowledgeBase(LoginUser user, String kbId) { ... }

    /**
     * 校验当前用户是否有权管理意图树（Phase 2 加入）。
     */
    public void assertCanManageIntentTree(LoginUser user, String agentId) { ... }
}
```

**落地时机**：Phase 1C。原因是 Phase 1C 同时做 `@RequireRole` + session/知识库权限联调，是唯一一个"所有资源级校验需求同时在手"的时间窗口。如果拖到 Phase 2，中间 Phase 1B 的知识库 API 就缺乏资源级保护。

#### 4.1.4 API 接口

```
知识库管理（ADMIN）：
POST   /api/admin/knowledge-bases                            创建知识库
GET    /api/admin/knowledge-bases                            列出知识库
GET    /api/admin/knowledge-bases/{kbId}                     知识库详情
PATCH  /api/admin/knowledge-bases/{kbId}                     更新知识库
POST   /api/admin/knowledge-bases/{kbId}/archive             归档知识库
POST   /api/admin/knowledge-bases/{kbId}/restore             恢复知识库

文档管理（ADMIN）：
POST   /api/admin/knowledge-bases/{kbId}/documents/upload          上传文档
GET    /api/admin/knowledge-bases/{kbId}/documents                 列出文档
POST   /api/admin/knowledge-bases/{kbId}/documents/{docId}/replace 替换文档
POST   /api/admin/knowledge-bases/{kbId}/documents/{docId}/archive 归档文档

内部助手绑定（ADMIN）：
PUT    /api/admin/assistant/knowledge-bases                  设置系统内部助手绑定知识库
GET    /api/admin/assistant/knowledge-bases                  查看系统内部助手绑定

用户对话入口（USER/ADMIN）：
POST   /api/chat-sessions                                    创建会话（前端不再选择 Agent，由后端绑定系统默认助手）
```

#### 4.1.5 检索结果契约（提前落地）

> 这部分虽然最终用于 Phase 4 的引用展示，但数据契约必须在 Phase 1 就统一，不然后面只能返回“像引用的字符串”。

```java
public record RetrievalHit(
    String sourceType,     // KNOWLEDGE_BASE / SESSION_FILE
    String sourceId,
    String documentId,
    String documentName,
    Integer chunkIndex,
    String sectionPath,
    String content,
    String contextText,
    Double score
) {}
```

#### 4.1.6 Milvus Collection Strategy（Phase 1B 设计确认）

> Phase 1A 已经把上游索引输入统一为 `IndexedChunkDocument + RagSourceType`；Phase 1B 不扩展现有 session-file collection，而是为知识库新建独立 collection。

**为什么不用一个混合 collection：**
- `ChatSessionFile` 是临时生命周期，session 删除时可以直接按 `session_id / session_file_id` 清理；知识库文档是持久资产，只有管理员归档/替换时才应删除，生命周期完全不同。
- Milvus 2.x 对已有 collection 的 schema 变更成本高。给现有 session-file collection 追加 `source_type / knowledge_base_id / document_id` 等字段，通常意味着重建 collection 和全量重建索引。
- 两类查询的 scope 和调优目标不同：session-file 检索面向单会话的小候选集；knowledge-base 检索面向一个或多个知识库的大候选集，`topK / candidateK / filter` 策略都不同。
- 上层合并成本很低。两路检索最终都返回 `RetrievalHit`，在 `SearchScopeResolver` 这一层做 RRF + rerank 即可。

**确认后的 Phase 1B 结构：**
```text
IndexedChunkDocument（通用，已在 Phase 1A 落地）
    |
    |-- SessionFileIndexedChunkAssembler（已有）
    |   `-- SessionScopedMilvusChunkMapper -> session_file collection（已有）
    |
    `-- KnowledgeBaseIndexedChunkAssembler（新增）
        `-- KnowledgeBaseMilvusChunkMapper -> knowledge_base collection（新增）
```

**知识库 collection 字段从一开始就带上来源信息：**
```text
chunk_id, kb_id, document_id, chunk_index, document_name, section_path,
content, context_text, retrieval_text, bm25_text, bm25_sparse, enabled,
created_at, embedding
```

**查询侧分层：**
- `SessionFileSimilaritySearcher`：只查 session-file collection。
- `KnowledgeBaseSimilaritySearcher`：只查 knowledge-base collection。
- `SearchScopeResolver`：按 scope 分别查询两路，合并 `RetrievalHit`，再做统一 RRF + rerank。

这样可以保留现有 `deleteBySessionId / deleteBySessionFileId` 的简单语义，同时让知识库 collection 从第一天就带 `section_path`，为后续 Phase 4 的引用溯源直接提供数据。

---

### Phase 2：分层树形意图识别 + 问题重写（架构强化版）

> **目标**：构建生产级的意图路由系统。通过“编排与执行分离”确保架构灵活性；引入“待澄清状态”解决追问闭环；通过“发布模型”控制线上风险。
> **预估**：后端 8 天（增加 2 天状态管理与发布模型实现），前端 2 天

#### 4.2.0 执行拆分

| 子阶段 | 目标 | 主要产出 | 预估 |
|------|------|------|------|
| **Phase 2A** | 先把运行时链路接起来 | `IntentResolution`、`PendingIntentResolutionStore`、`IntentRouter`、`QueryRewriter`、`ConversationOrchestrator` Turn Preparation、`SearchScopeResolver` 守卫、`ChatAgentFactory` 按矩阵注入 | 后端 4 天 |
| **Phase 2B** | 把意图树变成可发布、可回滚的数据面 | `intent_node / intent_knowledge_base`、`active_intent_version`、Versioned Snapshot、`IntentTreeCacheManager`、后台 CRUD / 发布 / 切换版本接口 | 后端 3 天 |
| **Phase 2C** | 收口管理面与联调 | 意图树后台页面、发布/切换版本操作、知识库绑定交互、聊天路由/追问/续接联调 | 后端 1 天，前端 2 天 |

#### 4.2.1 编排层级重构 (Orchestration Layer)

**核心原则**：意图识别是“前置准备（Preparation）”的一部分，不是“Agent 执行（Execution）”的一部分。

**新执行链路**：
1. **ConversationOrchestrator** 接收用户消息。
2. **待澄清检查**：检查 Redis `PendingIntentResolutionStore` 是否有该会话的待澄清状态。
   - 若有：执行 `ClarificationResolver` 解析用户回复，命中意图。
   - 若无：调用 `IntentRouter` 进行分层识别。
3. **产出 IntentResolution**：包含 `kind`、`path`、`scopedKbIds`、`scopePolicy`、`allowedTools`、`systemPromptOverride`。
4. **决策分流**：
   - `kind=CLARIFY`：调用 `ClarificationResponseBuilder` 存入 Redis 并直接返回追问。
   - `kind=SYSTEM`：由编排层直接渲染 `PromptTemplate` 返回，**不实例化 Agent**。
   - `kind=KB/TOOL`：将 `IntentResolution` 传递给 `ChatAgentFactory`，按需创建 Agent 并启动 `run()`。

#### 4.2.2 待澄清状态管理 (PendingIntentResolutionStore)

为解决“用户回复‘第二个’”无法续接的问题，引入基于 Redis 的临时状态存储。

*   **存储实体**：`PendingIntentResolution`
    *   `sessionId`: 会话 ID
    *   `candidateNodeIds`: 之前提供给用户的选项 ID 列表
    *   `originalQuery`: 用户最初的问题（用于重写续接）
    *   `parentPath`: 当前所在的意图树层级
    *   `expiresAt`: 过期时间（默认 5 分钟）

#### 4.2.3 意图树发布模型 (Versioned Snapshot Model)

避免“新旧交替”时的逻辑冲突，确保路由的原子性切换。

*   **版本控制**：`intent_node` 表增加 `version` (INT) 字段。
*   **发布逻辑 (Publishing)**：
    1. 管理员在 UI 上编辑 `version=0` (DRAFT) 的节点。
    2. 点击“发布”时，系统执行以下事务：
       - 生成新的全局递增版本号 `N`。
       - 将所有当前 DRAFT 节点深拷贝一份，版本号设为 `N`，状态设为 `PUBLISHED`。
       - 更新 `agent` 表中的 `active_intent_version = N`。
       - **原子级刷新 Redis 缓存**：Key 包含版本号或直接指向 Active 树。
    3. **回滚能力**：通过切换 `active_intent_version` 即可实现秒级线上路由回滚。

#### 4.2.4 运行时能力矩阵与 IntentResolution 契约

`IntentResolution` 是编排层交付给执行层的**唯一契约**。

**IntentResolution 定义**：
```java
public record IntentResolution(
    IntentKind kind,              // KB / TOOL / SYSTEM / CLARIFY
    List<IntentNode> path,        // 命中路径
    List<String> scopedKbIds,     // 圈定的知识库范围
    ScopePolicy scopePolicy,      // STRICT / FALLBACK_ALLOWED
    List<String> allowedTools,    // 允许调用的工具白名单
    String systemPromptOverride   // SYSTEM 意图下的回复模板
) {}
```

**运行时行为矩阵**：

| 意图类型 (Kind) | 实例化 Agent | 注入 SearchTool | 检索 Scope 守卫 | ScopePolicy 默认值 |
| :--- | :--- | :--- | :--- | :--- |
| **KB (知识库)** | 是 | 是 | **强制注入** | FALLBACK_ALLOWED |
| **TOOL (工具)** | 是 | 否 | 否 | N/A |
| **SYSTEM (系统)** | **否** | 否 | 否 | N/A |

#### 4.2.5 意图感知的问题重写 (Intent-Aware Rewriting)

*   **输入**：原始问题 + 对话历史 + **已命中意图路径**。
*   **Prompt**：将命中路径（如：HR > 考勤 > 请假）作为事实注入 LLM，确保重写后的 Query 精准。

#### 4.2.6 后端改动清单 (修正版)

| 任务 | 涉及目录 | 备注 |
|------|-----------|------|
| **PendingIntentResolutionStore** | persistence/redis | 澄清分支闭环的关键 |
| **IntentResolution 契约** | support/dto | 解耦编排与执行的契约 |
| **ConversationOrchestrator 改造** | conversation | 移入意图识别、澄清检查、分流决策 |
| **ChatAgentFactory 改造** | agent | 接收 IntentResolution，按矩阵注入工具 |
| **Versioned Snapshot 发布逻辑** | intent/admin | 包含表结构变更、快照发布与版本切换接口 |
| **SearchScopeResolver** | rag | 仅负责执行 Scope 边界，不判断业务语义 |

#### 4.2.7 验证指标 (KPI) 修正

*   **澄清转化率**：追问后用户回复并成功命中意图的比例。
*   **路由纯净度**：TOOL 意图下不产生任何 KB 检索请求。
*   **时延目标**：缓存命中时 1s 内；模型路由时（单层）1.5s 内。使用高效分类模型（如 DeepSeek-V3 / Qwen-Turbo）。

---

### Phase 3：会话记忆管理（增量摘要方案）

> **目标**：通过“三层记忆金字塔”架构，在确保上下文不丢失的前提下，将 Token 消耗降低 60% 以上，并彻底解决长对话的时延抖动问题。
> **预估**：后端 7 天

#### 4.3.0 执行拆分 (Incremental Implementation)

| 子阶段 | 目标 | 主要产出 |
|------|------|----------|
| **Phase 3A** | 记忆基建与状态追踪 | `chat_session_summary` 表（含 `seq_no` 水位线）、`chat_message.turn_id` 列、Redis 分布式锁 |
| **Phase 3B** | 增量摘要引擎 | `IncrementalSummarizer`、基于 `turn_id` 的原子回合提取、实体锚定校验、异步调度器 |
| **Phase 3C** | 运行时集成 | `AgentMemoryLoader` Token 预算制改造、System Prompt 分层注入 |

#### 4.3.1 三层记忆金字塔架构

1.  **L1：短期强记忆 (Atomic Turn Window)**
    *   **内容**：最近的完整原子回合，以 **Token 预算**（默认 4000 tokens，实际填充上限为预算的 80%，即 3200 tokens）倒序填充，而非固定 3-4 个回合。20% 安全余量用于应对不同模型 Tokenizer 效率差异（如 cl100k_base vs Qwen 151k），防止因粗估偏差导致 Token 溢出 400 Error。
    *   **回合定义**：1个 User 消息 + 及其引发的所有 Tool 调用/响应序列 + 最终 Assistant 响应。由 `ConversationOrchestratorServiceImpl` 在 `handleUserTurn` 入口处生成 `turn_id`，该回合内所有消息（User、中间 Tool、最终 Assistant）都携带此标识，精确圈定原子回合边界。
    *   **价值**：保证工具链执行和当前话题的绝对语境完整性。Token 预算制避免了固定回合数在不同回合大小下的浪费或溢出。
2.  **L2：中期增量摘要 (Rolling Summary)**
    *   **内容**：对 L1 窗口之外的历史进行压缩。
    *   **算法**：`New Summary = Summarize(Old Summary + Stable New Turns)`。
3.  **L3：长期画像 (User Profile)**
    *   **内容**：已有的 `userProfileSummary`。

#### 4.3.2 关键工程细节

*   **水位线 (High-Water Mark)**：在 `chat_message` 表新增 `seq_no BIGSERIAL` 自增列，水位线直接用 `last_seq_no` 标记。相比原 `(last_created_at, last_message_id)` 复合游标，单一自增序列天然单调递增，免去 UUID 无序和时间戳微秒级并发的 edge case，同时简化查询和比较逻辑。
*   **原子回合标识 (Turn Boundary)**：在 `chat_message` 表新增 `turn_id VARCHAR(36)` 列。`ConversationOrchestratorServiceImpl.handleUserTurn()` 在入口处生成 `turn_id`（UUID），该回合内所有消息均携带此标识。`TurnBasedContextExtractor` 仅需 `GROUP BY turn_id` 即可精确提取，不再通过消息角色序列反推边界。
*   **噪声过滤 (Context Cleaning)**：摘要引擎在提取 L2 消息时，依据 `turn_id` 分组后，仅保留每个回合中 `role=USER` 的原始消息和最终 `role=ASSISTANT`（不带 `toolCalls`）的结论消息。过滤所有 `role=TOOL` 的消息及带有 `toolCalls` 载荷的中间 `ASSISTANT` 消息。
*   **并发与一致性**：使用 Redis 锁 `chatagent:memory:lock:{sessionId}`。
*   **事件触发机制 (Two-Path Event)**：统一通过 `ConversationTurnCompletedEvent` 触发摘要。
    *   **直答路径**：由 `Orchestrator` 在消息 **persist 成功之后、方法返回之前**发布（不依赖 SSE 发送成功）。
    *   **Agent 路径**：由 `ChatEventListener` 在 `ChatAgent.run()` 彻底结束后发布。
    *   **事件载荷**：必须携带 `turn_id` 和 `last_seq_no` 作为本次回合的**截止锚点**。
    *   **幂等保障**：`AsyncSummaryListener` 获取 Redis 锁后，必须**再次校验水位线**（`last_seq_no` 是否已 >= 事件锚点），确保 Agent 重试、SSE 断连重连等场景不会重复摘要。
*   **首次触发条件**：`AsyncSummaryListener` 在执行摘要前检查总回合数，若 `totalTurns <= L1_WINDOW_SIZE` 则跳过，避免对短对话产生无意义的 LLM 调用。
*   **摘要任务资源隔离**：`AsyncSummaryListener` 使用独立线程池 `@Async("summaryExecutor")`，配置 `ThreadPoolTaskExecutor`（核心线程 1-2，最大队列 20）。队列满时采用 `DiscardOldestPolicy` 丢弃最旧任务——下一次回合结束仍会增量触发，不会丢失数据。独立线程池防止摘要 LLM 调用（尤其是本地 Ollama 模型响应较慢时）抢占主对话链路的计算资源。
*   **摘要长度约束**：`IncrementalSummarizer` 必须设定硬性长度上限（如 500 字）。当摘要接近上限时，LLM 应自动从”叙述式”转向”关键事实罗列式”，防止 L2 记忆本身发生 Token 膨胀。
*   **摘要模型路由**：摘要任务使用专用轻量模型（如 deepseek-chat / qwen-turbo），需在 `ChatModelRouter` 或独立配置中新增 `summary-model` 通道，与主对话模型隔离。
*   **实体锚定校验 (Entity Anchoring)**：从用户消息中用正则提取明确实体（订单号、日期、金额等），存入 `chat_session_summary.anchored_entities` JSON 字段。每次滚动摘要后校验锚定实体是否仍出现在新摘要中，丢失则输出告警日志，防止摘要质量随轮次退化。
*   **摘要 Prompt 结构化**：`IncrementalSummarizer` 的 Prompt 必须明确输出格式，避免模型自由发挥：
    ```
    Given the existing summary and new conversation turns, produce an updated summary.
    EXISTING SUMMARY: {oldSummary}
    NEW TURNS: {newTurns}
    OUTPUT (keep under 500 chars, prioritize factual accuracy):
    - Key topics: ...
    - Decisions made: ...
    - Open questions: ...
    - Important entities (IDs, dates, amounts): ...
    ```

#### 4.3.3 后端改动清单 (3A/3B/3C)

| 任务 | 描述 | 涉及目录 |
|------|-----------|----------|
| **3A: Schema Migration** | `chat_message` 表加 `seq_no BIGSERIAL` + `turn_id`；落地 `chat_session_summary` 表（含 `last_seq_no`、`anchored_entities` JSON 字段） | persistence / db/migration |
| **3A: Turn ID Injection** | 改造 `ConversationOrchestratorServiceImpl.handleUserTurn()` 在入口生成 `turn_id`，贯穿整个回合的消息持久化链路 | conversation |
| **3A: Redis Lock Manager** | 实现分布式锁，防止并发摘要任务重叠 | framework/redis |
| **3B: Turn-Based Extractor** | 实现基于 `turn_id` 的原子回合提取，按角色过滤噪声（仅保留 User 原文 + 最终 Assistant 结论） | conversation/summary |
| **3B: Rolling Summarizer** | 实现基于 LLM 的增量压缩算法 + 结构化 Prompt + 实体锚定校验 | conversation/summary |
| **3B: Summary Model Channel** | 新增 `summary-model` 配置通道，路由摘要任务到轻量模型 | config / model |
| **3B: Async Listener** | `AsyncSummaryListener` 监听事件，含幂等水位线校验 + 首次触发前置检查 | conversation/summary |
| **3B: Summary Thread Pool** | 配置独立 `summaryExecutor` 线程池（核心 1-2，队列 20，`DiscardOldestPolicy`），隔离摘要任务与主对话资源 | config |
| **3C: Loader Refactoring** | 改造 `AgentMemoryLoader` 为 Token 预算制（默认 4000 tokens，80% 安全水位即 3200 tokens 实际填充上限），倒序填充完整回合 | agent/runtime |
| **3C: Prompt Injection** | 在 System Prompt 中按分层顺序注入：Base → L2 Historical Summary → Intent Context → Session Context | agent |
| **3C: Session Cleanup** | `ChatSessionFacadeServiceImpl` 删除 Session 时，在同一事务内联合清理 `chat_session_summary` | conversation |

#### 4.3.4 验证指标 (KPI)

*   **Token 节省率**：长对话（>20轮）下，单次请求的 Context Token 消耗降低 50%-80%。
*   **信息完整度**：摘要必须包含对话中提到的关键实体（如：订单号、日期、特定要求）。通过 `anchored_entities` 校验机制量化检测。
*   **无感知触发**：摘要任务在后台执行，用户侧端到端时延增加 < 50ms。
*   **幂等性**：同一回合的事件重复触发不会产生重复摘要或数据不一致。
*   **短对话零开销**：回合数 ≤ L1 窗口时，不触发任何摘要 LLM 调用。

---

### Phase 4：助手模板 + 引用溯源展示

> **目标**：预置常用助手模板，管理员可初始化系统内部助手；回答标注信息来源。
> **预估**：后端 4 天，前端 2.5 天

#### 4.4.1 Agent 模板

```sql
CREATE TABLE agent_template (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(200) NOT NULL,
    description   TEXT,
    system_prompt TEXT NOT NULL,
    model         VARCHAR(50),
    allowed_tools JSONB DEFAULT '[]',
    chat_options  JSONB DEFAULT '{}',
    category      VARCHAR(50),   -- HR / IT / CUSTOMER_SERVICE / DATA_ANALYSIS
    intent_tree   JSONB,         -- 预置的意图树结构（节点名/层级/kind 描述）
    created_at    TIMESTAMP DEFAULT NOW()
);
```

**"从模板初始化"策略**：模板的 `intent_tree` 仅存储意图树的结构描述（节点名、层级、kind），不存储完整快照。初始化时由后端遍历 JSON 结构，逐一调用 `IntentTreeFacadeService` 创建节点 → 发布版本 → 刷新缓存。复用已有逻辑，不需要新写批量导入。4 个预置模板只初始化一次，性能不敏感。

#### 4.4.2 引用溯源

前置条件：Phase 1 已经把检索结果统一为 `RetrievalHit`，所以 Phase 4 主要做的是”引用元数据穿透链路 + 前端展示”。

**核心问题**：当前 `RetrievalHitFormatter.formatForPrompt()` 把结构化的 `RetrievalHit` 转为纯文本扔给 LLM，此后文档名、章节路径等结构化元数据就**丢失**了。前端即使看到 `[1]`，也无法知道它对应哪个文档。Phase 4 的关键工程挑战是让引用元数据从 RAG 检索穿透到前端。

**4.4.2.1 引用数据模型**

```java
// 标准化引用数据结构，贯穿后端全链路
public record CitationMetadata(
    RagSourceType sourceType,    // KNOWLEDGE_BASE / SESSION_FILE
    String sourceId,
    String documentId,
    String documentName,
    String sectionPath,
    Integer chunkIndex,
    String snippet,              // content 的前 200 字摘要
    Double score
) {}
```

**4.4.2.2 后端引用穿透链路**

```
RetrievalHit（结构化，Phase 1 已有）
    ↓
RetrievalHitFormatter.formatWithCitations()       ← 改造：输出编号文本 + 对齐的 CitationMetadata 列表
    ↓
String（prompt 文本，含 [1] [2] 标记） + List<CitationMetadata>（编号与列表 index 严格对齐）
    ↓
SessionFileTools.knowledgeQuery()                  ← 改造：citations 写入 run-scoped holder（sessionId + turnId 级别）
    ↓
AgentMessageBridgeImpl.persistAndPublish()         ← 改造：ASSISTANT 消息持久化时按 sessionId + turnId 取出 citations 附着到 metadata
    ↓
ChatMessageDTO.MetaData.citations                  ← 扩展：新增 List<CitationMetadata> 字段
    ↓
chat_message.metadata JSONB                        ← 已有列，天然支持，不需要加列
    ↓
ChatMessageVO.metadata.citations                   ← 扩展：携带到前端
    ↓
SseMessage.Payload.message.metadata.citations      ← SSE 推送
```

**关键约束**：
1. `RetrievalHitFormatter` 输出时标注的编号（`[1]`、`[2]`）必须与 `CitationMetadata` 列表的 index 严格一致。这是引用正确性的根基。
2. **引用元数据不能靠 ThreadLocal**。同一 turn 中可能产生多条 assistant 中间消息（工具调用循环），ThreadLocal 的"最后一次检索结果"语义会把引用挂错消息。必须使用 **sessionId + turnId 级别的 run-scoped holder**（例如 `CurrentTurnCitationHolder`），确保 citations 与产生它们的那条消息严格绑定。

**4.4.2.3 LLM Prompt 引用指令**

检索结果携带文档名、段落路径和 chunk 元数据，prompt 要求 LLM 在回答中标注引用：

```
根据《员工手册》第3章[1]，年假天数根据工龄计算...

---
[1] 来源：员工手册.pdf > 第三章 休假制度
```

**4.4.2.4 前端展示**

- **来源区以 `metadata.citations` 为准渲染**：只要 `citations` 数组非空就展示来源区，不依赖正文中是否存在 `[N]` 标记。这样即使 LLM 漏写了 `[N]`，来源区也不会一起消失
- 回答正文中的 `[1] [2]` 是增强体验：解析 `[N]` 后通过 `metadata.citations[N-1]` 映射到结构化来源信息，hover tooltip 弹出来源摘要
- 底部来源区展示 `文档名 / 章节路径 / 命中片段`
- 无检索命中时（`citations` 为空或不存在）不显示来源区
- 后续可扩展为点击来源直接打开文档定位

---

## 五、完整对话链路（改造后）

```
用户发送消息
     │
     ▼
┌──────────────────────────┐
│  1. Turn Preparation     │  PendingIntent check / ClarificationResolver
│  (Orchestrator)          │  IntentRouter / QueryRewriter
└────────┬─────────────────┘
         │
  IntentResolution
         │
    ┌────┼──────────┬──────────────┐
    ▼    ▼          ▼              ▼
 kind=KB  kind=TOOL  kind=SYSTEM   kind=CLARIFY
    │       │          │              │
    ▼       │          ▼              ▼
┌────────┐  │     Orchestrator    Clarification
│2.问题  │  │     直答 (SYSTEM)    Response Builder
│  重写  │  │                    (存入 PendingIntentResolutionStore)
└───┬────┘  │
    │       │
    ▼       │
┌────────┐  │
│3.精准  │  │
│  检索  │  │
│返回结构│  │
│化命中  │  │
└───┬────┘  │
    ▼       ▼
┌──────────────────────────┐
│  4. 记忆组装               │  摘要（Layer2）+ 最近对话（Layer1）
│  ConversationMemoryManager │
└────────┬─────────────────┘
         ▼
┌──────────────────────────┐
│  5. Agent 执行（ReAct）    │  think() → tool call → execute → loop
│  ChatAgentFactory 实例化   │  (根据 IntentResolution 注入工具)
└────────┬─────────────────┘
         │
    ┌────┼────┐
    ▼         ▼
┌────────┐ ┌────────┐
│知识库  │ │工具    │
│补充检索│ │执行    │
│(可选)  │ │        │
└───┬────┘ └───┬────┘
    ▼          ▼
┌──────────────────────────┐
│  6. 答案生成 + 引用溯源    │  基于 RetrievalHit 标注 [1] [2]
│  + 异步记忆压缩            │  Redis 锁 → LLM 摘要 → DB
└────────┬─────────────────┘
         ▼
    SSE 流式推送
```

---

## 六、改造后整体架构

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
│  │                    1. 编排层 (Orchestrator)               │    │
│  │  意图识别(Router/Clarify) → 问题重写 → 记忆组装 → 分流    │    │
│  │       ↑ Redis 缓存           ↑ PendingIntentResolutionStore        │    │
│  └──────────────────────┬──────────────────────────────────┘    │
│                         │                                        │
│  ┌──────────────────────┴──────────────────────────────────┐    │
│  │                    2. 执行层 (Agent Loop)                 │    │
│  │  ChatAgentFactory → ChatAgent → ThinkingEngine           │    │
│  └──────────────────────┬──────────────────────────────────┘    │
│                         │                                        │
│         ┌───────────────┼───────────────┐                       │
│         ▼               ▼               ▼                       │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐                  │
│  │RAG 检索层│    │工具执行层│    │ 对话服务 │                   │
│  │知识库+   │    │邮件/DB/ │    │ 会话管理 │                   │
│  │临时文件  │    │文件系统  │    │ 消息持久 │                   │
│  └────┬─────┘    └──────────┘    └──────────┘                  │
│       │                                                          │
│  ┌────┴──────────────────────────────────────────────────┐      │
│  │                    基础设施层                           │      │
│  │  PostgreSQL  │  Milvus  │  Redis  │  Ollama  │  LLM API│     │
│  └────────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────┘
```

---

## 七、各 Phase 工作量与排期

| Phase | 内容 | 后端 | 前端 | 依赖 |
|-------|------|------|------|------|
| **Phase 0** | 基线收敛 + 安全清理 + 单助手入口 | 2 天 | 1 天 | 无 |
| **Phase 1A** | `RetrievalHit` 契约 + source-aware indexer | 2 天 | 0 天 | Phase 0 |
| **Phase 1B** | 知识库数据面 + ingestion + 双 collection 检索 | 4 天 | 2 天 | Phase 1A |
| **Phase 1C** | 后台管理面收口 + 单助手知识绑定联调 | 1 天 | 1 天 | Phase 1B |
| **Phase 2A** | 编排契约 + 运行时意图边界 | 4 天 | 0 天 | Phase 1C |
| **Phase 2B** | 意图树数据面 + 版本化快照发布 | 3 天 | 0 天 | Phase 2A |
| **Phase 2C** | 意图树管理面 + 发布联调 | 1 天 | 2 天 | Phase 2B |
| **Phase 3** | 会话记忆管理（增量摘要） | 7 天 | 0 天 | Phase 1B |
| **Phase 4** | 助手模板 + 引用溯源展示 | 4 天 | 2.5 天 | Phase 1A + 1B + 2B |
| **总计** | | **28 天** | **8.5 天** | |

**建议执行顺序**：

```
Phase 0（基线 + 安全 + 单助手入口）
    ↓
Phase 1A（RetrievalHit + source-aware indexer）
    ↓
Phase 1B（知识库 + 双 collection 检索）
    ↓
Phase 1C（后台管理面 + 单助手联调）
    ↓
Phase 2A（编排契约 + 运行时守卫）
    ↓
Phase 2B（意图树数据面 + 版本化快照）
    ↓
Phase 2C（后台管理面 + 联调）
    ↘
      Phase 3（增量记忆，可在 Phase 2C 中后段并行）
    ↗
    ↓
Phase 4（模板 + 引用展示）
```

更现实的排期是 **4-6 周**：

- 单人后端主导 + 轻前端配合：约 5-6 周
- 前后端并行、联调顺利：可压到 4 周左右

---

## 八、新增的数据库表汇总

| 表名 | Phase | 用途 |
|------|-------|------|
| knowledge_base | 1 | 知识库 |
| knowledge_document | 1 | 知识库文档 |
| knowledge_chunk | 1 | 知识库切片 |
| agent_knowledge_base | 1 | Agent-知识库关联 |
| intent_node | 2 | 意图树节点 |
| intent_knowledge_base | 2 | 意图-知识库关联 |
| chat_session_summary | 3 | 会话摘要 |
| agent_template | 4 | Agent 模板 |

保留 `agent` 与 `agent_knowledge_base` 作为内部运行配置；`t_user.role` 保持不变，但需要完成统一枚举值迁移。

---

## 九、不做什么（明确边界）

| 不做 | 原因 |
|------|------|
| 部门体系 | ADMIN/USER 两个角色足够 |
| 工作流编排 | 保持 ReAct Agent 模式 |
| 多租户隔离 | 单企业场景 |
| SSO/LDAP | 先用自有 JWT |
| 模型熔断降级 | 暂不需要复杂容错 |
| 全链路 Trace | 现有 traceId 日志够用 |
| 问题拆分 | 先做问题重写，拆分后续再加 |
| 普通用户自创建/选择 Agent | 当前阶段采用单固定助手入口，Agent 不作为普通用户产品概念 |

---

## 十、验收标准

### Phase 0
- [ ] 仓库文档基线与当前三模块 + Milvus + env 配置保持一致
- [ ] 认证链路不再打印密码、access token、refresh token
- [ ] 普通用户侧不再暴露 Agent 创建/选择入口，聊天统一从 `/chat` 进入
- [ ] 后台管理能力路由骨架建立，管理接口统一规划到 `/api/admin/*`

### Phase 1
#### Phase 1A
- [ ] 检索链路统一返回 `RetrievalHit`，包含来源元数据
- [ ] `IndexedChunkDocument + RagSourceType` 契约落地，source-aware indexer 可复用
- [ ] 不修改现有 session-file collection schema 的前提下，临时文件检索保持可用

#### Phase 1B
- [ ] 管理员可创建知识库、上传/替换/归档/恢复文档，文档自动 ingestion
- [ ] 系统内部助手可绑定多个知识库
- [ ] 用户对话时，Agent 自动从知识库检索
- [ ] 临时文件上传保持可用
- [ ] session-file collection 与 knowledge-base collection 隔离，`SearchScopeResolver` 能统一融合两路结果

#### Phase 1C
- [ ] 用户创建会话/聊天时不需要选择 Agent，后端自动绑定系统默认助手
- [ ] ADMIN/USER 权限隔离
- [ ] 资源级访问控制统一收口到 `ResourceAccessGuard`（至少覆盖 Session / KnowledgeBase），不散落在各个 Facade 中
- [ ] 后台知识库管理入口与内部助手知识绑定入口可用

### Phase 2
#### Phase 2A
- [ ] 用户提问先经过分层意图识别，精准路由到具体知识库集合
- [ ] 待澄清状态可续接且有过期机制（基于 PendingIntentResolutionStore）
- [ ] 闲聊不触发检索，意图不明或 top1/top2 分差过小时主动追问
- [ ] 多轮对话自动重写查询再检索
- [ ] 意图层 scope 是边界控制：Agent Loop 内的检索工具不暴露 kbIds 参数，无法突破意图圈定的范围
- [ ] ScopePolicy（STRICT / FALLBACK_ALLOWED）可配置、可观测（fallback 时有日志记录）

#### Phase 2B
- [ ] 管理员可维护意图树草稿（CRUD 节点）
- [ ] KB 类型叶子节点可绑定多个知识库
- [ ] 只有 active_intent_version 对应的已发布快照参与线上路由，DRAFT 变更对用户不可见
- [ ] 意图树缓存在 Redis，快照发布或切换 active_intent_version 后同步刷新
- [ ] 支持快照发布与版本切换回滚

#### Phase 2C
- [ ] 后台意图树管理页可用，支持编辑、绑定知识库、发布/切换版本
- [ ] 前后端联调跑通：闲聊、工具意图、KB 意图、意图不明追问、状态续接
- [ ] 编排层直答（`CLARIFY / SYSTEM`）持久化后也能实时触发 SSE 推送，前端无需区分消息来源

### Phase 3
- [x] `chat_message` 表具备 `seq_no BIGSERIAL` 自增列和 `turn_id` 列
- [x] `Orchestrator` 每次 `handleUserTurn` 生成 `turn_id` 并贯穿回合所有消息
- [x] 超过 L1 Token 预算窗口的对话自动触发增量摘要压缩
- [x] 短对话（回合数 ≤ L1 窗口）不触发摘要 LLM 调用
- [x] prompt 按分层顺序注入：Base → L2 Historical Summary → Intent Context → Session Context
- [x] Token 消耗明显下降（长对话 >20 轮下降 50%-80%）
- [x] Redis 锁 + `seq_no` 水位线 + 幂等校验防止重复压缩与乱序
- [x] 实体锚定校验：摘要丢失关键实体时输出告警日志
- [x] Session 删除时同一事务内联合清理 `chat_session_summary`


### Phase 4
- [ ] `agent_template` 表和 CRUD 接口可用，含 4 个预置模板
- [ ] 管理员可从模板初始化系统内部助手（Agent + IntentTree + KB 绑定 + 版本发布 + 缓存刷新事务完整）
- [ ] `RetrievalHit` → `CitationMetadata` 结构化引用贯穿到 `ChatMessageVO.metadata.citations`
- [ ] LLM prompt 中引用编号与 citations 数组顺序严格一致
- [ ] 前端消息中 `[N]` 标记可点击/hover，底部来源区展示文档名/章节/片段
- [ ] 无检索命中时不显示来源区，不出现空的 `[N]` 标记

---

## 十一、与 Ragent 对标

| 能力 | Ragent | ChatAgent（改造后） |
|------|--------|-------------------|
| 知识库管理 | ✅ 独立知识库 | ✅ Phase 1 |
| 意图识别 | ✅ 树形意图 + Redis 缓存 | ✅ Phase 2A + 2B（分层路由 + 已发布快照） |
| 问题重写 | ✅ 重写 + 拆分 + 术语映射 | ✅ Phase 2A（重写，暂不做拆分和术语映射） |
| 多路检索 | ✅ 向量 + BM25 | ✅ 已有 |
| Rerank | ✅ 有 | ✅ 已有 |
| 会话记忆 | ✅ 滑窗 + 摘要 + Redis 锁 | ✅ Phase 3（Token 预算制 L1 + 增量摘要 L2 + 实体锚定 + 幂等保障） |
| 模型路由容错 | ✅ 熔断 + 降级 + 首包探测 | ⚠️ 多模型可切换，暂无熔断 |
| MCP 工具 | ✅ MCP 协议 | ⚠️ 硬编码工具注册 |
| **Agent 自主循环** | ❌ 固定流水线 | ✅ **ReAct 架构**（差异化优势） |
| 全链路 Trace | ✅ AOP + Trace | ⚠️ 基础 traceId 日志 |
| 文档入库流水线 | ✅ Pipeline 编排 | ✅ 已有 |
| 管理后台 | ✅ 完整 | ✅ 已有 |
| 权限体系 | ✅ Sa-Token | ✅ Phase 1（现有 JWT + 自定义 RBAC） |
| 引用溯源 | ❌ 无 | ✅ Phase 1 契约 + Phase 4 结构化穿透（RetrievalHit → CitationMetadata → 前端 `[N]` 映射）（**优势**） |

**ChatAgent 的差异化优势**：
1. **ReAct Agent 架构** — LLM 自主决策，不是固定流水线。面试时讲 Agentic RAG 有区分度
2. **意图识别 + Agent 循环融合** — 意图识别做粗分路由，Agent 循环做细粒度决策，两层配合
3. **引用溯源** — 回答标注来源，Ragent 没有这个

---

## 十二、开发任务清单

> 下面的清单按实际开发顺序拆分，可直接作为迭代任务池使用。

### 12.0 Phase 0：基线收敛 + 安全清理 + 单助手入口

- [ ] 统一 README / 模块文档 / 改造方案中的当前架构基线描述，以三模块后端 + Milvus + env 配置为准
- [ ] 清理注册、登录、刷新、鉴权链路中的密码与 token 日志
- [ ] 建立数据库迁移基线（Flyway / Liquibase 或版本化 SQL 目录）
- [ ] 统一 `t_user.role` 枚举迁移策略，兼容历史 `user/admin`
- [ ] 前端移除普通用户创建/选择 Agent 的依赖，聊天入口固定为 `/chat`
- [ ] 建立 `/chat` 与 `/admin/*` 路由骨架
- [ ] 约定后台管理接口统一使用 `/api/admin/*`

### 12.1 Phase 1：知识库独立化 + 基础 RBAC + 单助手知识绑定

#### 12.1.A Phase 1A：检索契约与 source-aware indexer

- [ ] 抽象检索结果模型，新增 `RetrievalHit`
- [ ] 改造 RagService / SimilaritySearcher，返回结构化 `RetrievalHit`
- [ ] 优先落地 source-aware indexer 与统一检索契约，避免后续继续返回字符串拼接结果
- [ ] 保留现有 session-file collection schema，不做破坏性变更
- [ ] 新增 `SessionFileIndexedChunkAssembler`，统一 session-file 索引输入
- [ ] 新增 `SessionScopedMilvusChunkMapper`，将通用索引契约适配到现有 session-file collection

#### 12.1.B Phase 1B：知识库数据面与双 collection 检索

- [ ] 编写数据库迁移脚本：`knowledge_base`、`knowledge_document`、`knowledge_chunk`、`agent_knowledge_base`
- [ ] 新增 KnowledgeBase / KnowledgeDocument / KnowledgeChunk DTO 与实体映射
- [ ] 新增 KnowledgeBaseRepository、KnowledgeDocumentRepository、KnowledgeChunkRepository、AgentKnowledgeBaseRepository
- [ ] 为知识库新增独立 Milvus collection，不扩展现有 session-file collection schema
- [ ] 新增 `KnowledgeBaseIndexedChunkAssembler`，复用 `IndexedChunkDocument + RagSourceType` 契约
- [ ] 新增 `KnowledgeBaseMilvusChunkMapper / KnowledgeBaseMilvusIndexer`，将知识库 chunk 写入独立 knowledge-base collection
- [ ] 新增 `KnowledgeBaseSimilaritySearcher`，按 `knowledge_base_id` 范围检索 knowledge-base collection
- [ ] 新增 `SearchScopeResolver`，分别查询 knowledge-base / session-file collection 后统一做 RRF + rerank
- [ ] 新增知识库文档 ingestion 流程，复用现有 parser / chunker / enricher
- [ ] knowledge-base collection 从第一版 schema 就写入 `section_path`，避免后续引用溯源回填
- [ ] 视复杂度补充 `ingestion_task`，或在 `knowledge_document` 中补齐 `content_hash / failed_reason / indexed_at / retry_count`
- [ ] 新增知识库管理接口：创建、列表、详情、更新、归档、恢复
- [ ] 新增知识库文档接口：上传、列表、替换、归档
- [ ] 新增后台内部助手绑定知识库接口
- [ ] 改造 SessionFileSearchTool，使其支持“知识库 + 临时文件”联合检索
- [ ] 改造 Agent 运行时上下文装载，补充知识库摘要入口

#### 12.1.C Phase 1C：后台管理面收口与单助手联调

- [ ] 统一 `t_user.role` 枚举值，完成历史数据兼容脚本
- [ ] 实现 `@RequireRole` 注解与 RBAC 拦截
- [ ] 新增 `ResourceAccessGuard`，统一收口 Session / KnowledgeBase 资源级访问校验，所有 Facade 通过 guard 方法完成归属校验
- [ ] 补充 `ResourceAccessGuard` 单元测试：USER 只能访问自己的 session、ADMIN 可管理所有知识库
- [ ] 改造 ChatSession 创建入口：不再要求前端传 `agentId`，统一绑定系统默认助手
- [ ] 前端新增知识库管理页
- [ ] 前端新增内部助手知识绑定页或后台设置区
- [ ] 前后端联调：知识库上传、入库、检索、单助手入口、权限拦截
- [ ] 补充测试：Repository、RBAC、知识库 API、联合检索

### 12.2 Phase 2：分层树形意图识别 + 问题重写

#### 12.2.A Phase 2A：编排契约与运行时边界

- [ ] 实现 `IntentResolution` 契约，统一承载 `kind / path / scopedKbIds / scopePolicy / allowedTools / systemPromptOverride`
- [ ] 实现 `PendingIntentResolutionStore`，支持会话级待澄清状态续接与过期
- [ ] 实现分层 `IntentRouter`：DOMAIN → CATEGORY → TOPIC
- [ ] 为每层节点设计分类 prompt，支持 `description + examples`
- [ ] 实现 top1 / top2 分差判断与追问策略
- [ ] 实现 `ClarificationResponseBuilder` 并存入 `PendingIntentResolutionStore`
- [ ] 实现 `QueryRewriter`，并把意图路径作为重写上下文输入
- [ ] 实现 `ConversationOrchestrator` 编排逻辑：Turn Preparation（澄清检查、意图路由、重写、分流决策，产出 `IntentResolution`）
- [ ] 改造 `SearchScopeResolver`：从 `IntentResolution` 中读取 `ScopePolicy` 与 `scopedKbIds` 实施边界守卫
- [ ] 改造 `ChatAgentFactory`：接收 `IntentResolution`，按矩阵注入工具集合
- [ ] 改造 Agent `searchTool`：不暴露 `knowledgeBaseIds` 参数，scope 由意图层注入，Agent 只控制查询内容
- [ ] 补充测试：分层路由、分差追问、Query Rewriter、ScopePolicy 边界守卫、PendingIntent 过期与续接

#### 12.2.B Phase 2B：意图树数据面与版本化快照

- [ ] 编写数据库迁移脚本：`intent_node`、`intent_knowledge_base`，并为 `agent` 增加 `active_intent_version`
- [ ] 新增 `IntentNode / IntentKnowledgeBase` DTO、实体、Repository
- [ ] 实现 KB 意图绑定多个知识库的查询逻辑
- [ ] 实现 `IntentTreeCacheManager`，只加载 `active_intent_version` 对应快照
- [ ] 实现版本化快照发布：DRAFT 深拷贝为新版本、切换 `active_intent_version`、同步刷新线上缓存
- [ ] 实现版本切换回滚能力
- [ ] 新增意图树管理接口：节点 CRUD、节点绑定知识库、快照发布、切换版本
- [ ] 预留 `IntentRouter` 从启发式实现升级到轻量级 LLM 分类器（如 DeepSeek-V3 / Qwen-Turbo）的能力，保持 `IntentRouter` 接口与 `IntentResolution` 契约不变
- [ ] 补充测试：意图树构建、快照发布、版本切换、缓存刷新

#### 12.2.C Phase 2C：后台管理面与联调

- [ ] 前端新增意图树管理页面
- [ ] 前端支持节点编辑、排序、启停、绑定知识库、发布快照、切换版本
- [ ] 前后端联调：闲聊、工具意图、KB 意图、意图不明追问、状态续接、发布/回滚
- [ ] 联调时补齐编排层直答消息分发：`persistDirectAssistantReply` 落库后也触发 SSE 推送，聊天侧与 Agent 回复共用同一套实时展示语义

### 12.3 Phase 3：会话记忆管理（三层记忆金字塔）

#### 12.3.A Phase 3A：记忆基建与状态追踪（~1.5 天）✅
- [x] 编写数据库迁移脚本 V5：`chat_message` 表加 `seq_no BIGSERIAL` 自增列 + `turn_id VARCHAR(36)` 列（含索引）
- [x] 编写数据库迁移脚本 V5（续）：`chat_session_summary` 表（含 `last_seq_no BIGINT`、`summary TEXT`、`anchored_entities JSONB`、`version INT`）
- [x] 改造 `ChatMessageDTO` / `ChatMessage` 实体 / `ChatMessageMapper.xml`：支持 `seq_no` 和 `turn_id` 字段读写
- [x] 改造 `ConversationOrchestratorServiceImpl.handleUserTurn()`：在入口生成 `turn_id`（UUID），通过 `ConversationTurnContext` 传递给下游所有消息持久化点
- [x] 新增 ChatSessionSummary 实体、DTO 与 MyBatis Mapper
- [x] 实现 RedisLockManager，提供基于 `sessionId` 的摘要任务互斥锁（key: `chatagent:memory:lock:{sessionId}`）（含 Lua 原子 compare-and-delete）
- [x] 实现 SummaryWatermarkService，支持基于 `seq_no` 的水位线读写与区间消息检索
- [x] 编写 3A 单元测试：`seq_no` 单调递增验证、`turn_id` 回合分组、并发锁竞争、乐观锁版本更新

#### 12.3.B Phase 3B：增量摘要引擎与异步调度（~3 天）✅
- [x] 实现 TurnBasedContextExtractor：基于 `turn_id` 分组提取原子回合，按角色过滤噪声（仅保留 User 原文 + 最终 Assistant 无 toolCalls 的结论消息）
- [x] 实现 IncrementalSummarizer：结构化 Prompt（含长度约束 + KV 降级提示），硬性 500 字上限，LLM 失败时确定性降级
- [x] 实现实体锚定校验：从 User 消息正则提取关键实体 → 存入 `anchored_entities` → 摘要后校验留存率，丢失则告警
- [x] 新增 `summary-model` 配置通道：`chatagent.memory.summary-model` 支持为摘要任务路由到专用轻量模型
- [x] 配置独立线程池 `summaryExecutor`：`ThreadPoolTaskExecutor`（核心 1，最大 2，队列 8，`DiscardOldestPolicy`），隔离摘要 LLM 调用与主对话资源
- [x] 实现 AsyncSummaryListener（`@Async("summaryExecutor")`）：监听 `ConversationTurnCompletedEvent`，含以下保障：
    - 前置检查：`totalTurns <= L1_WINDOW_SIZE` 时直接跳过
    - 获取 Redis 锁后**再次校验**水位线 `last_seq_no` 是否已 >= 事件锚点（幂等保障）
    - 异步触发滚动摘要逻辑
- [x] 改造 `ConversationOrchestratorServiceImpl`：直答路径在消息 **persist 成功后、方法返回前**通过 `ConversationTurnCompletionPublisher` 发布事件
- [x] 改造 `ChatEventListener`：Agent 路径在 `ChatAgent.run()` 结束后发布同一事件（含失败路径）
- [x] 编写 3B 单元测试：`turn_id` 分组过滤准确性、摘要滚动累加逻辑、幂等重复事件无副作用、实体锚定丢失告警、首次触发条件、Prompt 长度约束验证

#### 12.3.C Phase 3C：运行时集成与上下文注入（~2 天）✅
- [x] 改造 AgentMemoryLoader：Token 预算制（默认 4000 tokens × 0.8 = 3200 有效预算），按 turn_id 分组倒序填充，超大单回合保留 + 告警日志
- [x] 改造 DefaultAgentRuntimeContextLoader.buildSystemPrompt()：按分层顺序注入 System Prompt 各区段：
    1. Base System Prompt（角色定义、行为约束）
    2. `[Historical Context Summary]`（L2 滚动摘要 — 全局对话背景）
    3. `[Intent Routing Context]`（当前回合意图路由信息）
    4. `[Session Context]`（文件/知识库摘要、用户画像 L3）
- [x] 新增 `AgentSessionSummaryResolver`：从 `ChatSessionSummaryRepository` 加载 L2 摘要
- [x] 改造 `ChatSessionFacadeServiceImpl`：删除 Session 时在**同一事务内**联合清理 `chat_session_summary` 记录
- [x] 补充测试：AgentMemoryLoaderTest（Token 预算填充 + 超大回合保留）、DefaultAgentRuntimeContextLoaderTest（L2 在 Intent 之前注入）、ConversationTurnCompletionPublisherTest（事件发布 + 无锚点跳过）


### 12.4 Phase 4：助手模板 + 引用溯源展示

#### 12.4.A 助手模板（~1.5 天后端 + 0.5 天前端） ✅
- [x] 编写数据库迁移脚本 V6：`agent_template` 表（含 `code` UNIQUE 索引、`built_in` 标记、`ON CONFLICT DO NOTHING` 幂等）
- [x] 新增 AgentTemplate DTO（含嵌套 `IntentTreeNodeTemplateDTO`）、实体、MyBatis Mapper + XML（JSONB 列 `::text` 读取 + `CAST AS jsonb` 写入）
- [x] 设计 4 个预置模板：HR、IT Ops、Customer Service、Data Analysis（各含 system prompt、allowed tools、chat options、完整三层意图树结构，`bindSelectedKnowledgeBases` 声明式标记 KB 绑定节点）
- [x] 实现”从模板初始化系统内部助手”接口：单 `@Transactional` 内更新助手配置 → `replaceBindings` KB → 清空草稿树 → `LinkedHashMap` code→nodeId 映射逐节点创建 → KB 绑定 TOPIC → `publishIntentTreeSnapshot()`
- [x] 前端 `AssistantTemplatePage`：2 列卡片布局，含 intent scope 统计、tools、prompt 预览、”Initialize assistant” 入口
- [x] 前端 `TemplateInitDialog`：警告提示 + 模板摘要 + prompt 全文 + ACTIVE KB 多选绑定 + 结构预览
- [x] 编写模板测试：`AssistantTemplateFacadeServiceImplTest`（列表查询 + 完整初始化链路含 KB 验证、草稿清空、节点创建、KB 绑定、发布快照）

#### 12.4.B 引用溯源后端链路（~2.5 天后端） ✅
- [x] 新增 `CitationMetadata` record：8 字段（sourceType, sourceId, documentId, documentName, sectionPath, chunkIndex, snippet, score），immutable record 类型
- [x] 扩展 `ChatMessageDTO.MetaData`：新增 `List<CitationMetadata> citations` 字段（利用已有 `metadata JSONB` 列，ObjectMapper 序列化/反序列化，不需加列）
- [x] 扩展 `ChatMessageVO`：直接复用 `ChatMessageDTO.MetaData` 类型，citations 自然穿透到前端 + SSE payload
- [x] 改造 `RetrievalHitFormatter`：新增 `formatWithCitations()` → `FormattedRetrievalPrompt(promptText, citations)`，编号 `[N]` 与 citations list index 严格对齐，snippet 截断 180 字符
- [x] 新增 `CurrentTurnCitationHolder`：`ConcurrentHashMap<sessionId::turnId, List<CitationMetadata>>`，**非 ThreadLocal**，提供 `put/peek/take/merge/clear` API，`LinkedHashSet` 去重保序
- [x] 改造 `SessionFileTools.knowledgeQuery()`：`formatWithCitations()` → `currentTurnCitationHolder.put(sessionId, turnId, citations)`
- [x] 改造 `AgentMessageBridgeImpl.persistAndPublish()`：仅在最终 assistant 消息（toolCalls 为空）时 `take` citations，中间 tool-calling 消息不附着引用；`ChatEventListener.finally` 清理 holder
- [x] 引用注入 Prompt：`"Use the following numbered evidence snippets... cite it inline with [n]"`，嵌入 `formatWithCitations()` 输出
- [x] 编写引用测试：`RetrievalHitFormatterTest`（3 个：结构化渲染、空命中降级、编号对齐）+ `CurrentTurnCitationHolderTest`（2 个：存取隔离、无效 key）+ `AgentMessageBridgeImplTest`（2 个：最终消息附着 citations、中间消息不附着）

#### 12.4.C 引用溯源前端 + 联调（~2 天前端 + 1 天联调） ✅
- [x] 新增 `CitationMetadata` TS 接口（`types/index.ts`）：8 字段对齐后端，`ChatMessageVOMetadata.citations?: CitationMetadata[]`
- [x] 新增 `CitationSourcePanel`：**以 `metadata.citations` 为准渲染**——数组非空即展示，不依赖 `[N]`。展示编号圆标、documentName > sectionPath、snippet、sourceType badge、chunkIndex、score
- [x] 改造 `AgentChatHistory.tsx`：`injectCitationLinks()` 将 `[N]` 替换为 `citation://N` 伪链接（跳过 ``` 代码块），markdown `<a>` 组件拦截渲染 `CitationInlineTag`；超出 citations 范围的 `[N]` 保持原样
- [x] 引用交互：`CitationInlineTag` hover Tooltip 弹出 documentName + sectionPath + snippet；click → `navigateToCitation()` → `document.getElementById(citation-source-${messageId}-${index})` → `scrollIntoView({ behavior: "smooth" })`
- [x] 无引用时降级：`citations` 为空 → `CitationSourcePanel` return null；正文无有效 `[N]` → 不渲染内联标记
- [x] 前后端联调：引用展示、模板初始化、无命中降级

### 12.F 前端改造清单

> 前端当前状态：React 18 + Vite + TypeScript + Ant Design + TailwindCSS，状态管理用 React Context（AuthContext / ChatSessionsContext），认证 token 存内存，SSE 做流式推送。
>
> 下面按 Phase 拆分前端独立任务，每个任务可单独跟踪。

#### 12.F.0 现状基线

```
ui/src/
├── api/          http.ts, auth.ts, api.ts          # API 层
├── auth/         token.ts, events.ts               # token 内存存储 + 过期事件
├── components/
│   ├── auth/     AuthCard, AuthDialog, LoginPage    # 认证组件
│   ├── tabs/     ChatTabContent, AgentTabContent    # 侧边栏 tab
│   └── views/    AgentChatView, AgentChatHistory,   # 主内容区
│                 AgentChatInput, EmptyAgentChatView
├── contexts/     AuthContext, ChatSessionsContext   # 全局状态
├── hooks/        useAuth, useChatSessions, useAgents# 自定义 hook
├── layout/       Layout, Sidebar(304px), Content    # 布局骨架
├── types/        index.ts                           # TS 类型定义
└── utils/        index.ts                           # 工具函数
```

**当前路由**（Phase 0 已改造）：
| 路由 | 组件 | 说明 |
|------|------|------|
| `/` | Redirect → `/chat` | |
| `/chat` | `EmptyAgentChatView` | 空白聊天首页 |
| `/chat/:chatSessionId` | `AgentChatView` | 具体会话 |
| `/admin/*` | 占位页 | Phase 0 预留 |

**当前状态管理**：
| Context | 数据 | 说明 |
|---------|------|------|
| `AuthContext` | currentUser, isAuthenticated, authDialogOpen | 认证状态 + 登录弹窗控制 |
| `ChatSessionsContext` | chatSessions[], loading | 会话列表 + 加载状态 |
| `useAgents` hook | agents[] | Agent 列表（Phase 0 已去掉用户侧入口，hook 仍存在） |

#### 12.F.1 Phase 0 前端改动（✅ 已完成）

- [x] 移除用户侧 Agent 创建/选择入口，侧边栏 tab 只保留 "Chats"
- [x] `api.ts`：建会话不传 agentId，发消息不传 agentId
- [x] `JChatMindLayout.tsx`：加 `/admin/*` 占位路由
- [x] `ChatTabContent.tsx`：会话列表直接用 `/chat` 前缀
- [x] `AgentChatView.tsx`：去掉 agentId 依赖
- [x] `EmptyAgentChatView.tsx`：去掉 agent 选择提示
- [x] `AgentChatHistory.tsx`：退化失效的 markdown 高亮插件

#### 12.F.2 Phase 1C 前端任务

> Phase 1A/1B 是纯后端，前端从 Phase 1C 开始。

**路由扩展**：

```
/chat                           # 用户聊天（不变）
/chat/:chatSessionId            # 具体会话（不变）
/admin                          # 后台首页（重定向到知识库）
/admin/knowledge-bases          # 知识库列表
/admin/knowledge-bases/:kbId    # 知识库详情（文档列表 + 上传）
/admin/assistant                # 内部助手设置（绑定知识库）
```

**新增页面与组件**：

| 组件 | 路径 | 说明 |
|------|------|------|
| `AdminLayout` | `components/admin/AdminLayout.tsx` | 后台布局壳（侧边导航 + 内容区），与聊天布局独立 |
| `AdminSideNav` | `components/admin/AdminSideNav.tsx` | 后台左侧导航：知识库、助手设置 |
| `KnowledgeBaseListPage` | `components/admin/knowledge/KnowledgeBaseListPage.tsx` | 知识库列表（创建、归档、恢复） |
| `KnowledgeBaseDetailPage` | `components/admin/knowledge/KnowledgeBaseDetailPage.tsx` | 知识库详情：文档列表、上传、替换、归档 |
| `DocumentUploadDrawer` | `components/admin/knowledge/DocumentUploadDrawer.tsx` | 文档上传抽屉（拖拽上传 + 进度条） |
| `AssistantSettingsPage` | `components/admin/assistant/AssistantSettingsPage.tsx` | 系统内部助手配置：绑定/解绑知识库 |

**API 层扩展**（`api/admin.ts` 新文件）：

```typescript
// 知识库
createKnowledgeBase(req: CreateKnowledgeBaseRequest): Promise<KnowledgeBaseVO>
getKnowledgeBases(): Promise<KnowledgeBaseVO[]>
getKnowledgeBase(kbId: string): Promise<KnowledgeBaseVO>
updateKnowledgeBase(kbId: string, req: UpdateKnowledgeBaseRequest): Promise<void>
archiveKnowledgeBase(kbId: string): Promise<void>
restoreKnowledgeBase(kbId: string): Promise<void>

// 文档
uploadDocument(kbId: string, file: File): Promise<KnowledgeDocumentVO>
getDocuments(kbId: string): Promise<KnowledgeDocumentVO[]>
replaceDocument(kbId: string, docId: string, file: File): Promise<void>
archiveDocument(kbId: string, docId: string): Promise<void>

// 内部助手绑定
getAssistantKnowledgeBases(): Promise<KnowledgeBaseVO[]>
setAssistantKnowledgeBases(kbIds: string[]): Promise<void>
```

**类型定义扩展**（`types/admin.ts` 新文件）：

```typescript
interface KnowledgeBaseVO {
  id: string
  name: string
  description?: string
  visibility: 'SHARED' | 'PRIVATE'
  status: 'ACTIVE' | 'ARCHIVED'
  documentCount?: number
  createdAt: string
  updatedAt: string
}

interface KnowledgeDocumentVO {
  id: string
  knowledgeBaseId: string
  filename: string
  originalFilename: string
  mimeType: string
  sizeBytes: number
  parseStatus: 'PENDING' | 'PARSING' | 'INDEXED' | 'FAILED'
  createdAt: string
  updatedAt: string
}
```

**侧边栏改造**：
- [ ] `SideMenu.tsx`：ADMIN 角色用户底部显示"后台管理"入口按钮，点击跳转 `/admin`
- [ ] 后台页面使用独立的 `AdminLayout`，不复用聊天侧边栏

**路由守卫**：
- [ ] 新增 `AdminRouteGuard` 组件：校验 `currentUser.role === 'ADMIN'`，否则重定向到 `/chat`
- [ ] `/admin/*` 路由统一包裹在 guard 内

**状态管理**：
- [ ] 不新增全局 Context，后台页面用组件级 state + SWR/React Query 或手动 fetch
- [ ] 原因：后台页面访问频率低，不需要跨组件共享知识库列表状态

**Phase 1C 前端任务清单**：

- [ ] 新增 `api/admin.ts`，实现知识库、文档、助手绑定 API 调用
- [ ] 新增 `types/admin.ts`，定义 KnowledgeBaseVO、KnowledgeDocumentVO 等类型
- [ ] 新增 `AdminLayout` + `AdminSideNav`，后台独立布局
- [ ] 新增 `AdminRouteGuard`，ADMIN 角色校验
- [ ] 改造 `JChatMindLayout.tsx`，把 `/admin/*` 占位页替换为真实路由
- [ ] 新增 `KnowledgeBaseListPage`：知识库列表、创建、归档、恢复
- [ ] 新增 `KnowledgeBaseDetailPage`：文档列表、上传、替换、归档、解析状态展示
- [ ] 新增 `DocumentUploadDrawer`：拖拽上传、进度条、格式校验
- [ ] 新增 `AssistantSettingsPage`：展示当前绑定的知识库，支持增删绑定
- [ ] 改造 `SideMenu.tsx`：ADMIN 用户显示后台入口按钮
- [ ] 前后端联调：知识库 CRUD、文档上传/解析状态轮询、助手绑定

#### 12.F.3 Phase 2C 前端任务

> Phase 2A / 2B 以纯后端为主，前端主要在 Phase 2C 收口管理面与联调。

**路由扩展**：

```
/admin/intent-tree              # 意图树可视化管理
```

**新增页面与组件**：

| 组件 | 说明 |
|------|------|
| `IntentTreePage` | 意图树管理主页 |
| `IntentTreeViewer` | 树形可视化（Ant Design Tree 或自定义树组件） |
| `IntentNodeEditDrawer` | 节点编辑抽屉：名称、描述、examples、kind、绑定知识库 |
| `IntentKnowledgeBaseBindPanel` | 节点绑定知识库面板（带搜索的多选列表） |

**API 层扩展**（追加到 `api/admin.ts`）：

```typescript
// 意图树
getIntentTree(): Promise<IntentTreeVO>
createIntentNode(req: CreateIntentNodeRequest): Promise<IntentNodeVO>
updateIntentNode(nodeId: string, req: UpdateIntentNodeRequest): Promise<void>
deleteIntentNode(nodeId: string): Promise<void>
setIntentNodeKnowledgeBases(nodeId: string, kbIds: string[]): Promise<void>
publishIntentTreeSnapshot(): Promise<PublishIntentTreeResponse>
listIntentVersions(): Promise<IntentVersionVO[]>
switchActiveIntentVersion(version: number): Promise<void>
```

**交互设计要点**：
- 树形结构展示三级：DOMAIN → CATEGORY → TOPIC
- 每个节点支持：编辑、排序（拖拽或上下箭头）、启停（开关）、删除
- TOPIC 节点（叶子）显示 kind 标签（KB / TOOL / SYSTEM）和已绑定知识库数量
- 节点编辑抽屉里 kind=KB 时显示知识库绑定面板
- 页面顶部工具栏提供“发布快照”“切换版本”入口

**聊天侧改造**：
- [ ] 意图不明追问与编排层 `SYSTEM` 直答时，前端正常展示 assistant 消息（不需要特殊 UI，统一走现有 SSE 流）
- [ ] 后续可选：聊天区展示意图识别路径标签（如"HR > 薪酬 > 报销制度"），但不阻塞 Phase 2

**Phase 2C 前端任务清单**：

- [ ] 新增意图树相关类型定义（IntentTreeVO、IntentNodeVO、CreateIntentNodeRequest 等）
- [ ] `api/admin.ts` 追加意图树管理 API
- [ ] `AdminSideNav` 加"意图树管理"导航项
- [ ] 新增 `IntentTreePage`：树形可视化 + 顶部工具栏（添加根节点、发布快照、切换版本）
- [ ] 新增 `IntentTreeViewer`：三级树展示，节点带 kind 标签和知识库数量
- [ ] 新增 `IntentNodeEditDrawer`：节点属性编辑 + examples 多行输入
- [ ] 新增 `IntentKnowledgeBaseBindPanel`：kind=KB 时的知识库多选绑定
- [ ] 节点排序支持（拖拽或上下按钮）
- [ ] 节点启停开关
- [ ] 前后端联调：意图树 CRUD、知识库绑定、快照发布、版本切换
- [ ] 聊天页联调：确认编排层直答与 Agent 回复都通过同一 SSE 消息流实时显示

#### 12.F.4 Phase 4 前端任务

**聊天区引用溯源展示**：

| 组件 | 说明 |
|------|------|
| `CitationInlineTag` | 消息正文中的 `[1]` `[2]` 内联标签，可点击/hover |
| `CitationSourcePanel` | 消息底部来源区：文档名、章节路径、命中片段预览 |

**交互设计**：
```
┌───────────────────────────────────────────────┐
│  根据《员工手册》第3章 [1]，年假天数根据工龄    │
│  计算。具体标准参见《薪酬福利制度》 [2]。        │
│                                               │
│  ┌─ 来源 ────────────────────────────────────┐│
│  │ [1] 员工手册.pdf > 第三章 休假制度          ││
│  │     "...工龄满1年享受5天年假..."            ││
│  │ [2] 薪酬福利制度.docx > 4.2 年假标准       ││
│  │     "...年假天数按以下标准执行..."           ││
│  └────────────────────────────────────────────┘│
└───────────────────────────────────────────────┘
```

- `[N]` 内联标签 hover 时弹出来源摘要 tooltip
- 点击 `[N]` 滚动到底部来源区对应条目
- 来源区默认折叠，有引用时自动展开
- 无引用时不显示来源区

**助手模板页面**：

| 组件 | 说明 |
|------|------|
| `AgentTemplatePage` | 模板列表（卡片式展示 4 个预置模板） |
| `TemplateInitDialog` | 确认初始化对话框：预览模板内容，一键初始化系统助手 |

**路由扩展**：

```
/admin/templates                # 助手模板列表
```

**Phase 4 前端任务清单**：

- [ ] 新增引用相关类型定义（CitationVO 等），约定 SSE / 消息 metadata 中的引用数据格式
- [ ] 改造 `AgentChatHistory.tsx`：解析消息内容中的 `[N]` 标记，渲染为 `CitationInlineTag`
- [ ] 新增 `CitationSourcePanel`：消息底部来源区展示
- [ ] 引用交互：hover tooltip、点击滚动、折叠/展开
- [ ] 无引用时降级展示（不显示来源区）
- [ ] `api/admin.ts` 追加模板 API（列表、从模板初始化助手）
- [ ] 新增 `AgentTemplatePage`：4 个预置模板卡片展示
- [ ] 新增 `TemplateInitDialog`：预览 + 确认初始化
- [ ] `AdminSideNav` 加"助手模板"导航项
- [ ] 前后端联调：引用展示、模板初始化

#### 12.F.5 前端公共改进（跨 Phase）

- [ ] 清理 `useAgents` hook：Phase 0 已去掉用户侧入口，但 hook 和 `AgentTabContent` 文件仍存在，可安全删除
- [ ] 清理 `api.ts` 中残留的 Agent CRUD 函数（`getAgents`、`createAgent`、`updateAgent`、`deleteAgent`）
- [ ] 统一错误提示：当前 API 错误处理分散，考虑抽一个 `useApiError` hook 或全局 message provider
- [ ] API 基地址从硬编码 `http://localhost:8080/api` 改为环境变量 `VITE_API_BASE_URL`
- [ ] 考虑引入轻量数据获取库（如 SWR 或 TanStack Query）用于后台管理页面，避免手写 loading/error/refetch 样板代码

### 12.6 公共任务

- [ ] 补充配置项文档与 `.env.example`
- [ ] 增加每个 Phase 的回滚方案说明
- [ ] 为新增表和核心查询补索引设计
- [ ] 统一异常码与错误提示文案
- [ ] 统一日志字段：`traceId`、`agentId`、`sessionId`、`intentId`
- [ ] 规划一次完整联调回归：注册登录、知识库上传、意图路由、会话摘要、引用展示

### 12.7 建议里程碑

- [ ] 里程碑 M0：基线文档、安全日志与单助手入口收敛完成
- [ ] 里程碑 M1：知识库脱离 session，可完成独立上传、入库、检索
- [ ] 里程碑 M2：RBAC 与单助手后台绑定可用，管理后台可配置知识库绑定关系
- [ ] 里程碑 M3：分层意图路由可用，追问策略与问题重写联通
- [ ] 里程碑 M4：增量摘要上线，长对话 token 明显下降
- [ ] 里程碑 M5：模板创建与引用展示上线，形成完整企业场景闭环
