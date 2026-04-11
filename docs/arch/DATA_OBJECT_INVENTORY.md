# ChatAgent 数据对象全量清单与简化评估

> 统计日期：2026-04-12
> 范围：`bootstrap/src/main/java` 下全部 DTO / VO / Response / Request
> 总量：**原 101 → 当前 82 个在库对象**（DTO 21 + VO 23 + Response 18 + Request 20）
> 说明：DTO 实际文件 20 个 `*DTO.java`，另加 `JwtClaims.java`（无 DTO 后缀）共 21 条；Response 18 个含 1 个协议层 `McpJsonRpcResponse`（非 API），API Response 实际 17 个；Phase 9 净删 19 类（1 VO + 13 Response + 5 Request）
> Phase 9 已完成：
> - 9-α：删除 `McpToolReferenceVO`（1 VO），`IntentVersionVO` 改为 record
> - 9-β-1：三批累计删除 13 个薄 Response（Agent 2 + Session 3 + Message 1 + Intent 2 + KnowledgeBase 1 + MCP list 1 + File list 1 + Doc list 1 + Knowledge 1）
> - 9-β-2：5 对 Create/Update Request 合并为 5 个 UpsertRequest（净删 5 个 Request 类）
> - 9-β-3：漏网复核完成，零候选。AgentFacadeService 无 HTTP consumer 已标记

---

## 1. DTO（数据传输对象 / 持久化边界）— 21 个

DTO 是 MyBatis 直接映射的对象，承担 `DB Row → DTO` 的转换，是持久化层的唯一数据出口。

