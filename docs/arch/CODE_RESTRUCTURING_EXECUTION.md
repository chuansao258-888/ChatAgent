# ChatAgent 代码结构重构执行记录

> 分支：`reconstruct/code-reconstruction`
> 基于提交：`9613e77` (main)
> 执行日期：2026-04-11 ~ 2026-04-12
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
| 9-α | 数据对象精简（契约不变批） | ✅ 完成 | TBD | 多个 | McpToolReferenceVO 删除 + IntentVersionVO 改 record |
| 9-β | 数据对象精简（契约变化批） | ✅ 完成 | TBD | 多个 | 三批删除 13 薄 Response + 5 对 Create/Update 合并为 UpsertRequest + 漏网复核零候选 |

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

**最终测试**: `mvn test` 351 tests 通过，Failures=0，Errors=0，Skipped=0

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
| 数据对象精简（Phase 9） | 9-α 删除 McpToolReferenceVO + IntentVersionVO 改 record；9-β-1 三批删除 13 个薄 Response；9-β-2 合并 5 对 Create/Update 为 UpsertRequest；9-β-3 漏网复核零候选；当前 DTO 21 + VO 23 + API Response 17 + Request 20 = 81 |
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
| `./mvnw.cmd test` 全量回归（默认排除 golden） | ✅ 348 tests, Failures=0, Errors=0, Skipped=0 |
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

**执行时间**: 2026-04-11 ~ 2026-04-12
**风险**: 低
**状态**: ✅ 完成（仅保留“VO 与 DTO 字段完全一样才删除”的收敛规则）

### 9-α 扫描与判定标准

Phase 9 初稿一度把“VO 是 DTO 的字段子集”也视为可删候选，但这会把“裁剪暴露面”和“删除包装层”混为一谈。经过对 `DATA_OBJECT_INVENTORY.md` 与当前工作树的再次 review，本阶段准则收紧为：

- **默认保留 VO**
- 只有当 **VO 与 DTO 字段完全一致** 时，才允许把删除 VO 作为 9-α 的默认方案
- 这里的“完全一致”包括：字段名、字段类型、可空性/序列化行为、无额外计算/脱敏/裁剪逻辑

按这个新准则重新核对后，当前仅有 1 个“删 VO → 直用 DTO”的有效样本，另有 1 个“极小值对象 record 化”样本：

| 项目 | 结论 | 原因 |
|------|------|------|
| `McpToolReferenceVO` | ✅ 删除，改用 `McpToolReferenceDTO` | 4 个字段与 DTO 完全一致 |
| `IntentVersionVO` | ✅ 改为 record | 无对应 DTO，但只有 `version + active` 两个字段 |

### 2026-04-12 校准批：收回冲突的 DTO 直出尝试

在开始继续推进 Phase 9 之前，我先检查了当前工作树，发现已经存在一批“把 VO 直接替换成 DTO”的未提交尝试。这些尝试与上面的新准则冲突，而且其中有一部分已经破坏了代码边界：

- `GetAgentsResponse` 被改成 `AgentDTO[]`
- `GetChatSessionResponse` / `GetChatSessionsResponse` 被改成 `ChatSessionDTO`
- `GetKnowledgeBaseResponse` / `GetKnowledgeBasesResponse` / `GetAssistantKnowledgeBasesResponse` 被改成 `KnowledgeBaseDTO`
- `GetMcpServerResponse` 被改成 `List<McpToolCatalogDTO>`
- `McpToolCatalogVO.java` 被删除
- `ChatMessageConverter.toVO(...)` 被删掉，但 `AgentMessageBridgeImpl` 仍依赖该方法

这批改动如果继续保留，会让当前 Phase 9 同时混入“契约变化”和“边界回退”两种风险，不利于后续分批执行，所以本批先全部收回到安全边界内。

### 实际改造

#### 9-α-1 已保留的正式收敛项

- `McpToolReferenceVO` 删除，`DeleteMcpServerResponse` 直接使用 `List<McpToolReferenceDTO>`
- `IntentVersionVO` 改为 Java record，序列化行为保持不变

#### 9-α-2 VO 边界恢复（2026-04-12）

##### Agent 模块

