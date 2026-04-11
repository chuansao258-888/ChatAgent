# ChatAgent 代码结构重构执行记录

> 分支：`reconstruct/code-reconstruction`
> 基于提交：`9613e77` (main)
> 执行日期：2026-04-11
> 参考方案：[CODE_RESTRUCTURING_PLAN.md](../plans/CODE_RESTRUCTURING_PLAN.md)

---

## 状态总览

| Phase | 内容 | 状态 | Commit | 文件变更 | 备注 |
|-------|------|------|--------|----------|------|
| 0 | 根目录清理 & Git 卫生 | ✅ 完成 | `1d71ab7` | 15 | 删除临时文件，解除 output/ 追踪 |
| 1 | MCP 工具类归位 | ✅ 完成 | `3800051` | 26 | 5 源文件 + 4 测试从 admin→mcp |
| 3 | RAG 命名统一 | ✅ 完成 | `dabfb64` | 22 | rag/service→rag/application |
| 2 | Intent 控制器 + Agent 端口归位 | ✅ 完成 | `b75c58c` | 22 | IntentTreeController + 3 Agent 端口归位 |
| 4 | 双重持久化统一 | ✅ 完成 | `83e27eb` | 9 | user/infrastructure→support/persistence |
| 7A | chat 包分裂修复 | ✅ 完成 | `ac44d53` | 4 | chat/→support/chat/ |
| 5 | 上帝类拆分 | ✅ 完成 | 多个 | ~20 | PdfPageRenderer/QualityRouter/TextExtractor + McpServerCrudHelper + DashboardOverviewAggregator |
| 6A | Entity 层消除 | ✅ 完成 | 多个 | ~80 | 15 个 Entity 合并进 DTO，6 个保留 |
| 7B | Framework 模块测试 | ✅ 完成 | `919f0a3` | 8 | 7 个测试类，41 个测试全部通过 |
| 9-α | 数据对象精简（契约不变批） | ✅ 完成 | TBD | 5 | McpToolReferenceVO 删除 + IntentVersionVO 改 record；8 个候选归入 9-β |

---

## Phase 0：根目录清理 & Git 卫生

**执行时间**: 2026-04-11
**风险**: 零

### 执行动作

#### 1. 删除根目录临时文件
- `git rm commit_msg.txt` — 42 字节临时文件
- `git rm commit_raw.txt` — 464 字节临时文件

#### 2. MCP/.venv/ 解除追踪
- **跳过** — 已在 commit `9613e77` 中完成

#### 3. 添加 output/ 到 .gitignore 并解除追踪
- `.gitignore` 添加 `output/` 规则
- `git rm -r --cached output/` — 解除约 11 个生成文件的追踪

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `git ls-files output/` 返回空 | ✅ PASS |
| `mvn compile` 通过 | ✅ PASS |

### 问题与备注
- MCP/.venv/ 解除追踪已在之前的 commit `9613e77` 完成，无需重复操作
- output/ 下有 11 个已追踪文件（生成代码和上传 PDF），已全部解除追踪

---

## Phase 1：MCP 工具类归位

**执行时间**: 2026-04-11
**风险**: 低（纯包移动，无逻辑变更）

### 执行动作

#### 1. 移动源文件（5 个）
```
admin/application/McpFeatureFlag.java           → mcp/application/
admin/application/McpCredentialCipher.java       → mcp/application/
admin/application/McpServerStatusMachine.java    → mcp/application/
admin/application/McpToolNameNormalizer.java     → mcp/application/
admin/application/McpServerReferenceInspector.java → mcp/application/
```

#### 2. 移动测试文件（4 个）
```
admin/application/McpFeatureFlagTest.java           → mcp/application/
admin/application/McpCredentialCipherTest.java       → mcp/application/
admin/application/McpServerStatusMachineTest.java    → mcp/application/
admin/application/McpToolNameNormalizerTest.java     → mcp/application/
```

#### 3. 更新 package 声明
`com.yulong.chatagent.admin.application` → `com.yulong.chatagent.mcp.application`

#### 4. 更新 import 语句
主要消费方：mcp/application（5 文件）、mcp/runtime（2 文件）、mcp/transport（1 文件）、测试文件（6+ 文件）

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |
| `grep "admin\.application\.Mcp"` 零结果 | ✅ PASS |

### 问题与备注
- 初次 sed 批量替换时，模式 `admin.application.Mcp` 过于宽泛，误将 `McpAlertService` 和 `McpServerAdminFacadeService` 的 import 也改为 `mcp.application`。这两个类未被移动。已手动恢复。
- `DashboardFacadeServiceImpl` 和 `McpServerAdminFacadeServiceImpl` 原与被移动类在同一包（admin.application），移动后需添加显式 import。

---

## Phase 3：RAG 命名统一

**执行时间**: 2026-04-11
**风险**: 低

### 执行动作

#### 1. 移动源文件（8 个）
```
rag/service/DocumentStorageService.java     → rag/application/
rag/service/RagService.java                 → rag/application/
rag/service/MarkdownParserService.java      → rag/application/
rag/service/FormattedRetrievalPrompt.java   → rag/application/
rag/service/RetrievalHitFormatter.java      → rag/application/
rag/service/impl/RagServiceImpl.java        → rag/application/
rag/service/impl/DocumentStorageServiceImpl.java → rag/application/
rag/service/impl/MarkdownParserServiceImpl.java  → rag/application/
```

#### 2. 移动测试文件（1 个）
```
test/rag/service/RetrievalHitFormatterTest.java → test/rag/application/
```

#### 3. 更新 package 声明
`com.yulong.chatagent.rag.service` / `rag.service.impl` → `com.yulong.chatagent.rag.application`

#### 4. 更新 import 语句
主要消费方：agent/tools、file/application、conversation/application、knowledge/application、rag/ingestion + 测试

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |
| 空目录 rag/service 已删除 | ✅ PASS |

### 问题与备注

已完成

---

## Phase 2：Intent 控制器 + Agent 端口归位

**执行时间**: 2026-04-11
**风险**: 低

### 执行动作

#### 2A. 移动 IntentTreeController
```
admin/controller/IntentTreeController.java → intent/controller/
```

#### 2B. 移动 Agent 端口（3 个）
```
admin/port/AgentRepository.java                → agent/port/
admin/port/AgentKnowledgeBaseRepository.java   → agent/port/
admin/port/AssistantTemplateRepository.java     → agent/port/
```

#### 3. 更新 import 语句
- AgentRepository: 9 个消费方
- AgentKnowledgeBaseRepository: 10 个消费方
- AssistantTemplateRepository: 3 个消费方

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |

### 问题与备注

已完成