| # | 类名 | 说明 | 可简化 | 原因 |
|---|------|------|--------|------|
| 1 | `ChatSessionDTO` | 聊天会话：id, userId, agentId, title, metadata, createdAt/updatedAt | 否 | 持久化核心对象，字段均有业务用途；内部 MetaData 类为空但预留给未来扩展 |
| 2 | `JwtClaims` | JWT 载荷：userId, username, role | 否 | 纯值对象，仅 3 字段，无冗余 |
| 3 | `ChatMessageDTO` | 聊天消息：id, sessionId, turnId, role(RoleType), content, metadata, seqNo, createdAt/updatedAt | 否 | 含内部 MetaData 类（toolResponse/toolCalls/citations）和 RoleType 枚举，职责完整 |
| 4 | `UserDTO` | 用户账户：id, username, passwordHash, role, avatar, status, deleted, createdAt/updatedAt | 否 | 包含 passwordHash 等敏感字段，是持久化完整表示 |
| 5 | `UserProfileDTO` | 用户画像：userId, summary, status, createdAt/updatedAt | 否 | 仅 5 字段，已是最小形态 |
| 6 | `AgentDTO` | Agent 配置：id, userId, name, description, systemPrompt, model(ModelType), allowedTools, chatOptions, activeIntentVersion, createdAt/updatedAt | 否 | 含内部 ModelType 枚举和 ChatOptions 类，是 Agent 领域完整数据模型 |
| 7 | `McpToolReferenceDTO` | MCP 工具引用：referenceType, referenceId, referenceName, referencePath | 否 | 已是 Phase 9-α 瘦身后最简形态（替代了原 McpToolReferenceVO） |
| 8 | `ChatSessionFileDTO` | 会话附件：id, sessionId, filename, originalFilename, mimeType, sizeBytes, storagePath, status, parseStatus, metadata, createdAt/updatedAt | 否 | 文件全量信息，storagePath/metadata 仅后端使用但不暴露给前端 |
| 9 | `McpServerDTO` | MCP 服务器配置：19 个字段（含 encryptedCredentials, credentialKeyVersion 等敏感字段） | 否 | 完整的服务器配置模型，加密凭证字段不暴露给前端 |
| 10 | `McpToolCatalogDTO` | MCP 工具目录：id, serverId, remoteOriginalName, toolDescription, exposedModelName, schemaJson, schemaHash, status, deletedAt, createdAt/updatedAt, lastSyncedAt | 否 | 缓存远端工具描述，schemaJson/schemaHash 用于变更检测 |
| 11 | `McpAlertEventDTO` | MCP 告警事件：id, serverId, serverSlug, toolName, alertType, severity, status, summary, detailsJson, resolvedAt, createdAt/updatedAt | 否 | 告警全量数据，含 3 个枚举分类 |
| 12 | `ChatSessionSummaryDTO` | 会话滚动摘要：sessionId, lastSeqNo, summary, anchoredEntities(Map), anchoredEntitiesJson(String), version, createdAt/updatedAt | 否 | 双字段模式（rich Map + raw JSON）因 MyBatis 映射需要保留 |
| 13 | `IntentNodeDTO` | 意图树节点：18 个字段（含 examples列表, intentKind, scopePolicy, allowedTools 等） | 否 | 意图树完整模型，字段均有业务语义 |
| 14 | `FileChunkDTO` | 文件切块：id, sessionFileId, chunkIndex, content, tokenCount, metadata, enabled, createdAt/updatedAt | 否 | RAG 切块最小形态 |
| 15 | `AssistantTemplateDTO` | 助手模板：含双字段模式（model/modelValue, allowedTools/allowedToolsJson, chatOptions/chatOptionsJson, intentTree/intentTreeJson）+ 内部 IntentTreeNodeTemplateDTO | 否 | 双字段模式为 MyBatis 所需；内部类表达模板中的意图树结构 |
| 16 | `AgentKnowledgeBaseDTO` | Agent-知识库绑定：agentId, knowledgeBaseId, createdAt | 否 | 仅 3 字段关联表 DTO，已最简 |
| 17 | `KnowledgeBaseDTO` | 知识库：id, createdBy, name, description, visibility, status, metadata, createdAt/updatedAt | 否 | 知识库完整信息 |
| 18 | `KnowledgeDocumentDTO` | 知识库文档：16 个字段（含 storagePath, contentHash, failedReason, indexedAt, retryCount, metadata） | 否 | 文档全生命周期数据 |
| 19 | `KnowledgeChunkDTO` | 知识库切块：id, knowledgeDocumentId, chunkIndex, content, tokenCount, metadata, enabled, createdAt/updatedAt | 否 | RAG 检索切块最小形态 |
| 20 | `KnowledgeDocumentEnhancementDTO` | 文档增强信号：keywords列表, questions列表, metadata(Map) + createdAt/updatedAt | 否 | 增强 metadata 用 Map 存储，与 DB 的 JSON String 类型不同，保留 Entity 转换层 |
| 21 | `IntentKnowledgeBaseDTO` | 意图-知识库绑定：id, intentNodeId, knowledgeBaseId, createdAt | 否 | 仅 4 字段关联表 DTO，已最简 |

**DTO 小结**：21 个 DTO 全部保留。Phase 6A 已将 15 个 Entity 合并进 DTO，当前 DTO 层已是最小持久化边界。双字段模式（rich + raw JSON）是 MyBatis 映射的必要设计。

---

## 2. VO（视图对象 / 展示层）— 23 个当前在库对象

VO 是 Facade → Controller → Response 的数据载体，负责向前端暴露"该暴露的"字段。

### 2.0 删除准则（本次 review 后收紧）

**默认原则**：不要轻易删除 VO。只有当 **VO 和 DTO 字段完全一样** 时，才允许把删除 VO 作为默认方案。

这里的“完全一样”包括：

1. 字段名一致
2. 字段类型一致
3. 可空性 / 序列化行为一致
4. 无额外计算、脱敏、聚合、裁剪逻辑

只要 VO 是 DTO 的**字段子集**、DTO 含内部/敏感字段、VO 含计算字段，或两者序列化结果可能不同，就默认**保留 VO**。