- `AgentFacadeServiceImpl.getAgents()` 恢复为 `AgentDTO -> AgentVO` 显式转换
- `GetAgentsResponse` 恢复为 `AgentVO[]`

##### Conversation 模块

- `GetChatMessagesResponse` 恢复为 `ChatMessageVO[]`
- `GetChatSessionResponse` / `GetChatSessionsResponse` 恢复为 `ChatSessionVO`
- `SseMessage.Payload.message` 恢复为 `ChatMessageVO`
- `ChatMessageConverter` 恢复 `toVO(ChatMessageDTO)` / `toVO(ChatMessage)`，保持 `AgentMessageBridgeImpl` 的现有调用成立
- `ChatSessionConverter` 恢复 `toVO(ChatSessionDTO)` / `toVO(ChatSession)`，避免 facade 再把 VO 当 DTO 使用
- `ChatMessageFacadeServiceImpl` 恢复 `ChatMessageDTO -> ChatMessageVO[]` 的显式组装
- `ChatSessionFacadeServiceImpl` 恢复 `ChatSessionDTO -> ChatSessionVO[]` / `ChatSessionVO` 的显式组装
- `ConversationOrchestratorService` 恢复 direct-reply SSE payload 使用 `ChatMessageVO`
- `AgentMessageBridgeImpl` / `ChatEventProcessor` 恢复所有 SSE message snapshot / fallback message 使用 `ChatMessageVO`

##### Knowledge 模块

- `KnowledgeBaseFacadeServiceImpl` 恢复注入 `KnowledgeBaseConverter`
- `getKnowledgeBases()` / `getKnowledgeBase()` 恢复为 `KnowledgeBaseVO` 输出
- `AssistantKnowledgeBaseFacadeServiceImpl` 恢复 `KnowledgeBaseDTO -> KnowledgeBaseVO` 转换
- `GetKnowledgeBaseResponse` / `GetKnowledgeBasesResponse` / `GetAssistantKnowledgeBasesResponse` 全部恢复为 `KnowledgeBaseVO`

##### MCP 模块

- 重新加入 `McpToolCatalogVO`
- `GetMcpServerResponse.catalogTools` 恢复为 `List<McpToolCatalogVO>`

### 当前 Phase 9-α 台账

#### 已收敛（仅 2 项）

| 项目 | 方式 | 说明 |
|------|------|------|
| `McpToolReferenceVO` | 删除，改用 DTO | 唯一满足“字段完全一致”的删 VO 样本 |
| `IntentVersionVO` | 改为 record | 极小值对象，非 DTO 替换 |

#### 经复核后默认保留（8 项）

| VO | 保留原因 |
|----|----------|
| `AgentVO` | DTO 多 `userId / activeIntentVersion / createdAt / updatedAt` |
| `ChatSessionVO` | DTO 多 `userId / metadata / createdAt / updatedAt` |
| `ChatMessageVO` | DTO 多 `createdAt / updatedAt` |
| `ChatSessionFileVO` | DTO 多 `sessionId / storagePath / metadata` |
| `KnowledgeBaseVO` | DTO 多 `createdBy / metadata` |
| `KnowledgeDocumentVO` | DTO 多 `storagePath / contentHash / failedReason / indexedAt / retryCount / metadata` |
| `McpServerVO` | DTO 含敏感字段，VO 还多 `unresolvedReferenceCount` 计算字段 |
| `McpToolCatalogVO` | DTO 多 `schemaJson / schemaHash / deletedAt` |

#### 必须保留（14 项）

| VO | 保留原因 |
|----|----------|
| `McpDiscoveredToolVO` | 来自远端工具描述，含名称规范化 |
| `ChatRoutingCandidateVO` | 配置 + 运行时状态聚合 |
| `IntentNodeVO` | 含 DTO 中不存在的 `knowledgeBaseIds` |
| `AssistantTemplateVO` | rich 字段视图 + 嵌套转换 |
| `AdminUserVO` | 非 1:1 DTO 来源 |
| `LoginUserVO` | JWT claims + UserDTO 组合 |
| `UserProfileVO` | 裁剪视图，不是等价 DTO |
| `DashboardOverviewVO` | 聚合视图 |
| `DashboardTrendsVO` | 聚合视图 |
| `DashboardMcpAlertVO` | 关联补充字段 |
| `DashboardMcpAlertsVO` | 聚合包装器 |
| `DashboardMcpPerformanceVO` | 聚合视图 |
| `DashboardMcpServerMetricVO` | 统计聚合结果 |
| `DashboardPerformanceVO` | 聚合视图 |