---

## Phase 4：双重持久化统一

**执行时间**: 2026-04-11
**风险**: 中

### 执行动作

#### 1. 移动文件（6 个）
```
user/infrastructure/persistence/adapter/MyBatisUserRepository.java     → support/persistence/adapter/user/
user/infrastructure/persistence/adapter/MyBatisUserProfileRepository.java → support/persistence/adapter/user/
user/infrastructure/persistence/entity/User.java                        → support/persistence/entity/
user/infrastructure/persistence/entity/UserProfile.java                 → support/persistence/entity/
user/infrastructure/persistence/mapper/UserMapper.java                  → support/persistence/mapper/
user/infrastructure/persistence/mapper/UserProfileMapper.java           → support/persistence/mapper/
```

#### 2. 更新 MyBatis XML
- `resources/mapper/UserMapper.xml` — namespace + resultType
- `resources/mapper/UserProfileMapper.xml` — namespace + resultType

#### 3. 更新 import 语句
所有 `user.infrastructure.persistence.*` → `support.persistence.*`

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |
| 空目录已清理 | ✅ PASS |

### 问题与备注

已完成

---

## Phase 7A：chat 包分裂修复

**执行时间**: 2026-04-11
**风险**: 低

### 执行动作

#### 1. 移动配置类（2 个）
```
bootstrap/.../chat/ChatModelAvailability.java              → bootstrap/.../support/chat/
bootstrap/.../chat/ChatModelHttpClientTimeoutConfig.java   → bootstrap/.../support/chat/
```

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |
| 空 chat/ 目录已删除 | ✅ PASS |

### 问题与备注

已完成

---

## Phase 5：上帝类拆分

**执行时间**: 2026-04-11
**风险**: 中高

### 执行动作

#### 5A. 拆分 PdfDocumentParser（1640 行 → 1189 行）

| 新类 | 职责 | Commit |
|------|------|--------|
| PdfPageRenderer | PDF 转图片渲染（DPI 配置） | `453d957` |
| PdfQualityRouter | 路由决策、字符密度分析 | `453d957` |
| PdfPageTextExtractor | 逐页文本提取、Font 分析、结构化 Markdown 恢复 | `453d957` |
| PdfDocumentParser（瘦身） | VDP 调度管线、缓存、批处理、Segment 构建 | — |

#### 5B. 拆分 McpServerAdminFacadeServiceImpl（523 行）
| 新类 | 职责 |
|------|------|
| McpServerAdminFacadeServiceImpl（瘦身） | 核心 CRUD + VO 转换 |
| McpServerDeleteHandler | 删除编排：引用检查 → Intent 清理 → Catalog 清理 → 服务器删除 → 告警 |

#### 5C. 拆分 DashboardFacadeServiceImpl（588 行）
| 新类 | 职责 |
|------|------|
| DashboardFacadeServiceImpl（瘦身） | 顶层编排 + overview + trends + 性能聚合 |
| DashboardMcpMetricsComposer | MCP 性能指标 + 告警查询 |

**额外拆分：**
- `DashboardOverviewAggregator`（`d584a4b`）— overview KPI 聚合逻辑从 DashboardFacadeServiceImpl 提取
- `McpServerCrudHelper`（`46bf4fe`）— CRUD DTO 组装和持久化逻辑从 McpServerAdminFacadeServiceImpl 提取

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |
| `mvn test` 通过 | ✅ PASS |

### 问题与备注
- PdfDocumentParser 从 1640 行减至 1189 行，VDP 调度管线（~500 行）因与缓存、批处理紧密耦合暂保留在主类中
- McpServerAdminFacadeServiceImpl 从 528 行减少到 ~390 行
- DashboardFacadeServiceImpl 从 588 行减少到 425 行
- DashboardTrendsAggregator 因与 private enum 紧密耦合而跳过

---

## Phase 6A：Entity 层消除 — 合并进 DTO

**执行时间**: 2026-04-11
**风险**: 中

### 执行动作

将 Entity 层合并进 DTO 层，消除 Mapper → Entity → Adapter → DTO 的双重转换。每个合并按以下模式执行：
1. DTO 添加 `@NoArgsConstructor` + `@AllArgsConstructor`
2. MyBatis XML: resultMap type / parameterType 改为 DTO 类路径
3. Mapper 接口: 方法签名改用 DTO 类型
4. Adapter: 删除 `toEntity()`/`toDTO()` 方法，直接委托给 Mapper
5. 删除 Entity 文件

#### 已合并（15 个 Entity 删除）

| Entity | DTO | Commit | 备注 |
|--------|-----|--------|------|
| ChatSessionFile | ChatSessionFileDTO | `c13fee9` | 直接合并 |
| McpServer | McpServerDTO | `b7aa575` | 直接合并 |
| McpToolCatalog | McpToolCatalogDTO | `b7aa575` | 直接合并 |
| McpAlertEvent | McpAlertEventDTO | `b7aa575` | 直接合并 |
| IntentNode | IntentNodeDTO | `61be522` | 新增 JsonStringListTypeHandler |
| IntentKnowledgeBase | IntentKnowledgeBaseDTO | `61be522` | 直接合并 |
| User | UserDTO | `61be522` | 移除 UserConverter.toEntity/toDTO |
| UserProfile | UserProfileDTO | `61be522` | 直接合并 |
| AgentTemplate | AssistantTemplateDTO | `61be522` | JSON 字段用 populateRichFields/populateJsonFields |
| ChatSessionSummary | ChatSessionSummaryDTO | `61be522` + `0843d15` | anchoredEntities JSON 转换保留在 Adapter |
| FileChunk | FileChunkDTO | `61be522` | 直接合并 |
| AgentKnowledgeBase | AgentKnowledgeBaseDTO | `0c1a1bb` | 直接合并 |
| KnowledgeBase | KnowledgeBaseDTO | `0843d15` | 直接合并 |
| KnowledgeDocument | KnowledgeDocumentDTO | `0843d15` | 直接合并 |
| KnowledgeChunk | KnowledgeChunkDTO | `0843d15` | 直接合并 |

#### 保留 Entity（6 个，有合理原因）

| Entity | 保留原因 |
|--------|----------|
| Agent | Converter 做 model String↔ModelType enum、allowedTools JSON↔List、chatOptions JSON↔ChatOptions 转换 |
| ChatMessage | Converter 做 role String↔RoleType enum、metadata JSON↔MetaData 转换 |
| ChatSession | Converter 做 metadata JSON↔MetaData 转换 |
| KnowledgeDocumentEnhancement | DTO 有 List/Map 类型但 DB 存 JSON String |
| ChatTurnMetric | 无 DTO，Mapper 直接使用 Entity |
| MqOutbox | Entity 即 Port 层的领域模型，9 个文件引用 |

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |
| `mvn test` 通过（342 tests） | ✅ PASS |