### 2.1 已收敛（Phase 9-α）

| # | 类名 | 说明 | 状态 | 处理方式 |
|---|------|------|------|----------|
| 1 | `McpToolReferenceVO` | MCP 工具引用视图 | ✅ 已删除（历史项） | 字段与 DTO 100% 一致，直接用 `McpToolReferenceDTO` |
| 2 | `IntentVersionVO` | 意图版本（version + active） | ✅ 已简化 | 从 @Data class 改为 Java record；无对应 DTO，不属于“删 VO → 用 DTO” |

### 2.2 经 review 后改为保留（8 个）

以下 VO 之前容易被误判为“薄 VO”，但它们都**不满足**“VO 与 DTO 字段完全一样”的删除前提。当前结论是：**默认保留，不作为 Phase 9 的主删除目标**。

| # | 类名 | 原因 | 结论 |
|---|------|------|------|
| 1 | `ChatSessionFileVO` | DTO 多 `sessionId / storagePath / metadata` | 保留；DTO 直出会扩大暴露面 |
| 2 | `ChatMessageVO` | DTO 多 `createdAt / updatedAt` | 保留；不是字段完全一致 |
| 3 | `ChatSessionVO` | DTO 多 `userId / metadata / createdAt / updatedAt` | 保留；DTO 明显更宽 |
| 4 | `AgentVO` | DTO 多 `userId / activeIntentVersion / createdAt / updatedAt` | 保留；VO 是裁剪后的展示模型 |
| 5 | `KnowledgeBaseVO` | DTO 多 `createdBy / metadata` | 保留；VO 仍有展示裁剪价值 |
| 6 | `KnowledgeDocumentVO` | DTO 多 `storagePath / contentHash / failedReason / indexedAt / retryCount / metadata` | 保留；DTO 明显包含内部字段 |
| 7 | `McpServerVO` | DTO 多敏感字段 `encryptedCredentials / credentialKeyVersion / deletedAt`，VO 还多 `unresolvedReferenceCount` 计算字段 | 保留；绝不应直接用 DTO 替换 |
| 8 | `McpToolCatalogVO` | DTO 多 `schemaJson / schemaHash / deletedAt` | 保留；当前 `GetMcpServerResponse` 仍以 VO 作为安全展示边界 |

### 2.3 必须保留（14 个）

| # | 类名 | 说明 | 保留原因 |
|---|------|------|----------|
| 1 | `McpDiscoveredToolVO` | 远端工具发现结果（4 字段） | 来源是 McpRemoteToolDescriptor（record），exposedModelName 经过 normalizeToolName() 计算 |
| 2 | `ChatRoutingCandidateVO` | 聊天路由候选（20 字段） | 来源是 ChatRoutingProperties + 运行时状态合并，含 configured/effective 双层字段和熔断指标 |
| 3 | `IntentNodeVO` | 意图节点视图（16 字段） | DTO 无 knowledgeBaseIds，此字段从关联表查询填充 |
| 4 | `AssistantTemplateVO` | 助手模板视图（12 字段 + 内部 IntentTreeNodeTemplateVO） | DTO 有双字段（model+modelValue），VO 只暴露 rich 字段；内部类做 enum→String 转换 |
| 5 | `AdminUserVO` | 管理员用户视图（7 字段） | 来源是 UserDTO/User 实体组合，无 1:1 DTO 映射 |
| 6 | `LoginUserVO` | 登录用户视图（5 字段） | 来源是 JWT claims + UserDTO，无直接 DTO |
| 7 | `UserProfileVO` | 用户画像视图（3 字段） | 仅 userId/summary/updatedAt，不完整子集 |
| 8 | `DashboardOverviewVO` | 概览仪表盘（含内部 KPI 嵌套结构） | 纯聚合视图，含 DashboardOverviewGroupVO → DashboardOverviewKpiVO 三级嵌套 |
| 9 | `DashboardTrendsVO` | 趋势图表（含 series/point 嵌套） | 纯聚合视图，含 DashboardSeriesVO → DashboardPointVO 嵌套 |
| 10 | `DashboardMcpAlertVO` | MCP 告警视图（10 字段） | 来源 McpAlertEventDTO 但额外填充 serverSlug/toolName（关联查询） |
| 11 | `DashboardMcpAlertsVO` | MCP 告警集合（2 字段） | 包装器：openAlertCount + List<DashboardMcpAlertVO> |
| 12 | `DashboardMcpPerformanceVO` | MCP 性能指标 | 纯聚合视图，嵌套 DashboardMcpServerMetricVO 列表 |
| 13 | `DashboardPerformanceVO` | 全局性能仪表盘 | 纯聚合视图，嵌套 DashboardMcpPerformanceVO |
| 14 | `DashboardMcpServerMetricVO` | MCP 服务器级指标（16 字段） | DashboardMcpPerformanceVO 的内部组件，从 McpServerDTO + 调用统计聚合 |