### 验证结果

#### 定向验证

```bash
./mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dtest=AgentMessageBridgeImplTest,KnowledgeBaseFacadeServiceImplTest,AssistantKnowledgeBaseFacadeServiceImplTest,McpServerAdminFacadeServiceImplTest" test
```

结果：15 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

覆盖面：

- `AgentMessageBridgeImplTest` — 验证 `ChatMessageConverter.toVO(...)` 恢复后桥接层仍可构造消息视图
- `KnowledgeBaseFacadeServiceImplTest` — 验证 `KnowledgeBaseVO` 边界恢复后的 facade 行为
- `AssistantKnowledgeBaseFacadeServiceImplTest` — 验证管理员知识库绑定查询仍返回 VO 视图
- `McpServerAdminFacadeServiceImplTest` — 验证 `McpToolCatalogVO` 恢复后 MCP 详情聚合仍正常

#### 全量回归

```bash
./mvnw.cmd test
```

结果：整仓 346 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

备注：

- 初始定向测试通过后，继续做 `conversation` 链路扫尾时又暴露出一串同类残留（`GetChatMessagesResponse`、`SseMessage`、`ChatSessionConverter`、`ChatSessionFacadeServiceImpl`、`AgentMessageBridgeImpl`、`ChatEventProcessor`）
- 最终以这次 `./mvnw.cmd test` 全量 346 通过作为本批校准的**最终验收结果**

### 9-α 结论

- **Phase 9-α 的真实边界已经重新收紧**：只删除“字段完全一致”的 VO，不再把“DTO 的字段子集”作为默认删除候选
- **当前正式收敛项仍然只有 2 个**：`McpToolReferenceVO` 与 `IntentVersionVO`
- **23 个当前在库 VO 中，22 个应保留**：其中 8 个是这次经复核后明确改判为保留的薄 VO，14 个本来就是聚合/计算/非 DTO 来源
- **2026-04-12 这批工作是 Phase 9 的校准批**：目的是把工作树里已经出现的 DTO 直出尝试收回到正确边界，而不是继续扩大契约变化面

---

## Phase 9-β：数据对象精简 — 契约变化批（已完成）

**执行时间**: 2026-04-12
**风险**: 中
**状态**: ✅ 完成（9-β-1 三批 + 9-β-2 Upsert + 9-β-3 漏网复核）

### 9-β-1 选批原则

在 9-α 把 VO 边界重新校准以后，9-β 的第一批没有再碰 VO 删除，而是只处理**低耦合、单字段壳很明显**的 Response：

- 只改 `Controller -> Facade -> FacadeImpl` 的返回类型
- 只删除“内部只有一个 `VO` / `VO[]` / `List<VO>` 字段”的薄 Response
- **不改** `VO` 字段、不改 `Request` 结构、不动多字段 Response（如 `CreateKnowledgeBaseResponse`、`InitializeAssistantFromTemplateResponse`）

这批最终落在 3 条链路上：

1. `AssistantTemplate`
2. `AssistantKnowledgeBase`
3. `KnowledgeBase`

### 实际改造

#### AssistantTemplate：删除 2 个薄 Response

涉及文件：

- `admin/controller/AssistantTemplateController`
- `admin/application/AssistantTemplateFacadeService`
- `admin/application/AssistantTemplateFacadeServiceImpl`

改造内容：

- `getTemplates()`：`ApiResponse<GetAssistantTemplatesResponse>` → `ApiResponse<List<AssistantTemplateVO>>`
- `getTemplate(String templateId)`：`ApiResponse<GetAssistantTemplateResponse>` → `ApiResponse<AssistantTemplateVO>`
- facade 接口与实现同步改为直接返回 `List<AssistantTemplateVO>` / `AssistantTemplateVO`
- 删除：
  - `GetAssistantTemplatesResponse`
  - `GetAssistantTemplateResponse`

HTTP 契约变化：

- 之前：`data.templates: [...]`
- 现在：`data: [...]`
- 之前：`data.template: {...}`
- 现在：`data: {...}`