---

## Phase 6B：合并薄 VO 到 Response 类

**执行时间**: 2026-04-11
**状态**: ⏭️ 已跳过

### 跳过原因

当前 Dashboard 有 4 个独立端点，各返回专属 VO：

| 端点 | VO |
|------|-----|
| `/api/admin/dashboard/overview` | DashboardOverviewVO |
| `/api/admin/dashboard/performance` | DashboardPerformanceVO |
| `/api/admin/dashboard/trends` | DashboardTrendsVO |
| `/api/admin/dashboard/mcp-alerts` | DashboardMcpAlertsVO |

合并所有 VO 到 `DashboardOverviewVO` 会：
1. 破坏前端 API 契约
2. 创建巨型响应，不利于缓存和按需加载
3. 混合关注点（KPI、性能、趋势、告警）

当前结构职责分离清晰，无需强行合并。

---

## 执行总结

**最终测试**: `mvn test` 346 tests 通过，Failures=0，Errors=0，Skipped=0

### 量化成果

| 指标 | 变化 |
|------|------|
| Entity 文件 | 21 → 6（删除 15 个） |
| PdfDocumentParser | 1640 → 1189 → 535 行（Phase 5 + Phase 8） |
| PdfVdpDispatcher | 503 → 159 行（Phase 8 第二批） |
| VDP 协作者 | 0 → 7（`PdfVdpCache` / `PdfVdpBatchPlanner` / `PdfVdpDispatcher` / `PdfSegmentAssembler` / `PdfVdpPageDispatchLoop` / `PdfVdpBatchResultNormalizer` / `PdfVdpResultSupport`） |
| Phase 8 不变量测试 | 3 条（digest 稳定 / batch 归一化 / golden 输出 + 缓存查询不变量） |
| Phase 8 golden 基线 | 已记录首次数据，报告输出 `target/phase8-baseline/golden-pdf-performance-baseline.json` |
| McpServerAdminFacadeServiceImpl | 523 → ~390 行（-25%） |
| DashboardFacadeServiceImpl | 588 → 425 行（-28%） |
| 单实现接口（Phase 11） | 收敛 4 个应用服务接口；完成全量评估并形成保留/删除台账 |
| 数据对象精简（Phase 9-α） | 删除 McpToolReferenceVO（→ DTO 直用）；IntentVersionVO 改为 record；全量评估 24 个 VO 形成 10/14 保留台账 |
| Framework 测试 | 0 → 7 个测试类，41 个测试用例 |
| Git 追踪体积 | 解除 MCP/.venv/（96MB）+ output/ 追踪 |

### 提交历史

```
22a8cb4 docs: update restructuring execution record with Phase 5 and 6A results
0247281 fix: update ChatSessionSummaryRepositoryTest to use DTO instead of deleted entity
0843d15 refactor(persistence): merge Knowledge entities into DTOs, delete unused entities
0c1a1bb refactor(persistence): merge AgentKnowledgeBase entity into DTO
61be522 refactor(persistence): merge Intent, User, UserProfile, AgentTemplate, ChatSessionSummary, FileChunk entities into DTOs
b7aa575 refactor(persistence): merge McpServer, McpToolCatalog, McpAlertEvent entities into DTOs
c13fee9 refactor(persistence): merge ChatSessionFile entity into ChatSessionFileDTO
453d957 refactor(pdf): split PdfDocumentParser into PdfPageRenderer, PdfQualityRouter, and PdfPageTextExtractor
d584a4b refactor(dashboard): extract DashboardOverviewAggregator from DashboardFacadeServiceImpl
46bf4fe refactor(mcp): extract McpServerCrudHelper from McpServerAdminFacadeServiceImpl
919f0a3 test(framework): add baseline test coverage for framework module
ac44d53 refactor(chat): move chat config classes to support/chat package
83e27eb refactor(persistence): unify user persistence into support/persistence
b75c58c refactor: move IntentTreeController to intent and Agent ports to agent package
dabfb64 refactor(rag): rename rag/service to rag/application
3800051 refactor(mcp): move MCP utility classes from admin to mcp package
1d71ab7 chore: remove root temp files and untrack output/ directory
```

---

## Phase 7B：Framework 模块测试

**执行时间**: 2026-04-11
**风险**: 低

### 执行动作