**VO 小结**：23 个当前在库 VO 中，`IntentVersionVO` 已在 9-α 简化为 record；其余 22 个当前都应保留。历史上另有 1 个 `McpToolReferenceVO` 已删除，因为它与 DTO 字段完全一致。

---

## 3. Response（HTTP 响应包装）— 当前 18 个文件（API 层 17 + 协议层 1）

Response 是 Controller 层的返回类型，被 `ApiResponse<T>` 包装后序列化为 JSON。另有 1 个 MCP JSON-RPC 协议层 Response（`McpJsonRpcResponse`），不参与 API 层简化。

### 3.1 已完成（Phase 9-β-1 三批累计删除 13 个薄 Response）

以下 13 个 Response 已在 2026-04-12 的 Phase 9-β-1 中删除，Controller/Facade 直接改为返回 `VO` / `VO[]` / `List<VO>` / `String` / `Integer`，不再保留额外一层壳：

| # | 已删除类名 | 当前替代 | 批次 |
|---|-----------|----------|------|
| 1 | `GetAssistantTemplateResponse` | `ApiResponse<AssistantTemplateVO>` | 第一批 |
| 2 | `GetAssistantTemplatesResponse` | `ApiResponse<List<AssistantTemplateVO>>` | 第一批 |
| 3 | `GetAssistantKnowledgeBasesResponse` | `ApiResponse<KnowledgeBaseVO[]>` | 第一批 |
| 4 | `GetKnowledgeBaseResponse` | `ApiResponse<KnowledgeBaseVO>` | 第一批 |
| 5 | `GetKnowledgeBasesResponse` | `ApiResponse<KnowledgeBaseVO[]>` | 第一批 |
| 6 | `ListMcpServersResponse` | `ApiResponse<List<McpServerVO>>` | 第二批 |
| 7 | `GetChatSessionFilesResponse` | `ApiResponse<ChatSessionFileVO[]>` | 第二批 |
| 8 | `GetKnowledgeDocumentsResponse` | `ApiResponse<KnowledgeDocumentVO[]>` | 第二批 |
| 9 | `CreateAgentResponse` | `ApiResponse<String>` | 第三批 |
| 10 | `GetAgentsResponse` | `ApiResponse<List<AgentVO>>` | 第三批 |
| 11 | `GetIntentVersionsResponse` | `ApiResponse<List<IntentVersionVO>>` | 第三批 |
| 12 | `PublishIntentTreeResponse` | `ApiResponse<Integer>` | 第三批 |
| 13 | `CreateKnowledgeBaseResponse` | `ApiResponse<String>` | 第三批 |

> 另有 2 个多字段薄包装经 review 降级为保留：`CreateChatMessageResponse`（2 字段：chatMessageId + turnId）和 `CreateAdminUserResponse`（3 字段：userId + username + initialPassword）。

### 3.2 保留的 API Response（17 个）+ 协议层 Response（1 个）