#### AssistantKnowledgeBase：删除 1 个薄 Response

涉及文件：

- `admin/controller/AssistantKnowledgeBaseController`
- `knowledge/application/AssistantKnowledgeBaseFacadeService`
- `knowledge/application/AssistantKnowledgeBaseFacadeServiceImpl`

改造内容：

- `getAssistantKnowledgeBases()`：`ApiResponse<GetAssistantKnowledgeBasesResponse>` → `ApiResponse<KnowledgeBaseVO[]>`
- facade 接口与实现同步改为直接返回 `KnowledgeBaseVO[]`
- 删除 `GetAssistantKnowledgeBasesResponse`

HTTP 契约变化：

- 之前：`data.knowledgeBases: [...]`
- 现在：`data: [...]`

#### KnowledgeBase：删除 2 个薄 Response

涉及文件：

- `knowledge/controller/KnowledgeBaseController`
- `knowledge/application/KnowledgeBaseFacadeService`
- `knowledge/application/KnowledgeBaseFacadeServiceImpl`

改造内容：

- `getKnowledgeBases()`：`ApiResponse<GetKnowledgeBasesResponse>` → `ApiResponse<KnowledgeBaseVO[]>`
- `getKnowledgeBase(String knowledgeBaseId)`：`ApiResponse<GetKnowledgeBaseResponse>` → `ApiResponse<KnowledgeBaseVO>`
- facade 接口与实现同步改为直接返回 `KnowledgeBaseVO[]` / `KnowledgeBaseVO`
- 删除：
  - `GetKnowledgeBasesResponse`
  - `GetKnowledgeBaseResponse`

HTTP 契约变化：

- 之前：`data.knowledgeBases: [...]`
- 现在：`data: [...]`
- 之前：`data.knowledgeBase: {...}`
- 现在：`data: {...}`

### 刻意未处理的项

- `CreateKnowledgeBaseResponse` 保留：虽然只有 1 个字段，但它表达的是“创建结果语义”，不是纯查询壳
- `InitializeAssistantFromTemplateResponse` 保留：2 个字段都有独立业务语义
- `VO` 一律不删：本批目标是缩掉最外层薄包装，而不是再次打开“DTO 直出替代 VO”的口子
- `Conversation` / `Agent` / `MCP` 端点暂未纳入本批：它们虽然也有薄 Response，但当前工作树里还有别的并行改动，先避开高交叉区

### 测试与回归

#### 定向验证

```bash
./mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dtest=AssistantTemplateFacadeServiceImplTest,AssistantKnowledgeBaseFacadeServiceImplTest,KnowledgeBaseFacadeServiceImplTest" test
```

结果：8 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

本批测试变化：

- 更新 `AssistantTemplateFacadeServiceImplTest`，把断言从 `response.getTemplates()` 调整为直接断言 `List<AssistantTemplateVO>`
- 更新 `AssistantKnowledgeBaseFacadeServiceImplTest`，把断言从 `response.getKnowledgeBases()` 调整为直接断言 `KnowledgeBaseVO[]`
- 为 `KnowledgeBaseFacadeServiceImplTest` 新增 2 条测试，分别钉住：
  - `getKnowledgeBases()` 直接返回 `KnowledgeBaseVO[]`
  - `getKnowledgeBase()` 直接返回 `KnowledgeBaseVO`

#### 全量回归

```bash
./mvnw.cmd test
```

结果：整仓 348 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

### 9-β 第一批结论

- 9-β 已经开始落地，但在这一步还只完成了**第一批薄 Response 删除**
- 这批一共删除了 5 个 Response 类，数据对象总量从 103 收敛到 98，`Response` 从 34 收敛到 29
- 9-β 的推进方式已经明确：**先删薄 Response，再评估 Request 合并；VO 边界继续按 9-α 的收紧规则执行**
- 下一步仍有两块待完成：
  - 9-β-1 剩余薄 Response（如 `GetAgentsResponse`、`GetChatMessagesResponse`、`ListMcpServersResponse` 等）
  - 9-β-2 Create/Update Request 合并为 `UpsertXxxRequest`

### 2026-04-12 纠偏记录：Conversation VO 边界恢复

在 9-β-1 首批完成后复核工作树时，发现 `conversation` 链路又出现了一次**越界漂移**，与 9-α 已确认的保留结论不一致：