#### 新增 7 个测试类
| 测试类 | 目标 | 优先级 |
|--------|------|--------|
| ApiResponseTest | ApiResponse | 高 |
| BizExceptionTest | BizException, ClientException, ServiceException | 高 |
| GlobalExceptionHandlerTest | GlobalExceptionHandler | 高 |
| UserContextTest | UserContext (ThreadLocal) | 高 |
| TraceContextTest | TraceContext (ThreadLocal) | 中 |
| TraceIdFilterTest | TraceIdFilter | 中 |
| SseEmitterSenderTest | SseEmitterSender | 中 |

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn test` 全部通过 | ✅ PASS |

### 问题与备注

已完成

---

## Phase 11：单实现接口逐案评估

**执行时间**: 2026-04-11
**风险**: 低
**状态**: ✅ 完成

### 扫描方法

本 Phase 按“先评估、后收敛”的原则执行，避免把真实边界接口误删：

1. 用 `git grep "^public interface "` 扫描 `bootstrap` 模块全部公开接口
2. 用 `git grep "implements ..."` 建立接口 → 实现类映射
3. 交叉检查主代码注入点与测试 mock 点，区分以下三类：
   - 六边形边界：Facade / Port / Repository / 多实现策略，保留
   - 能力标记或未来扩展点：暂保留
   - 仅有单实现、仅在内部被注入的应用服务：优先收敛

补充：为了避免漏项，我还用 PowerShell 脚本再次扫描了 `bootstrap/src/main/java`，对当前仍存在的 public 单实现接口做了全量清点；`DirectToolCallbackSource` 因为是“第二接口 marker”不会被脚本直接计入，所以单独补评估。

### 全量评估结论

#### 已收敛（4 个）

| 接口 | 结论 | 原因 |
|------|------|------|
| `UserProfileService` | ✅ 收敛 | 仅 1 个实现，调用方仅 `UserProfileController` / `AgentUserProfileSummaryResolver`，不承担 Port/Facade 语义 |
| `ConversationOrchestratorService` | ✅ 收敛 | 仅 1 个实现，调用方仅 `ChatMessageController`，属于应用编排类，不需要额外抽象层 |
| `RagService` | ✅ 收敛 | 仅被 `SessionFileTools` 注入，无测试以接口 mock，属于内部检索编排入口 |
| `FileIngestionService` | ✅ 收敛 | 仅被 `ChatSessionFileFacadeServiceImpl` 注入，测试直接实例化实现类，保留接口没有额外边界价值 |

#### 保留：应用契约 / Facade 边界

| 接口 | 结论 | 原因 |
|------|------|------|
| `AgentFacadeService`, `AssistantKnowledgeBaseFacadeService`, `AssistantTemplateFacadeService`, `AuthService`, `ChatMessageFacadeService`, `ChatRoutingAdminFacadeService`, `ChatSessionFacadeService`, `ChatSessionFileFacadeService`, `DashboardFacadeService`, `IntentTreeFacadeService`, `KnowledgeBaseFacadeService`, `KnowledgeDocumentFacadeService`, `McpServerAdminFacadeService`, `MqAdminFacadeService`, `ToolFacadeService`, `UserAdminFacadeService` | ⏸️ 保留 | 虽然当前单实现，但都属于应用层对外契约；控制器、工具装配或跨子域调用依赖这些名称表达职责边界，不适合为了减少接口数量而内联 |

#### 保留：Port / Repository / Store / Client 边界

| 接口 | 结论 | 原因 |
|------|------|------|
| `AgentKnowledgeBaseRepository`, `AgentRepository`, `AssistantTemplateRepository`, `ChatMessageRepository`, `ChatSessionFileRepository`, `ChatSessionRepository`, `ChatSessionSummaryRepository`, `FileChunkRepository`, `IntentKnowledgeBaseRepository`, `IntentNodeRepository`, `KnowledgeBaseRepository`, `KnowledgeChunkRepository`, `KnowledgeDocumentEnhancementRepository`, `KnowledgeDocumentRepository`, `McpAlertEventRepository`, `McpServerReferenceQueryRepository`, `McpServerRepository`, `McpToolCatalogRepository`, `OutboxRepository`, `RefreshTokenStore`, `UserProfileRepository`, `UserRepository` | ⏸️ 保留 | 这些接口是典型六边形端口；即使当前只有 1 个 MyBatis / Redis 实现，也承担持久化与基础设施隔离职责 |
| `DocumentStorageService`, `McpTransportClient`, `MilvusIndexService`, `KnowledgeBaseMilvusIndexService`, `PendingIntentResolutionStore` | ⏸️ 保留 | 分别隔离文件存储、远程 MCP 传输、Milvus 索引、pending intent 存储等基础设施能力，属于可替换网关而非命名噪音 |

#### 保留：运行时 seam / 策略 / 安全边界

| 接口 | 结论 | 原因 |
|------|------|------|
| `AgentMessageBridge`, `AgentRuntimeContextLoader` | ⏸️ 保留 | `ChatAgentFactory` / `AgentThinkingEngine` / `AgentToolExecutionEngine` 通过接口依赖运行时装配 seam，且测试中直接以接口 mock |
| `IntentTreeCacheManager` | ⏸️ 保留 | 被 `IntentRouter`、`ConversationTurnPreparationService`、`McpServerDeleteHandler`、`IntentTreeFacadeServiceImpl` 共享，属于跨子域缓存边界 |
| `PasswordService` | ⏸️ 保留 | 安全相关能力边界，便于在 `AuthServiceImpl` / `UserAdminFacadeServiceImpl` 中替换哈希策略与测试桩 |
| `ResourceAccessGuard` | ⏸️ 保留 | 读写权限校验是横切安全边界，多处 service / controller 依赖，测试中也直接 mock 接口 |
| `DocumentChunker` | ⏸️ 保留 | 当前虽仅 1 个实现，但承担 ingestion pipeline 的 chunking 策略角色，且被多个测试显式依赖 |
| `KnowledgeDocumentIngestionService` | ⏸️ 保留 | 既被 `KnowledgeDocumentFacadeServiceImpl` 注入，也被 MQ consumer 使用，测试中以接口 mock，保留有助于异步摄取边界隔离 |
| `DirectToolCallbackSource` | ⏸️ 保留 | capability marker，表达“工具已自带 ToolCallback”的运行时语义，不只是命名噪音 |

#### 已评估但暂不在本批删除

| 接口 | 结论 | 原因 |
|------|------|------|
| `MarkdownParserService` | ⏭️ 延后 | 当前没有运行时调用方，但内部 `MarkdownSection` 值对象仍被 `MarkdownSectionChunker` 与测试复用；删除接口前应先单独整理 markdown 值对象归属，避免在本 Phase 顺手引入死代码清理混改 |

### 实际改造

#### 第一批：收敛 `UserProfileService`

- 删除 `UserProfileService` 接口 + `UserProfileServiceImpl` 双文件结构
- 保留类型名 `UserProfileService`，直接改为 `@Service` 具体类
- 将原有实现逻辑原样并入，包含：
  - `getCurrentUserProfile`
  - `updateCurrentUserProfile`
  - `getUserProfile`
  - `getUserProfileSummary`
  - 存储失败降级与默认 summary 逻辑
- 因类型名保持不变，`UserProfileController` 与 `AgentUserProfileSummaryResolver` 无需改注入签名

#### 2. 收敛 `ConversationOrchestratorService`

- 删除 `ConversationOrchestratorService` 接口 + `ConversationOrchestratorServiceImpl` 双文件结构
- 保留类型名 `ConversationOrchestratorService`，直接改为 `@Service` 具体类
- 原有 `@Transactional`、turn-id 校验、direct reply、event dispatch、SSE 推送逻辑全部保留
- `ChatMessageController` 保持原有注入类型不变，仅底层 bean 由接口代理切为直接服务类
- 两个直接引用实现类名的测试已同步更新为引用 `ConversationOrchestratorService.class`

#### 第二批：收敛 `RagService`

- 删除 `RagService` 接口 + `RagServiceImpl` 双文件结构
- 保留类型名 `RagService`，直接改为 `@Service` 具体类
- `embed`、`similaritySearchBySession`、`similaritySearchByKnowledgeBaseIds` 委托逻辑保持不变
- `SessionFileTools` 继续注入 `RagService`，因此业务调用点零侵入

#### 第二批：收敛 `FileIngestionService`

- 删除 `FileIngestionService` 接口 + `FileIngestionServiceImpl` 双文件结构
- 保留类型名 `FileIngestionService`，直接改为 `@Service` 具体类
- 将旧实现按原行为迁移，保留：
  - `@Async` 异步摄取入口
  - fetch / parse / enhance / chunk / enrich / persist / mark status 全链路
  - `OCR_REQUIRED` / `REJECTED` 分支处理
  - `LoadedDocumentSource` 内部 record
  - `documentCacheKey` 构造与 `openInputStream` 读取逻辑
- `[FileIngestionServiceTest]` 仅把直接实例化目标改为 `new FileIngestionService(...)`，测试语义保持不变

### 验证结果

#### 定向测试（第一批）

```bash
./mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dtest=ConversationOrchestratorServiceTest,ConversationOrchestratorServiceTransactionTest,ChatMessageControllerTest" test
```

结果：3 个测试全部通过，验证点包括：
- 非 canonical UUID turnId 仍会被拒绝
- `handleUserTurn` 仍保留 `@Transactional`
- `ChatMessageController` 在 session lock 冲突时仍不会触发 orchestrator

#### 定向测试（第二批）

```bash
./mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dtest=FileIngestionServiceTest" test
```

结果：`FileIngestionServiceTest` 2 个测试全部通过，验证 session-file ingestion 在接口收敛后仍能完成：
- markdown 文件正常落库、切块与状态更新
- image 文件允许空 chunk 结果并保持完成状态

说明：`RagService` 当前没有独立单测；本批对它只做了“接口并入具体类”的结构性收敛，实际行为依赖后续全量测试回归确认。

#### 尾单整理：测试命名对齐

- 将遗留的 `*ImplTest` 文件名与类名统一改回当前实际被测类型：
  - `ConversationOrchestratorServiceImplTest` → `ConversationOrchestratorServiceTest`
  - `ConversationOrchestratorServiceImplTransactionTest` → `ConversationOrchestratorServiceTransactionTest`
  - `FileIngestionServiceImplTest` → `FileIngestionServiceTest`
- 本次仅整理命名，不改测试语义，目的是让 Phase 11 收敛后的类型名、测试文件名和执行文档口径一致

#### 全量验证

```bash
./mvnw.cmd test
```

结果：整仓 342 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

---

## Phase 8：PdfDocumentParser 深度拆分（第一、二批）

**执行时间**: 2026-04-11
**风险**: 中
**状态**: ⏳ 进行中（第二批完成）

### 本批目标

先完成 Phase 8 的“职责切面剥离”，只做行为保持型抽取，不在第一批同时引入更高风险的批次算法变化：

1. 从 `PdfDocumentParser` 中抽出 cache / planner / dispatcher / segment assembler 四个协作者
2. 保持 cache key 字节级稳定
3. 保持回退顺序 `VDP -> NATIVE_TEXT -> EMPTY_PAGE`
4. 保持 batch engine 仍按“整份 PDF 一次提交”的当前行为，不在本批引入多批切分

### 实际改造

#### 1. 抽出 `PdfVdpCache`

- 新增 `[PdfVdpCache]`，接管以下职责：
  - `PageCacheContext` 构建
  - `buildDigest` 生成 page cache digest
  - `get / put / putAll` 与 `VdpPageCacheService` 交互
- cache digest 公式保持与重构前完全一致：

```text
pdf-page:{documentCacheKey}:{pageIndex}:{dpi*10}:{normalizedLanguageHint}:{recognizeFormulasFlag}
```

- 其中 `languageHint` 仍按 `trim().toLowerCase()` 归一化，空值仍降级为 `default`
- `renderDpi` 仍按 `Math.round(renderDpi * 10.0f)` 进入 digest，确保既有缓存 namespace 不漂移

#### 2. 抽出 `PdfVdpBatchPlanner`

- 新增 `[PdfVdpBatchPlanner]`，从 parser 中移出“哪些 visual-track 页已经命中缓存、哪些页仍需调度”的规划逻辑
- 当前 planner 只负责**缓存感知的 dispatch plan**，不在本批引入新的 multi-batch 切分算法
- 这样做是为了先满足 8.3 的“职责隔离可验证”，同时避免在同一批次混入 batch 语义变化

#### 3. 抽出 `PdfVdpDispatcher`

- 新增 `[PdfVdpDispatcher]`，接管以下职责：
  - batch engine / page-image engine 选择后的 VDP 调度
  - 文档级 timeout 与 per-page timeout
  - in-flight future 管理、取消、完成收集、超时淘汰
  - batch 结果规范化与失败/超时填充
  - `vdp.page.success / degraded / failed / timeout` 指标记录
- 为了保持行为不变，本批**刻意不修改**以下点：
  - batch engine 仍使用 `parsePagesAsync(pdfStreamSupplier, visualPageIndices, ...)`
  - `mineruReceivesWholePdf=true` 的整 PDF 调用语义保持原样
  - 仍不做“按页数 / DPI 预算拆成多个 batch 请求”的实验性改造

#### 4. 抽出 `PdfSegmentAssembler`

- 新增 `[PdfSegmentAssembler]`，从 parser 中移出：
  - native page segment 构建
  - visual page segment 构建
  - 字体元数据与结构元数据挂载
  - visual success / degraded / failed 计数
- 回退顺序保持不变：
  - 有 markdown：直接用 VDP 输出
  - 无 markdown 但有 native text：回退到 native text，并打 `visualFallback=NATIVE_TEXT`
  - 两者都没有：输出空 segment，并打 `visualFallback=EMPTY_PAGE`

#### 5. 瘦身 `PdfDocumentParser`

- `PdfDocumentParser` 从 `1189` 行降到 `535` 行
- 主类现在主要保留：
  - PDF 打开与文件大小保护
  - page snapshot 提取
  - route 决策
  - extractionMode / qualityLevel 判定
  - diagnostics / warnings 汇总
- 这一步达到了 8.3 的第一优先级要求：`PdfDocumentParser` 对 `Cache / Planner / Dispatcher / Assembler` 的调用边界已经可以直接用一句话描述，不再存在主类内的 VDP 调度巨型私有方法串联

### 暂未在本批处理的项

- **未引入真正的 batch size planner**：计划文档里原先提到的“根据 DPI 预算 + 页数计算批次大小”会改变 batch 行为；考虑到 `MinerU` 目前仍以“整份 PDF + pageIndices”模式工作，本批不做这个高风险改动
- **未继续拆 `PdfVdpDispatcher` 内部子职责**：当前先把职责从 parser 中剥离出去，后续如果 Phase 8 继续推进，可再评估是否把 dispatcher 内部的 timeout / batch-normalize / page-dispatch 再细分

### 验证结果

#### 编译验证

```bash
./mvnw.cmd -pl bootstrap -am -DskipTests compile
```

结果：✅ 通过。

#### 定向测试

```bash
./mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dtest=PdfDocumentParserTest,PdfVdpCacheTest" test
```

结果：24 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

#### 新增不变量测试

- 新增 `[PdfVdpCacheTest]`
- 覆盖点：
  - `documentCacheKey + pageIndex + dpi*10 + normalizedLanguageHint + formulasFlag` 的 digest 格式不变
  - `languageHint` 缺失时仍使用 `default`

### 本批结论

#### 全量回归

```bash
./mvnw.cmd test
```

结果：整仓 344 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

Phase 8 第一批已经完成“主类瘦身 + 职责抽离 + 核心不变量显式测试”三件事，但还不能把整个 Phase 8 标记为最终完成。当前更准确的状态是：

- `PdfDocumentParser` 的剩余职责已经收敛到入口编排
- VDP cache / planning / dispatch / segment assembly 已具备独立协作者
- 更高风险的 batch 语义演进（若后续仍需要）应单独评估，不与本批混做

### 第二批：继续细分 `PdfVdpDispatcher`

#### 本批目标

在不改变 `MinerU` 整 PDF 提交语义的前提下，继续把 `PdfVdpDispatcher` 内部“过粗”的职责拆开，重点解决第一批后仍然偏大的 dispatcher。

#### 实际改造

##### 1. 抽出 `PdfVdpPageDispatchLoop`

- 新增 `[PdfVdpPageDispatchLoop]`
- 将以下 page-image dispatch loop 逻辑从 `PdfVdpDispatcher` 中移出：
  - in-flight future 管理
  - per-page timeout 清理
  - document budget 轮询
  - page 渲染后提交 VDP
  - 取消、收集完成、剩余页失败补齐
- 这样 `PdfVdpDispatcher` 不再同时承担“选路 + 调度循环实现”两种职责

##### 2. 抽出 `PdfVdpBatchResultNormalizer`

- 新增 `[PdfVdpBatchResultNormalizer]`
- 将以下 batch 结果收口逻辑从 `PdfVdpDispatcher` 中移出：
  - batch 返回页结果映射
  - 缺页补失败结果
  - batch 全失败 / 全超时结果填充
  - 结果写 cache
- 这一步把“batch 调度”和“batch 结果归一化”拆成了两个清晰阶段

##### 3. 抽出 `PdfVdpResultSupport`

- 新增 `[PdfVdpResultSupport]`
- 集中承接：
  - failed result 生成
  - `vdp.page.success / degraded / failed / timeout` 指标写入
- `PdfSegmentAssembler` 中“缺失 visualResult 时补 failed result”的逻辑也改为依赖该 support，避免继续耦合 `PdfVdpDispatcher` 的静态 helper

##### 4. `PdfVdpDispatcher` 继续瘦身

- `PdfVdpDispatcher` 从 `503` 行降到 `159` 行
- 当前主要职责已经收敛为：
  - 选择 batch engine / page-image engine
  - 组装 cache context 与 dispatch plan
  - 调用 `PdfVdpBatchResultNormalizer` 或 `PdfVdpPageDispatchLoop`
  - 统一解析 document timeout / per-page timeout

#### 行数观察

- `PdfVdpDispatcher`: `503 -> 159`
- `PdfVdpPageDispatchLoop`: `296`
- `PdfVdpBatchResultNormalizer`: `79`
- `PdfVdpResultSupport`: `60`

这次拆分没有再追求“单类极限压行”，而是按“页面调度循环 / 批量结果归一化 / 结果与指标支持”三个稳定职责面切开。

#### 定向测试补充

```bash
./mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dtest=PdfDocumentParserTest,PdfVdpCacheTest,PdfVdpBatchResultNormalizerTest" test
```

结果：26 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

新增 `[PdfVdpBatchResultNormalizerTest]`，覆盖：

- batch 返回缺页时，missing page 仍会补成 failed result
- batch 全超时时，`vdp.page.timeout` 指标仍按页计数

#### 第二批结论

- 第二批已经把 `PdfVdpDispatcher` 从“单点承压类”进一步收敛成编排入口
- 目前 `Phase 8` 仍未完全结项，剩余项主要是：
  - 是否要把 `PdfVdpBatchPlanner` 从“缓存感知 planner”继续演化为真正的 batch size planner
  - 是否需要补性能基线记录，证明第二批继续拆分后无明显回归
  - 是否有必要在外部约束变化后重新评估 `MinerU` 的整 PDF 提交策略

### 第三批（结项）：Golden 基线记录 + 范围收口

**执行时间**: 2026-04-11
**状态**: ✅ Phase 8 按"行为保持型深度拆分"口径结项

#### 本批目标

前两批已经把 `PdfDocumentParser` 从 1189 行拆成 535 行 + 7 个协作者，且用 `PdfVdpCacheTest` / `PdfVdpBatchResultNormalizerTest` 钉住了 cache digest 与 batch 结果归一化两条关键不变量。本批不再动生产代码，只做两件事：

1. 用现成的 `golden-pdfs/` 样本和现成的 `PdfDocumentParser` 入口，在 golden 组内补一条**可复跑的性能 + 缓存基线**，作为任何后续 VDP 改动的 baseline
2. 把计划文档里挂在 Phase 8 下但性质属于"外因驱动演化"的两项（batch size planner 真正化、MinerU 整 PDF 提交策略）从 Phase 8 scope 中移出，挪到计划 §12 延后处理

#### 新增 `GoldenPdfPerformanceBaselineTest`

- 位置：`bootstrap/src/test/java/com/yulong/chatagent/rag/parser/GoldenPdfPerformanceBaselineTest.java`
- 标签：`@Tag("golden")` — 默认被 surefire `excludedGroups=golden` 排除，不污染主测试计数（仍为 346）
- 样本：复用已有 `src/test/resources/golden-pdfs/` 下 `heading-01` / `scanned-01` / `mixed-01` 三个固定样本（分别覆盖 NATIVE_TEXT、OCR_REQUIRED、PDF_VISUAL_ROUTED 三类 extractionMode）
- 每个样本执行两次 `parser.parse(...)`：
  - 第一次：冷缓存，记录 `firstParseMs`
  - 第二次：热缓存，记录 `secondParseMs`
  - `assertThat(second).usingRecursiveComparison().isEqualTo(first)` — 钉住"两次解析结果字节级一致"，即 Phase 8 的"输出不变"承诺
- 收集指标：
  - `vdp.cache.miss{layer=page}` / `vdp.cache.hit{layer=page}` 计数
  - `vdp.document.parse.latency` Timer 总耗时与采样数
  - 每样本的 `visualTrackPageCount` / `segmentCount` / `extractionMode`
- 写入 JSON 报告：`bootstrap/target/phase8-baseline/golden-pdf-performance-baseline.json`

#### 缓存查询不变量（本批钉住）

原先倾向断言"第二次应全缓存命中"，但与 `NoopVdpEngine` 的 FAILED 结果不写缓存语义冲突；真正稳定的不变量是：

> 每个 visual-track 页每次 parse 触发恰好一次缓存查询。

对应断言：

```java
double totalLookups = observation.cacheMisses() + observation.cacheHits();
assertThat(totalLookups).isEqualTo(observation.visualTrackPageCount() * 2.0d);
```

这条断言在真 VDP（SUCCESS 缓存）和 Noop VDP（FAILED 不缓存）两种场景下都成立，且能在未来任何"重复查询 / 漏查缓存"的回归里立刻报警。

#### 首次基线数据（2026-04-11）

| 样本 | extractionMode | visualTrack | firstParseMs | secondParseMs | parseLatencyTotalMs | cacheMisses / Hits |
|------|----------------|-------------|--------------|---------------|---------------------|--------------------|
| heading-01 | NATIVE_TEXT | 0 | 322.30 | 7.22 | 256.68 | 0 / 0 |
| scanned-01 | OCR_REQUIRED | 1 | 247.92 | 60.07 | 305.70 | 2 / 0 |
| mixed-01 | PDF_VISUAL_ROUTED | 1 | 98.76 | 79.21 | 175.57 | 2 / 0 |

说明：
- `scanned-01` / `mixed-01` 两个样本 cacheHits=0 是因为 `NoopVdpEngine` 产出 FAILED 结果、FAILED 不写缓存，这是符合设计的语义。用真 MinerU 重跑时 cacheHits 会接近 `visualTrackPageCount`
- 第一次 parse 明显高于第二次，主要来自 JVM warmup 与 PDFBox 首次加载，而不是 VDP 工作量
- 本数据作为 Phase 8 的 baseline，任何未来动 VDP 代码的改动都可通过 `mvnw -Dgroups=golden -Dtest=GoldenPdfPerformanceBaselineTest test` 重跑并对比

#### 如何在本地复跑基线

```bash
./mvnw.cmd -pl bootstrap -am \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dsurefire.excludedGroups=" \
  "-Dgroups=golden" \
  "-Dtest=GoldenPdfPerformanceBaselineTest" test