以下 17 个 API Response 均有多字段结构或语义保留价值：

| # | 类名 | 字段 | 保留原因 |
|---|------|------|----------|
| 1 | `GetAdminUsersResponse` | users: AdminUserVO[], page, size, total | 含分页元数据 |
| 2 | `GetIntentTreeResponse` | activeVersion, versions, nodes | 多字段组合 |
| 3 | `GetMcpServerResponse` | server + catalogTools | 服务器+目录组合 |
| 4 | `GetChatRoutingStateResponse` | 8 配置字段 + candidates | 路由状态全量 |
| 5 | `DeleteMcpServerResponse` | deleted, softDeleted, referenceCount 等 | 删除操作多维结果 |
| 6 | `LoginResponse` | accessToken, refreshToken(@JsonIgnore), userId 等 | 登录全量响应 |
| 7 | `GetMqOutboxRetryResponse` | 7 统计字段 + records | MQ 管理视图 |
| 8 | `UploadChatSessionFileResponse` | sessionFileId, sessionId | 上传双 ID |
| 9 | `InitializeAssistantFromTemplateResponse` | templateId, activeIntentVersion | 模板初始化结果 |
| 10 | `CreateIntentNodeResponse` | nodeId | 创建语义明确 |
| 11 | `UploadKnowledgeDocumentResponse` | knowledgeBaseId, documentId | 上传双 ID |
| 12 | `ReplayDlqMessagesResponse` | replayedCount, remainingDlqDepth, resetRetryCount | DLQ 重放结果 |
| 13 | `ResetAdminUserPasswordResponse` | userId, newPassword | 密码重置结果 |
| 14 | `SyncMcpToolCatalogResponse` | 13 字段 | 同步全量结果 |
| 15 | `TestMcpServerResponse` | 10 字段 | 测试连接全量结果 |
| 16 | `CreateChatMessageResponse` | chatMessageId, turnId | 2 字段，类型安全 |
| 17 | `CreateAdminUserResponse` | userId, username, initialPassword | 3 字段，初始密码一次返回 |

协议层（非 API）：

| # | 类名 | 说明 |
|---|------|------|
| 1 | `McpJsonRpcResponse` | MCP JSON-RPC 协议响应，不参与 API 层简化 |

**Response 小结**：当前 18 个 Response 文件中，17 个为 API 层（均保留），1 个为 MCP 协议层；另有 13 个薄 API Response 已在 9-β-1 删除。

| # | 类名 | 字段 | 保留原因 |
|---|------|------|----------|
| 1 | `GetAdminUsersResponse` | users: AdminUserVO[], page, size, total | 含分页元数据，结构合理 |
| 2 | `GetIntentTreeResponse` | activeVersion: Integer, versions: List\<IntentVersionVO\>, nodes: List\<IntentNodeVO\> | 多字段组合，表达完整意图树 |
| 3 | `GetMcpServerResponse` | server: McpServerVO, catalogTools: List\<McpToolCatalogVO\> | 服务器 + 工具目录组合 |
| 4 | `GetChatRoutingStateResponse` | 8 个配置字段 + candidates: ChatRoutingCandidateVO[] | 路由状态全量数据 |
| 5 | `DeleteMcpServerResponse` | deleted, softDeleted, activeReferenceCount, unresolvedReferenceCount, references | 删除操作多维度结果 |
| 6 | `LoginResponse` | accessToken, refreshToken(@JsonIgnore), userId, username, role, avatar, status | 登录全量响应 |
| 7 | `GetMqOutboxRetryResponse` | 7 个统计字段 + records | MQ 管理视图 |
| 8 | `UploadChatSessionFileResponse` | sessionFileId, sessionId: String | 上传返回双 ID |
| 9 | `InitializeAssistantFromTemplateResponse` | templateId: String, activeIntentVersion: Integer | 模板初始化结果 |
| 10 | `CreateIntentNodeResponse` | nodeId: String | 创建场景语义明确 |
| 11 | `UploadKnowledgeDocumentResponse` | knowledgeBaseId, documentId: String | 上传返回双 ID |
| 12 | `ReplayDlqMessagesResponse` | replayedCount, remainingDlqDepth, resetRetryCount | DLQ 重放操作结果 |
| 13 | `ResetAdminUserPasswordResponse` | userId, newPassword: String | 密码重置结果 |
| 14 | `SyncMcpToolCatalogResponse` | 13 个字段 | 同步操作全量结果 |
| 15 | `TestMcpServerResponse` | 10 个字段 | 测试连接全量结果 |
| 16 | `CreateChatMessageResponse` | chatMessageId, turnId: String | 2 字段，类型安全优于 Map |
| 17 | `CreateAdminUserResponse` | userId, username, initialPassword: String | 3 字段，初始密码仅返回一次 |