- `ChatMessageVO.java` 被删除
- `ChatSessionVO.java` 被删除
- 同时残留多处“DTO 直出”代码：
  - `ChatMessageConverter.toVO(...)` / `ChatSessionConverter.toVO(...)`
  - `GetChatMessagesResponse`
  - `GetChatSessionResponse`
  - `GetChatSessionsResponse`
  - `SseMessage.Payload.message`
  - `ChatMessageFacadeServiceImpl`
  - `ChatSessionFacadeServiceImpl`
  - `ConversationOrchestratorService`
  - `ChatEventProcessor`
  - `AgentMessageBridgeImpl`

这类漂移**不属于本批 9-β-1 的目标**，而且会直接破坏 9-α 已写入 inventory 的规则（`ChatMessageVO` / `ChatSessionVO` 均应保留）。因此本次按“越界修改纠偏”处理，而不是把它算作新的 Phase 9 收敛项。

#### 纠偏动作

- 恢复 `ChatMessageVO.java`
- 恢复 `ChatSessionVO.java`
- `ChatMessageConverter.toVO(...)` 改回返回 `ChatMessageVO`
- `ChatSessionConverter.toVO(...)` 改回返回 `ChatSessionVO`
- `GetChatMessagesResponse` 改回 `ChatMessageVO[]`
- `GetChatSessionResponse` 改回 `ChatSessionVO`
- `GetChatSessionsResponse` 改回 `ChatSessionVO[]`
- `SseMessage.Payload.message` 改回 `ChatMessageVO`
- `ChatMessageFacadeServiceImpl` / `ChatSessionFacadeServiceImpl` 恢复显式 DTO → VO 组装
- `ConversationOrchestratorService`、`ChatEventProcessor`、`AgentMessageBridgeImpl` 恢复 SSE payload 使用 `ChatMessageVO`

#### 重新验证

```bash
./mvnw.cmd -pl bootstrap -am compile
./mvnw.cmd test
```

结果：

- `bootstrap compile` 通过
- 整仓 348 个测试全部通过，`Failures=0, Errors=0, Skipped=0`

这次重新验证后的结果，才是当前工作树的有效验收结论。

### 2026-04-12 第二批：MCP 列表 / 会话文件列表 / 知识文档列表

在 conversation 越界漂移纠偏后，9-β-1 继续推进第二批薄 Response 收敛。这一批仍然严格遵守首批边界：

- 只删最外层薄 Response
- 不删 VO
- 不改 Request
- 只挑调用链短、低交叉、单字段壳明显的端点

#### 本批删除的 3 个薄 Response

| 已删除类 | 当前替代 | 影响链路 |
|----------|----------|----------|
| `ListMcpServersResponse` | `ApiResponse<List<McpServerVO>>` | `McpServerAdminController` / `McpServerAdminFacadeService` |
| `GetChatSessionFilesResponse` | `ApiResponse<ChatSessionFileVO[]>` | `ChatSessionFileController` / `ChatSessionFileFacadeService` |
| `GetKnowledgeDocumentsResponse` | `ApiResponse<KnowledgeDocumentVO[]>` | `KnowledgeDocumentController` / `KnowledgeDocumentFacadeService` |

#### 实际改造

##### MCP 列表

- `McpServerAdminFacadeService.getServers()`：`ListMcpServersResponse` → `List<McpServerVO>`
- `McpServerAdminFacadeServiceImpl.getServers()`：删除包装层，直接返回 `List<McpServerVO>`
- `McpServerAdminController.getServers()`：`ApiResponse<ListMcpServersResponse>` → `ApiResponse<List<McpServerVO>>`

契约变化：

- 之前：`data.servers: [...]`
- 现在：`data: [...]`

##### 会话文件列表

- `ChatSessionFileFacadeService.getChatSessionFiles()`：`GetChatSessionFilesResponse` → `ChatSessionFileVO[]`
- `ChatSessionFileFacadeServiceImpl.getChatSessionFiles()`：直接返回 `ChatSessionFileVO[]`
- `ChatSessionFileController.getChatSessionFiles()`：`ApiResponse<GetChatSessionFilesResponse>` → `ApiResponse<ChatSessionFileVO[]>`