```

输出报告：`bootstrap/target/phase8-baseline/golden-pdf-performance-baseline.json`

#### 验证

| 检查项 | 结果 |
|--------|------|
| `GoldenPdfPerformanceBaselineTest` 定向（golden 组） | ✅ 1 test 通过，三条 recursive equality + 三条 cache lookup 断言全部绿 |
| `./mvnw.cmd test` 全量回归（默认排除 golden） | ✅ 346 tests, Failures=0, Errors=0, Skipped=0 |
| 主测试总数 | 仍为 346，未因新增基线测试而漂移（golden 组被默认排除） |
| 生产代码改动 | 零 — 本批不触碰任何 `main/java` 文件 |

#### Phase 8 结项说明

- **Phase 8 "PdfDocumentParser 深度拆分" 按"行为保持型重构 + 输出不变证据"口径结项**
- 主类从 1640 → 1189 → 535 行，新增 7 个职责明确的 VDP 协作者
- 关键不变量被三条测试钉住：
  - `PdfVdpCacheTest` — cache digest 格式稳定
  - `PdfVdpBatchResultNormalizerTest` — batch 缺页补齐 + timeout 指标按页计数
  - `GoldenPdfPerformanceBaselineTest` — 两次 parse 字节级一致 + 每页每次 parse 恰好一次缓存查询
- 计划文档里原先挂在 Phase 8 下的两项"未来演进"（真正的 batch size planner；MinerU 整 PDF 提交策略重评估）已从 Phase 8 scope 中移出，挪到 §12 延后处理 — 原因是它们都**取决于外部约束（MinerU 行为）变化**，不应阻塞 Phase 8 结项

---


## Phase 9-α：数据对象精简 — 契约不变批

**执行时间**: 2026-04-11
**风险**: 低
**状态**: ✅ 完成（2 个 VO 收敛；其余分类 A 候选归入 9-β）

### 9-α 扫描方法

逐个读取全部 24 个 VO 文件及其对应 DTO，做字段级比对。判定标准：

- **可删（分类 A）**：VO 与 DTO 字段 100% 一致，或 VO 是 DTO 的纯子集，无格式化/类型转换/计算字段
- **保留（分类 B）**：VO 有计算字段、来源非 DTO、嵌套聚合逻辑、或 DTO↔VO 之间有类型转换

### 全量评估记录

#### 分类 A：可收敛（10 个 VO）

| # | VO | 对应 DTO | 重叠程度 | 构造逻辑 | 收敛风险 |
|---|---|---|----------|----------|----------|
| 1 | `McpToolReferenceVO` | `McpToolReferenceDTO` | 100%（4 字段完全一致） | `toReferenceVO()` 逐字段拷贝 | 零 |
| 2 | `McpToolCatalogVO` | `McpToolCatalogDTO` | VO 是 DTO 子集（DTO 多 schemaJson/schemaHash/deletedAt） | `toCatalogVO()` 逐字段拷贝 | 低 — 删 VO 后响应会多暴露 3 个字段 |
| 3 | `ChatSessionFileVO` | `ChatSessionFileDTO` | VO 是 DTO 子集（DTO 多 sessionId/storagePath/metadata） | builder 逐字段拷贝 | 低 — 删 VO 后响应会多暴露 3 个字段 |
| 4 | `ChatMessageVO` | `ChatMessageDTO` | VO 子集（DTO 多 createdAt/updatedAt） | builder 逐字段拷贝 | 低 — 删 VO 后响应会多暴露 2 个字段 |
| 5 | `ChatSessionVO` | `ChatSessionDTO` | VO 是 DTO 子集（DTO 多 userId/metadata/createdAt/updatedAt） | builder 逐字段拷贝 | 低 — 删 VO 后响应会多暴露 4 个字段 |
| 6 | `AgentVO` | `AgentDTO` | VO 是 DTO 子集（DTO 多 userId/activeIntentVersion/createdAt/updatedAt） | builder 逐字段拷贝 | 低 — 删 VO 后响应会多暴露 4 个字段 |
| 7 | `KnowledgeBaseVO` | `KnowledgeBaseDTO` | VO 是 DTO 子集（DTO 多 createdBy/metadata） | builder 逐字段拷贝 | 低 — 删 VO 后响应会多暴露 2 个字段 |
| 8 | `KnowledgeDocumentVO` | `KnowledgeDocumentDTO` | VO 是 DTO 子集（DTO 多 storagePath/contentHash/failedReason/indexedAt/retryCount/metadata） | builder 逐字段拷贝 | 低 — 删 VO 后响应会多暴露 7 个字段 |
| 9 | `IntentVersionVO` | 无 DTO（纯视图：version + active） | 由 `buildVersionVos()` 从整数+布尔构造 | 零 — 可改为 inline record |
| 10 | `McpServerVO` | `McpServerDTO` | VO 是 DTO 子集（DTO 多 encryptedCredentials/credentialKeyVersion/deletedAt），VO 多 `unresolvedReferenceCount`（计算字段） | `toServerVO()` 逐字段拷贝 + `resolveUnresolvedReferenceCount()` | 中 — VO 有计算字段，删 VO 后需保留计算逻辑 |

#### 分类 B：必须保留（14 个 VO）

| VO | 保留原因 |
|---|---|
| `McpDiscoveredToolVO` | 来源是 `McpRemoteToolDescriptor`（record，非 DTO），且 exposedModelName 经过 normalizeToolName() 计算 |
| `ChatRoutingCandidateVO` | 来源是 `ChatRoutingProperties.CandidateConfig` + 运行时路由状态合并，无 DTO |
| `IntentNodeVO` | DTO 有 agentId/createdAt/updatedAt 但 VO 多了 knowledgeBaseIds（从关联表查询），DTO 里没有 |
| `AssistantTemplateVO` | DTO 有双字段（model+modelValue 等），VO 只暴露 rich 字段 + 嵌套 IntentTreeNodeTemplateVO（enum→String）|
| `AdminUserVO` | 来源是 UserDTO/User 实体，无直接 1:1 DTO |
| `LoginUserVO` | 来源是 JWT claims + UserDTO，无直接 DTO |
| `UserProfileVO` | 来源是 UserProfileDTO，但只有 3 个字段（userId/summary/updatedAt），不完整子集 |
| `DashboardOverviewVO` | 纯聚合视图，含嵌套 KPI 结构，无 DTO |
| `DashboardTrendsVO` | 纯聚合视图，含嵌套 series/point 结构，无 DTO |
| `DashboardMcpAlertVO` | 来源 McpAlertEventDTO 但 VO 多了 serverSlug/toolName（从关联查询填充）|
| `DashboardMcpAlertsVO` | 包装器，含 openAlertCount + 嵌套 alert 列表 |
| `DashboardMcpPerformanceVO` | 纯聚合视图 |
| `DashboardMcpServerMetricVO` | 从 McpServerDTO + 调用统计聚合，无 1:1 DTO |
| `DashboardPerformanceVO` | 纯聚合视图，嵌套 McpPerformance |

### 实际改造

#### 9-α-1 试点：McpToolReferenceVO → McpToolReferenceDTO

- VO 与 DTO 字段 100% 一致（referenceType / referenceId / referenceName / referencePath），无转换逻辑
- 改动范围：
  - `DeleteMcpServerResponse` — 字段类型从 `List<McpToolReferenceVO>` 改为 `List<McpToolReferenceDTO>`
  - `McpServerDeleteHandler.execute()` — 移除 `Function<McpToolReferenceDTO, McpToolReferenceVO>` 参数，直接传递 DTO 列表
  - `McpServerAdminFacadeServiceImpl` — 删除 `toReferenceVO()` 转换方法，调用 `deleteHandler.execute(server, force)` 不再传转换器
  - 删除 `McpToolReferenceVO.java`
- 序列化行为不变：DTO 使用 `@Data`（Lombok 生成 getter/setter），Jackson 序列化字段名与原 VO 完全一致

#### 9-α-1 试点：IntentVersionVO → record

- VO 只有 2 个字段（version + active），无 DTO，由 `buildVersionVos()` 从 Integer + Boolean 构造
- 改动范围：
  - `IntentVersionVO` — 从 `@Data @AllArgsConstructor` class 改为 `public record IntentVersionVO(Integer version, boolean active) {}`
  - 所有消费方（`GetIntentVersionsResponse`、`GetIntentTreeResponse`、`IntentTreeFacadeServiceImpl`）使用 `new IntentVersionVO(...)` 构造，record 的 compact constructor 兼容原有调用
- 序列化行为不变：record 的 Jackson 序列化与 `@Data` class 字段名一致

#### 9-α-1 试点：ChatMessageVO → 延后至 9-β

- VO 有 7 个字段，DTO 有 9 个字段（多 `createdAt` / `updatedAt`）
- `ChatMessageConverter.toVO()` 显式丢弃这两个字段
- 替换后 HTTP 响应体会多暴露 2 个字段，属于契约变化，不满足 9-α 前提
- **结论**：归入 9-β

#### 9-α-2 扩展评估

逐个核实分类 A 剩余 7 个候选：

| VO | DTO 多出字段 | 是否契约变化 | 结论 |
|---|---|---|---|
| McpToolCatalogVO | schemaJson / schemaHash / deletedAt | 是 | 9-β |
| ChatSessionFileVO | sessionId / storagePath / metadata | 是 | 9-β |
| ChatSessionVO | userId / metadata / createdAt / updatedAt | 是 | 9-β |
| AgentVO | userId / activeIntentVersion / createdAt / updatedAt | 是 | 9-β |
| KnowledgeBaseVO | createdBy / metadata | 是 | 9-β |
| KnowledgeDocumentVO | storagePath / contentHash / failedReason / indexedAt / retryCount / metadata | 是 | 9-β |
| McpServerVO | encryptedCredentials / credentialKeyVersion / deletedAt（且 VO 多 unresolvedReferenceCount） | 是 | 9-β |

**全部归入 9-β**：每个 VO 都是 DTO 的真子集，删除后 HTTP 响应体会暴露额外字段。

#### 9-α-3 内部辅助 VO

扫描全部 23 个 VO 文件，逐一检查是否仅被内部 Helper 使用而不经 Controller/Response。结果显示每个 VO 至少被一个 Response 类或 Controller 直接引用。**无 9-α-3 候选**。

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ✅ PASS |
| `McpServerAdminFacadeServiceImplTest`（9 tests） | ✅ PASS |
| `./mvnw.cmd test` 全量（346 tests） | ✅ PASS，Failures=0, Errors=0, Skipped=0 |

### 9-α 结论

- **2 个 VO 成功收敛**：McpToolReferenceVO 删除（直接用 DTO）、IntentVersionVO 改为 record
- **8 个分类 A 候选归入 9-β**：均因 DTO 多暴露字段而不满足"契约不变"前提
- **14 个分类 B VO 保留**：有计算字段 / 聚合结构 / 非 DTO 来源，不应删除
- Phase 9-α 按计划"契约不变批"口径结项，剩余数据对象精简需等前端联调窗口