---

## 4. Request（HTTP 请求体）— 当前 20 个（原 25 个，9-β-2 合并 5 对 → 净删 5 个）

Request 是 Controller 接收的前端入参。

### 4.1 已完成（Phase 9-β-2：5 对 Create/Update 合并为 UpsertRequest）

以下 5 对 Create/Update 字段完全一致的 Request 已合并为单个 `UpsertXxxRequest`，Controller 的 POST / PATCH 端点共用同一类型：

| 对 | 原 Create 类 | 原 Update 类 | 合并为 | 共享字段数 |
|----|----------|----------|--------|-----------|
| 1 | `CreateAgentRequest` | `UpdateAgentRequest` | `UpsertAgentRequest` | 6 |
| 2 | `CreateAssistantTemplateRequest` | `UpdateAssistantTemplateRequest` | `UpsertAssistantTemplateRequest` | 8 |
| 3 | `CreateIntentNodeRequest` | `UpdateIntentNodeRequest` | `UpsertIntentNodeRequest` | 11 |
| 4 | `CreateKnowledgeBaseRequest` | `UpdateKnowledgeBaseRequest` | `UpsertKnowledgeBaseRequest` | 2 |
| 5 | `CreateMcpServerRequest` | `UpdateMcpServerRequest` | `UpsertMcpServerRequest` | 7 |

### 4.2 Create/Update 有差异的对（3 对 = 6 个）

| 对 | Create 类 | Update 类 | 差异 | 保留原因 |
|----|----------|----------|------|----------|
| 1 | `CreateAdminUserRequest` (3 字段) | `UpdateAdminUserRequest` (2 字段) | Create 多 username | Update 不允许改 username，差异合理 |
| 2 | `CreateChatSessionRequest` (1 字段) | `UpdateChatSessionRequest` (1 字段) | 相同字段 title | 仅 1 字段，合并收益极低 |
| 3 | `CreateChatMessageRequest` (5 字段) | `UpdateChatMessageRequest` (2 字段) | Create 多 sessionId/turnId/role | Create 需指定会话和角色，Update 仅改内容 |

### 4.3 特殊配对（1 对）

| 对 | 类 1 | 类 2 | 说明 | 可简化 | 原因 |
|----|------|------|------|--------|------|
| 1 | `LoginRequest` (username, password) | `RegisterRequest` (username, password) | 字段完全相同但语义不同 | **否** | 登录和注册是不同 API 端点，分开有助 API 语义清晰和未来差异化验证 |

### 4.4 独立 Request（无配对，9 个）