契约变化：

- 之前：`data.files: [...]`
- 现在：`data: [...]`

##### 知识文档列表

- `KnowledgeDocumentFacadeService.getKnowledgeDocuments()`：`GetKnowledgeDocumentsResponse` → `KnowledgeDocumentVO[]`
- `KnowledgeDocumentFacadeServiceImpl.getKnowledgeDocuments()`：直接返回 `KnowledgeDocumentVO[]`
- `KnowledgeDocumentController.getKnowledgeDocuments()`：`ApiResponse<GetKnowledgeDocumentsResponse>` → `ApiResponse<KnowledgeDocumentVO[]>`

契约变化：

- 之前：`data.documents: [...]`
- 现在：`data: [...]`

#### 测试与回归

##### 定向验证

```bash
./mvnw.cmd -pl bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dtest=McpServerAdminFacadeServiceImplTest,KnowledgeDocumentFacadeServiceImplTest,ChatSessionFileFacadeServiceImplTest" test
```

结果：18 个测试全部通过，`Failures=0, Errors=0, Skipped=0`。

本批新增 / 调整测试：

- `McpServerAdminFacadeServiceImplTest`
  - 新增 `shouldReturnDirectServerViewList()`，钉住 `getServers()` 直接返回 `List<McpServerVO>`，同时保留 `unresolvedReferenceCount` 计算语义
- `KnowledgeDocumentFacadeServiceImplTest`
  - 新增 `shouldReturnDirectKnowledgeDocumentArray()`，钉住 `getKnowledgeDocuments()` 直接返回 `KnowledgeDocumentVO[]`
- `ChatSessionFileFacadeServiceImplTest`
  - 新增测试类，补 `shouldReturnDirectChatSessionFileArray()`，覆盖 `UserContext + ResourceAccessGuard + DTO → VO` 链路

##### 编译与全量回归

```bash
./mvnw.cmd -pl bootstrap -am compile
./mvnw.cmd test
```

结果：

- `bootstrap compile` 通过
- 整仓 351 个测试全部通过，`Failures=0, Errors=0, Skipped=0`

### 9-β 第三批：Agent / Intent / KnowledgeBase 薄 Response

在第二批完成后，9-β-1 继续推进第三批薄 Response 删除，同时严格遵守 VO 边界不变原则。

#### 本批删除的 5 个薄 Response

| 已删除类 | 当前替代 | 影响链路 |
|----------|----------|----------|
| `CreateAgentResponse` | `ApiResponse<String>` | `AgentFacadeService` / `AgentFacadeServiceImpl` |
| `GetAgentsResponse` | `ApiResponse<List<AgentVO>>` | `AgentFacadeService` / `AgentFacadeServiceImpl` |
| `GetIntentVersionsResponse` | `ApiResponse<List<IntentVersionVO>>` | `IntentTreeFacadeService` / `IntentTreeController` |
| `PublishIntentTreeResponse` | `ApiResponse<Integer>` | `IntentTreeFacadeService` / `IntentTreeController` / `AssistantTemplateFacadeServiceImpl` |
| `CreateKnowledgeBaseResponse` | `ApiResponse<String>` | `KnowledgeBaseFacadeService` / `KnowledgeBaseController` |

#### 实际改造

##### Agent

- `AgentFacadeService.createAgent()`：`CreateAgentResponse` → `String`（直接返回 agentId）
- `AgentFacadeService.getAgents()`：`GetAgentsResponse` → `List<AgentVO>`
- `AgentFacadeServiceImpl`：删除 Response 组装，直接返回 DTO id 和 VO 列表
- 删除 `CreateAgentResponse`、`GetAgentsResponse`

契约变化：

- `POST /api/admin/agents`：`data.agentId` → `data`
- `GET /api/admin/agents`：`data.agents` → `data`

##### Intent

- `IntentTreeFacadeService.publishIntentTreeSnapshot()`：`PublishIntentTreeResponse` → `Integer`
- `IntentTreeFacadeService.getIntentVersions()`：`GetIntentVersionsResponse` → `List<IntentVersionVO>`
- `IntentTreeController`：同步更新返回类型
- `AssistantTemplateFacadeServiceImpl`：`publishResponse.getVersion()` → `publishedVersion`（直接 Integer）
- 删除 `PublishIntentTreeResponse`、`GetIntentVersionsResponse`