| # | 类名 | 字段 | 可简化 | 原因 |
|---|------|------|--------|------|
| 1 | `InitializeAssistantFromTemplateRequest` | knowledgeBaseIds: List\<String\> | 否 | 独立操作，仅 1 字段 |
| 2 | `SetIntentNodeKnowledgeBasesRequest` | knowledgeBaseIds: List\<String\> | 否 | 独立 set 操作 |
| 3 | `SetAssistantKnowledgeBasesRequest` | knowledgeBaseIds: String[] | 否 | 独立 set 操作（注意用 String[] 而非 List，风格不一致但功能等价） |
| 4 | `ReplayDlqMessagesRequest` | limit: Integer, resetRetryCount: Boolean | 否 | MQ 管理操作 |
| 5 | `UpdateAdminUserStatusRequest` | status: String | 否 | 状态切换操作 |
| 6 | `UpdateChatRoutingCandidateOverrideRequest` | 6 字段（candidateId, enabled, priority, supportsThinking, thinkingStrategy, thinkingModel） | 否 | 路由覆盖操作，字段语义独立 |
| 7 | `UpdateUserProfileRequest` | summary: String | 否 | 仅 1 字段 |
| 8 | `CreateChatMessageRequest` | sessionId, turnId, role, content, metadata (5 字段) | 否 | 创建消息需指定会话上下文 |
| 9 | `CreateChatSessionRequest` | title: String | 否 | 仅 1 字段 |

**Request 小结**：原 25 个 Request 中，5 对（10 个）已在 9-β-2 合并为 5 个 UpsertRequest，净删 5 个；剩余 20 个保持现状（含 5 Upsert + 6 差异对 + 2 特殊对 + 7 独立）。

---

## 5. 总结

### 按类别统计（与源树文件数对齐）

| 类别 | 原总数 | Phase 9 变化 | 当前在库 | 说明 |
|------|--------|-------------|---------|------|
| DTO | 21 | 0 | 21（20 `*DTO.java` + JwtClaims） | 全部保留 |
| VO | 24 | −1（McpToolReferenceVO 删除） | 23 | IntentVersionVO 改 record 但文件仍在 |
| Response | 31 | −13（薄包装删除） | 18（17 API + 1 协议 McpJsonRpcResponse） | |
| Request | 25 | −5（5 对合并为 5 Upsert，净删 5） | 20 | |
| **合计** | **101** | **−19** | **82** | API 层数据对象 81 |

### Phase 9 执行结果

| 批次 | 范围 | 结果 |
|------|------|------|
| 9-α | McpToolReferenceVO 删除；IntentVersionVO → record | ✅ 完成 |
| 9-β-1 | 三批删除 13 个薄 Response → Controller 直接返回 ApiResponse\<VO/String/Integer\> | ✅ 完成 |
| 9-β-2 | 5 对 Create/Update → 5 个 UpsertRequest | ✅ 完成 |
| 9-β-3 | 漏网复核：零 VO 候选。AgentFacadeService 无 HTTP consumer 已标记 | ✅ 完成 |

### 设计观察

1. **无验证注解**：20 个 Request 类全部没有 `@NotNull` / `@NotBlank` / `@Size` 等 javax.validation 注解，所有字段隐式 nullable。这是潜在的质量改进点（独立于重构计划）。
2. **数组 vs List 不一致**：Response 中列表字段混用 `VO[]`（多数）和 `List<VO>`（intent/template/MCP 模块），Request 中 `SetAssistantKnowledgeBasesRequest` 用 `String[]` 而其他用 `List<String>`。
3. **DTO 双字段模式**：`AssistantTemplateDTO`、`ChatSessionSummaryDTO` 等保留了 rich（Java 类型）+ raw（JSON String）双字段，是 MyBatis 映射的技术约束，非冗余。
4. **Dashboard VO 嵌套层级深**：`DashboardPerformanceVO` → `DashboardMcpPerformanceVO` → `DashboardMcpServerMetricVO` 三级嵌套，但每级有独立聚合语义，保留合理。
5. **VO 删除阈值应显著高于 Response 删除阈值**：薄 Response 常常只是 HTTP 包装层；而 VO 即使只比 DTO 少 2-3 个字段，也承担了裁剪暴露面和稳定前端契约的职责。今后应默认“先删薄 Response，再评估 VO”，而不是反过来。