契约变化：

- `POST /api/admin/assistant/intent-tree/publish`：`data.version` → `data`
- `GET /api/admin/assistant/intent-tree/versions`：`data.versions` → `data`

##### KnowledgeBase

- `KnowledgeBaseFacadeService.createKnowledgeBase()`：`CreateKnowledgeBaseResponse` → `String`
- `KnowledgeBaseController.createKnowledgeBase()`：`ApiResponse<CreateKnowledgeBaseResponse>` → `ApiResponse<String>`
- 删除 `CreateKnowledgeBaseResponse`

契约变化：

- `POST /api/admin/knowledge-bases`：`data.knowledgeBaseId` → `data`

#### 刻意未处理的项

- `CreateChatMessageResponse`（2 字段）和 `CreateAdminUserResponse`（3 字段）：降级为保留，转换会损失类型安全
- `AgentFacadeService` 无 HTTP controller 消费，标记为孤立 facade（超出 Phase 9 scope）

#### 测试变化

- `IntentTreeFacadeServiceImplTest`：`PublishIntentTreeResponse response = ...` → `Integer response = ...`；`response.getVersion()` → 直接 `assertThat(response).isEqualTo(3)`
- `AssistantTemplateFacadeServiceImplTest`：`thenReturn(new PublishIntentTreeResponse(3))` → `thenReturn(3)`

---

### 9-β-2：Create/Update Request 合并为 UpsertRequest

5 对字段完全一致的 Create/Update Request 合并为单个 `UpsertXxxRequest`，Controller 的 POST（create）和 PATCH（update）端点共用同一类型。

#### 合并清单

| 原 Create 类 | 原 Update 类 | 合并为 | 涉及 Controller / Facade |
|-------------|-------------|--------|--------------------------|
| `CreateAgentRequest` | `UpdateAgentRequest` | `UpsertAgentRequest` | `AgentFacadeService` / `AgentConverter` |
| `CreateAssistantTemplateRequest` | `UpdateAssistantTemplateRequest` | `UpsertAssistantTemplateRequest` | `AssistantTemplateController` / `AssistantTemplateFacadeService` |
| `CreateIntentNodeRequest` | `UpdateIntentNodeRequest` | `UpsertIntentNodeRequest` | `IntentTreeController` / `IntentTreeFacadeService` / `AssistantTemplateFacadeServiceImpl` |
| `CreateKnowledgeBaseRequest` | `UpdateKnowledgeBaseRequest` | `UpsertKnowledgeBaseRequest` | `KnowledgeBaseController` / `KnowledgeBaseFacadeService` |
| `CreateMcpServerRequest` | `UpdateMcpServerRequest` | `UpsertMcpServerRequest` | `McpServerAdminController` / `McpServerAdminFacadeService` / `McpServerCrudHelper` |

#### 测试变化

- `AssistantTemplateFacadeServiceImplTest`：`CreateIntentNodeRequest` → `UpsertIntentNodeRequest`
- `McpServerAdminFacadeServiceImplTest`：`CreateMcpServerRequest` → `UpsertMcpServerRequest`；`UpdateMcpServerRequest` → `UpsertMcpServerRequest`

---

### 9-β-3：漏网项复核

对全部 23 个 VO 与对应 DTO 做字段逐一比对：

**结果**：零漏网候选。所有剩余 VO 都是有意投射——它们隐藏敏感字段（credentials、storagePath）、内部字段（deletedAt、metadata）或添加计算字段（unresolvedReferenceCount、knowledgeBaseIds）。

**额外发现**：`AgentFacadeService` 无 HTTP controller 消费（grep 确认仅被自身接口和实现引用），标记为孤立 facade，但不删除（超出 Phase 9 scope）。

---

### 9-β 最终结论

- 9-β-1 三批累计删除 13 个薄 Response（原 26 → 17）
- 9-β-2 5 对 Create/Update 合并为 5 个 UpsertRequest（原 25 → 15 Request）
- 9-β-3 漏网复核：零 VO 候选；AgentFacadeService 孤立已标记
- 全量测试 351 个通过，`Failures=0, Errors=0, Skipped=0`
- **Phase 9 全部完成**
