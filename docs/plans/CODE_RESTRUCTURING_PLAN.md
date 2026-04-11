# ChatAgent 代码结构重构落地计划

> 状态：✅ 已完成（分支 `reconstruct/code-reconstruction`）
> 最后更新：2026-04-11
> 执行记录：[CODE_RESTRUCTURING_EXECUTION.md](../arch/CODE_RESTRUCTURING_EXECUTION.md)
> 前置条件：无（独立执行）
> 目标产出：结构更清晰、职责更明确、维护成本更低的代码组织

---

## 1. 概述

### 1.1 动机

当前 `bootstrap` 模块承载了 534 个 Java 源文件、13 个业务领域，存在以下结构性问题：

1. **admin/ 包越界**（85 文件）— MCP 工具类、Intent 控制器等被错误归入 admin
2. **双重持久化模式** — `user/infrastructure/` 与 `support/persistence/` 两套风格并存
3. **上帝类** — `PdfDocumentParser`（1640 行）、`McpServerAdminFacadeServiceImpl`（523 行）、`DashboardFacadeServiceImpl`（~588 行）
4. **数据对象膨胀** — ~148 个 DTO/VO/Entity/Request/Response，大量近乎相同的三元组
5. **命名不一致** — 有的领域用 `application/`，有的用 `service/impl/`
6. **根目录垃圾文件** — `commit_msg.txt`、96MB 的 `MCP/.venv/` 已入库
7. **chat/ 包分裂** — 同时存在于 `infra` 和 `bootstrap` 两个模块
8. **Framework 模块零测试**

### 1.2 约束

1. **不破坏项目结构** — 所有现有 API、端点、Spring Bean 注册保持不变
2. **增量交付** — 每个 Phase 独立可合并、可回滚
3. **不新增 Maven 模块** — 不拆分 bootstrap，当前单模块结构可接受
4. **保留六边形边界** — port/adapter 分离原则不变
5. **Git 友好** — 每步重构一个小而可审查的 commit

### 1.3 总览

| Phase | 内容 | 工时 | 风险 | 实际结果 |
|-------|------|------|------|----------|
| 0 | 根目录清理 & Git 卫生 | 0.5h | 零 | ✅ 完成 |
| 1 | MCP 工具类归位 | 1d | 低 | ✅ 完成 |
| 2 | Intent 控制器 + Agent 端口归位 | 1d | 低 | ✅ 完成 |
| 3 | RAG 命名统一 | 0.5d | 低 | ✅ 完成 |
| 4 | 双重持久化统一 | 1.5d | 中 | ✅ 完成 |
| 5 | 上帝类拆分 | 2d | 中 | ✅ 完成（部分调整） |
| 6 | 数据对象精简 | 1.5d | 中 | ✅ 完成（6A 部分，6B 跳过） |
| 7 | chat 包分裂修复 + Framework 测试 | 1d | 低 | ✅ 完成 |
| 8 | PdfDocumentParser 深度拆分（VDP 调度管线） | 2d | 中高 | ✅ 完成（3 批；2 项外因驱动演化移至 §12） |
| 11 | 单实现接口逐案评估 | 1.5d | 低 | ✅ 完成（全量评估台账；收敛 4 个应用服务接口） |
| 9-α | 数据对象精简（契约不变批） | 0.5d | 低 | ✅ 完成（McpToolReferenceVO 删除 + IntentVersionVO 改 record；其余 VO 候选经复核默认保留） |

**总工时**：约 9 个工作日（Phase 0–7）+ 4 个工作日（Phase 8 + 11 + 9-α 已完成部分）。**推荐执行顺序**：Phase 0 → 1 → 3 → 2 → 4 → 7 → 5 → 6 → **11 → 8 → 9-α → 9-β → 10**。当前进度停在 Phase 9-α 结项，下一步为 Phase 9-β（契约变化批，需前端联调）或 Phase 10（依赖三角解耦）。

---

## 2. Phase 0：根目录清理 & Git 卫生

**工时**：30 分钟 | **风险**：零

### 2.1 动作

| # | 动作 | 说明 |
|---|------|------|
| 1 | 删除 `commit_msg.txt` | 根目录临时文件，42 字节 |
| 2 | 删除 `commit_raw.txt` | 根目录临时文件，464 字节 |
| 3 | 将 `MCP/.venv/` 从 Git 追踪中移除 | `.gitignore` 已有 `**/.venv/` 规则（第 26 行），但该目录在规则添加前已提交。执行 `git rm -r --cached "MCP/.venv/"` 解除追踪，释放 96MB 工作副本 |
| 4 | 确认 `.gitignore` 覆盖 `output/` 目录 | 当前仅忽略 `output.txt`，未忽略 `output/` 目录 |
| 5 | 扫描硬编码密钥 | 检查 `application.yml` / `application-*.yml` 中是否有明文密码或 API Key 应改为环境变量 |

### 2.2 验证

```bash
git status --short    # 仅应显示删除和 .gitignore 变更
mvn clean compile     # 编译必须通过
```

---

## 3. Phase 1：MCP 工具类归位

**工时**：1 天 | **风险**：低（纯包移动，无逻辑变更）

5 个 MCP 领域工具类当前放在 `admin/application/`，与 admin 编排职责无关，应归入 `mcp/application/`。

### 3.1 文件移动

| # | 移动前 | 移动后 |
|---|--------|--------|
| 1 | `admin/application/McpFeatureFlag.java` | `mcp/application/McpFeatureFlag.java` |
| 2 | `admin/application/McpCredentialCipher.java` | `mcp/application/McpCredentialCipher.java` |
| 3 | `admin/application/McpServerStatusMachine.java` | `mcp/application/McpServerStatusMachine.java` |
| 4 | `admin/application/McpToolNameNormalizer.java` | `mcp/application/McpToolNameNormalizer.java` |
| 5 | `admin/application/McpServerReferenceInspector.java` | `mcp/application/McpServerReferenceInspector.java` |

### 3.2 测试文件同步移动

| # | 移动前 | 移动后 |
|---|--------|--------|
| 1 | `admin/application/McpFeatureFlagTest.java` | `mcp/application/McpFeatureFlagTest.java` |
| 2 | `admin/application/McpCredentialCipherTest.java` | `mcp/application/McpCredentialCipherTest.java` |
| 3 | `admin/application/McpServerStatusMachineTest.java` | `mcp/application/McpServerStatusMachineTest.java` |
| 4 | `admin/application/McpToolNameNormalizerTest.java` | `mcp/application/McpToolNameNormalizerTest.java` |

### 3.3 每个文件的操作步骤

1. `git mv` 移动文件
2. 更新 `package` 声明：`com.yulong.chatagent.admin.application` → `com.yulong.chatagent.mcp.application`
3. 全局更新 import 语句（主要消费方：`McpServerAdminFacadeServiceImpl`、`DashboardFacadeServiceImpl`）

### 3.4 验证

```bash
mvn clean compile
mvn test -pl bootstrap
grep -r "admin\.application\.Mcp" bootstrap/src/   # 预期：零结果
```

---

## 4. Phase 2：Intent 控制器 + Agent 端口归位

**工时**：1 天 | **风险**：低

### 4A：移动 IntentTreeController

`IntentTreeController` 放在 `admin/controller/` 中，但它仅依赖 `IntentTreeFacadeService` 和 Intent 模型，是纯 Intent 领域关注点。

| 移动前 | 移动后 |
|--------|--------|
| `admin/controller/IntentTreeController.java` | `intent/controller/IntentTreeController.java` |

需要创建 `intent/controller/` 目录。

### 4B：移动 Agent 相关端口

`AgentRepository`、`AgentKnowledgeBaseRepository`、`AssistantTemplateRepository` 放在 `admin/port/`，但它们被 7+ 个文件跨多个领域消费（agent、admin、intent）。Agent 有自己的有界上下文 `agent/`，端口应归位：

| 移动前 | 移动后 |
|--------|--------|
| `admin/port/AgentRepository.java` | `agent/port/AgentRepository.java` |
| `admin/port/AgentKnowledgeBaseRepository.java` | `agent/port/AgentKnowledgeBaseRepository.java` |
| `admin/port/AssistantTemplateRepository.java` | `agent/port/AssistantTemplateRepository.java` |

主要消费方（需更新 import）：
- `admin/application/AgentFacadeServiceImpl`
- `admin/application/AssistantTemplateFacadeServiceImpl`
- `agent/application/InternalAssistantService`
- `agent/runtime/AgentDefinitionLoader`
- `agent/runtime/AgentSessionFileSummaryResolver`
- `intent/application/DefaultIntentTreeCacheManager`
- `intent/application/IntentTreeFacadeServiceImpl`

### 4.1 验证

```bash
mvn clean compile
mvn test -pl bootstrap
```

---

## 5. Phase 3：RAG 命名统一

**工时**：0.5 天 | **风险**：低

`rag/` 包使用旧式 `service/` + `service/impl/` 模式，而其他所有领域统一使用 `application/`。

### 5.1 文件移动

| 移动前 | 移动后 |
|--------|--------|
| `rag/service/RagService.java` | `rag/application/RagService.java` |
| `rag/service/impl/RagServiceImpl.java` | `rag/application/RagServiceImpl.java` |
| `rag/service/DocumentStorageService.java` | `rag/application/DocumentStorageService.java` |
| `rag/service/impl/DocumentStorageServiceImpl.java` | `rag/application/DocumentStorageServiceImpl.java` |
| `rag/service/MarkdownParserService.java` | `rag/application/MarkdownParserService.java` |
| `rag/service/impl/MarkdownParserServiceImpl.java` | `rag/application/MarkdownParserServiceImpl.java` |
| `rag/service/RetrievalHitFormatter.java` | `rag/application/RetrievalHitFormatter.java` |
| `rag/service/FormattedRetrievalPrompt.java` | `rag/application/FormattedRetrievalPrompt.java` |

移动后删除空目录 `rag/service/impl/` 和 `rag/service/`。

### 5.2 验证

```bash
mvn clean compile
mvn test -pl bootstrap
```

---

## 6. Phase 4：双重持久化统一

**工时**：1.5 天 | **风险**：中（触及持久化层）

### 6.1 问题

当前两套持久化风格并存：

- **模式 A（集中式）**：`support/persistence/adapter/{domain}/` + `support/persistence/entity/` + `support/persistence/mapper/`
- **模式 B（嵌入式）**：`user/infrastructure/persistence/adapter/` + `user/infrastructure/persistence/entity/` + `user/infrastructure/persistence/mapper/`

目标：统一到模式 A。

### 6.2 文件移动

**实体类**：

| 移动前 | 移动后 |
|--------|--------|
| `user/infrastructure/persistence/entity/User.java` | `support/persistence/entity/User.java` |
| `user/infrastructure/persistence/entity/UserProfile.java` | `support/persistence/entity/UserProfile.java` |

**Mapper 接口**：

| 移动前 | 移动后 |
|--------|--------|
| `user/infrastructure/persistence/mapper/UserMapper.java` | `support/persistence/mapper/UserMapper.java` |
| `user/infrastructure/persistence/mapper/UserProfileMapper.java` | `support/persistence/mapper/UserProfileMapper.java` |

**Adapter 实现**：

| 移动前 | 移动后 |
|--------|--------|
| `user/infrastructure/persistence/adapter/MyBatisUserRepository.java` | `support/persistence/adapter/user/MyBatisUserRepository.java` |
| `user/infrastructure/persistence/adapter/MyBatisUserProfileRepository.java` | `support/persistence/adapter/user/MyBatisUserProfileRepository.java` |

**缓存基础设施**（可选保留）：

| 移动前 | 移动后 |
|--------|--------|
| `user/infrastructure/cache/RedisRefreshTokenStore.java` | 保留在 `user/infrastructure/cache/`（Redis 特有，非 MyBatis） |

移动后删除空目录：
- `user/infrastructure/persistence/adapter/`
- `user/infrastructure/persistence/entity/`
- `user/infrastructure/persistence/mapper/`
- `user/infrastructure/persistence/`（若为空）

### 6.3 注意事项

- XML Mapper 文件（`UserMapper.xml`、`UserProfileMapper.xml`）已在 `resources/mapper/`，无需移动
- MyBatis XML 中的 `resultType` 如果引用了 entity 全路径，需同步更新
- 所有 `user.infrastructure.persistence.*` 的 import 需全局替换为 `support.persistence.*`

### 6.4 验证

```bash
mvn clean compile
mvn test -pl bootstrap
```

---

## 7. Phase 5：上帝类拆分

**工时**：2 天 | **风险**：中（行为变更，需仔细测试）

### 5A：拆分 PdfDocumentParser（1640 行）

**实际落地**：

| 新类 | 职责 | 行数 |
|------|------|------|
| `PdfDocumentParser.java`（瘦身） | VDP 调度管线、缓存、批处理、Segment 构建 | ~1189 |
| `PdfPageTextExtractor.java` | 逐页文本提取、标题检测、字体分析 | ~300 |
| `PdfPageRenderer.java` | PDF 转图片渲染、DPI 配置 | ~80 |
| `PdfQualityRouter.java` | `PageRoutingDecision`、路由规划、字符密度、空白分析 | ~200 |

> **偏差说明**：`PdfVisualDispatchEngine` 未单独提取，VDP 调度管线（~500 行）因与缓存、批处理、超时处理紧密耦合保留在 `PdfDocumentParser` 中。实际从 1640 行减至 1189 行。

### 5B：拆分 McpServerAdminFacadeServiceImpl（523 行 → ~390 行）

**实际落地**：

| 新类 | 职责 |
|------|------|
| `McpServerCrudHelper` | Create/Update DTO 组装、字段映射、slug 生成、save/update 委托 |
| `McpServerDeleteHandler` | 删除编排：引用检查 → Intent 清理 → Catalog 清理 → 服务器删除 → 告警处理 |

### 5C：拆分 DashboardFacadeServiceImpl（588 行 → 425 行）

**实际落地**：

| 新类 | 职责 |
|------|------|
| `DashboardOverviewAggregator` | overview KPI 聚合（会话数、用户数、消息数及环比） |
| `DashboardMcpMetricsComposer` | MCP 性能指标 + 告警查询 + 服务器指标聚合 |

> **偏差说明**：原计划提取 4 个独立 Aggregator，实际合并为 2 个。`DashboardTrendsAggregator` 因与 private enum 紧密耦合跳过。`DashboardMcpPerformanceAggregator` 和 `DashboardMcpAlertAggregator` 合并为 `DashboardMcpMetricsComposer`。

### 5.4 验证

```bash
mvn clean compile
mvn test -pl bootstrap
# 集成验证：手动访问 /api/admin/dashboard、/api/admin/mcp-servers 端点
```

---

## 8. Phase 6：数据对象精简

**工时**：1.5 天 | **风险**：中（触及转换逻辑）

### 6A：消除 Entity 层

**当前转换链**：`DB Row → Entity → DTO → VO → Response`

**目标转换链**：`DB Row → DTO → Response`

Entity（`support/persistence/entity/`）仅在 MyBatis Adapter 内部使用，字段与 DTO 一一对应。策略：将 Entity 字段合并到对应 DTO，更新 MyBatis XML `<resultMap>` 直接映射到 DTO 类，删除 Entity 类。

**优先级排序**（从最简单开始，每批 3-4 个 Entity）：

| 批次 | Entity → 合并目标 | 实际结果 | 备注 |
|------|-------------------|----------|------|
| 1 | `McpServer` → `McpServerDTO` | ✅ 合并 | 字段 1:1 |
|   | `McpToolCatalog` → `McpToolCatalogDTO` | ✅ 合并 | — |
|   | `McpAlertEvent` → `McpAlertEventDTO` | ✅ 合并 | — |
| 2 | `Agent` → `AgentDTO`（删除 `AgentConverter`） | ❌ 保留 Entity | Converter 做 model↔enum、tools JSON↔List、chatOptions JSON↔Object 转换 |
|   | `AgentTemplate` → `AssistantTemplateDTO` | ✅ 合并 | JSON 字段用 populateRichFields/populateJsonFields 处理 |
|   | `AgentKnowledgeBase` → `AgentKnowledgeBaseDTO` | ✅ 合并 | — |
| 3 | `IntentNode` → `IntentNodeDTO` | ✅ 合并 | 新增 JsonStringListTypeHandler |
|   | `IntentKnowledgeBase` → `IntentKnowledgeBaseDTO` | ✅ 合并 | — |
|   | `KnowledgeBase` → `KnowledgeBaseDTO` | ✅ 合并 | — |
| 4 | `KnowledgeDocument` → `KnowledgeDocumentDTO` | ✅ 合并 | — |
|   | `KnowledgeChunk` → `KnowledgeChunkDTO` | ✅ 合并 | — |
|   | `KnowledgeDocumentEnhancement` → `KnowledgeDocumentEnhancementDTO` | ❌ 保留 Entity | DTO 有 List/Map 但 DB 存 JSON String |
| 5 | `ChatMessage` → `ChatMessageDTO`（删除 `ChatMessageConverter`） | ❌ 保留 Entity | Converter 做 role↔enum、metadata JSON↔Object |
|   | `ChatSession` → `ChatSessionDTO`（删除 `ChatSessionConverter`） | ❌ 保留 Entity | Converter 做 metadata JSON↔Object |
|   | `ChatSessionFile` → `ChatSessionFileDTO` | ✅ 合并 | — |
| 6 | `ChatSessionSummary` → `ChatSessionSummaryDTO` | ✅ 合并 | anchoredEntities JSON 转换保留在 Adapter |
|   | `ChatTurnMetric` → 直接 Mapper 消费 | ❌ 保留 Entity | 无 DTO，仅 2 个文件引用 |
|   | `FileChunk` → `FileChunkDTO` | ✅ 合并 | — |
|   | `MqOutbox` → 直接 OutboxRepository 消费 | ❌ 保留 Entity | Entity 即 Port 领域模型，9 个文件引用 |

> **结果统计**：21 个 Entity 中 15 个成功合并进 DTO，6 个因 JSON/enum 类型转换、无 DTO 或领域模型耦合等原因保留。

**每个 Entity 的操作步骤**：
1. 更新 MyBatis XML `<resultMap>` 直接映射到 DTO 类
2. 更新 Adapter 的 `toDTO()` 方法（或移除，如果 resultMap 处理了映射）
3. 删除 Entity 类
4. 删除独立 Converter 类（如有）
5. 全局更新 import

**注意事项**：
- DTO 使用枚举类型（如 `McpServerStatus`），Entity 使用原始字符串，需在 MyBatis XML resultMap 或 Adapter 代码中处理转换
- 使用 `@Builder` 或 Lombok 的 DTO 需确保 MyBatis 能正确实例化

### 6B：合并薄 VO 到 Response类

> **⏭️ 已跳过**：当前 Dashboard 有 4 个独立端点（overview/performance/trends/mcp-alerts），各返回专属 VO，嵌套层次清晰。强行合并所有 VO 到 `DashboardOverviewVO` 会破坏前端 API 契约、创建巨型响应、混合关注点。当前结构已经是合理的职责分离。

### 6.3 验证

```bash
mvn clean compile
mvn test -pl bootstrap
```

---

## 9. Phase 7：chat 包分裂修复 + Framework 测试

**工时**：1 天 | **风险**：低

### 7A：修复 chat/ 包分裂

`chat/` 包同时存在于 `infra` 模块（19 文件：路由、模型选择、流式处理）和 `bootstrap` 模块（2 文件：`ChatModelAvailability`、`ChatModelHttpClientTimeoutConfig`）。

这两个 bootstrap 文件是 `@Configuration` Bean，为 infra 层 chat 客户端提供配置。它们不属于业务逻辑，应放到更明确的位置：

| 移动前 | 移动后 |
|--------|--------|
| `chat/ChatModelAvailability.java` | `support/chat/ChatModelAvailability.java` |
| `chat/ChatModelHttpClientTimeoutConfig.java` | `support/chat/ChatModelHttpClientTimeoutConfig.java` |

移动后删除空的 `chat/` 目录。

### 7B：添加 Framework 模块测试

`framework` 模块（19 文件）当前零测试覆盖。添加基线测试：

| 测试类 | 目标 | 优先级 |
|--------|------|--------|
| `ApiResponseTest` | `ApiResponse` | 高 |
| `BizExceptionTest` | `BizException`、`ClientException`、`ServiceException` | 高 |
| `GlobalExceptionHandlerTest` | `GlobalExceptionHandler` | 高 |
| `UserContextTest` | `UserContext`（ThreadLocal 行为） | 高 |
| `TraceContextTest` | `TraceContext`（ThreadLocal 行为） | 中 |
| `TraceIdFilterTest` | `TraceIdFilter` | 中 |
| `SseEmitterSenderTest` | `SseEmitterSender` | 中 |

### 7.3 验证

```bash
mvn clean compile
mvn test    # 三个模块全部通过
```

---

## 10. 快速胜利（1 小时内可完成）

| # | 动作 | Phase | 预计时间 |
|---|------|-------|----------|
| 1 | 删除 `commit_msg.txt` 和 `commit_raw.txt` | 0 | 2 分钟 |
| 2 | 解除 `MCP/.venv/` Git 追踪 | 0 | 5 分钟 |
| 3 | 移动 `McpFeatureFlag` 到 `mcp/application/` | 1 | 15 分钟 |
| 4 | 重命名 `rag/service/` → `rag/application/` | 3 | 30 分钟 |
| 5 | 移动 `IntentTreeController` 到 `intent/controller/` | 2 | 20 分钟 |

---

## 11. 回滚策略

每个 Phase 是一个独立 commit（或特性分支 + squash merge）。回滚方式：

```bash
git revert <commit-hash>
```

### 11.1 Phase 0–7（已完成）

均为纯结构性变更：包移动、类重命名、import 更新。不引入数据库 schema 变更、API 契约变更或新依赖。单 commit 回滚即可。

### 11.2 Phase 8–11（待执行）— 例外说明

这四个 Phase **不再是纯结构性**，回滚成本和策略需要区别对待：

| Phase | 可能的非结构性变更 | 回滚策略 |
|-------|-------------------|----------|
| 8 | 无 API/依赖变更，但触及性能关键路径 | 单 commit 回滚；**前置要求**：提交前保存 VDP 缓存命中率 + 解析耗时基线，回滚判据包含性能回退 |
| 9 | **可能改变 HTTP 响应体结构**（分类 B/C），属于 API 契约变更 | 按"契约不变 / 契约变化"两批独立 commit，契约变化批回滚需前端同步回退；见 Phase 9 新增的 9-α / 9-β 分批 |
| 10 | **引入 ArchUnit 依赖**（新 test-scope 依赖）+ Spring `ApplicationEventPublisher` 使用扩大 | ArchUnit 单独 commit 可独立回滚；事件机制回滚需同时恢复 `IntentTreeFacadeServiceImpl` 的直接端口依赖 |
| 11 | 无 API 变更，但会动到被测试 Mock 的类型 | 单 commit 回滚；**前置要求**：删除前确认无 `@MockBean` 依赖该接口 |

**通用原则**：Phase 8–11 的每个 commit 在 PR 描述中必须列出"此 commit 是否改变：① API 响应体 ② 依赖项 ③ 性能基线"三项，便于事后快速定位回滚粒度。

---

## 11.5 后续计划：未完全解决项（Phase 8–11）

> 状态：📋 待执行
> 背景：Phase 0–7 解决了"结构性污染"（包归位、持久化统一、命名一致、chat 分裂、Framework 测试），但真正的**内部复杂度**未根治。本节针对四类遗留问题给出可执行的后续方案。
> **推荐执行顺序（单人推进）**：Phase 11 → 8 → 9-α → 9-β → 10
>   - 11 最先：纯评估 + 小规模删除，热身同时梳理接口全景，为后续拆分摸清边界
>   - 8 其次：纯后端改动，风险可控，性能基线一次性建立
>   - 9-α 第三：契约不变批，可独立合入，无需前端联调
>   - 9-β 第四：契约变化批，等前端联调窗口
>   - 10 最后：架构层变更，依赖前面三个 Phase 把"什么是边界 / 什么该共享"澄清清楚
> 基线数据（2026-04-11 实测）：
> - `PdfDocumentParser` 1189 行（Phase 5A 之后）
> - `DashboardFacadeServiceImpl` 425 行
> - `McpServerAdminFacadeServiceImpl` 233 行（Phase 5B + CrudHelper 抽取后已显著瘦身）
> - 数据对象总量：DTO 20 + VO 24 + Response 35 + Request 25 ≈ **104 个**
> - 单实现接口：约 36 个

---

### Phase 8：PdfDocumentParser 深度拆分（VDP 调度管线）

**工时**：2 天 | **风险**：中高（触及核心解析链路，需完整回归）

#### 8.1 问题

Phase 5A 已将渲染（`PdfPageRenderer`）、路由（`PdfQualityRouter`）、文本提取（`PdfPageTextExtractor`）抽出，但 **VDP 调度管线约 500 行**仍留在 `PdfDocumentParser` 中，与缓存、批处理、超时处理紧密耦合。`PdfDocumentParser` 目前仍是 1189 行。

耦合点（Phase 5A 放弃拆分的原因）：
1. **缓存**：按页签名缓存 VDP 结果，跨批次复用
2. **批处理**：按 DPI/内存预算动态切批，失败页回退
3. **超时**：单页/整体双重 deadline，超时后降级为 OCR 或纯文本
4. **Segment 构建**：VDP 输出与文本提取输出合并为统一 Segment 流

#### 8.2 拆分策略（引入显式管线对象）

| 新类 | 职责 | 预计行数 |
|------|------|----------|
| `PdfVdpCache` | 按页签名缓存 VDP 结果（get/put/invalidate），独立于调度 |
| `PdfVdpBatchPlanner` | 根据 DPI 预算 + 页数计算批次大小，纯函数，无状态 |
| `PdfVdpDispatcher` | 单批次的 VDP 调用 + 超时 + 重试 + 失败回退，依赖 Cache 和 Planner |
| `PdfSegmentAssembler` | 合并 VDP 输出与文本提取输出为 Segment 流 |
| `PdfDocumentParser`（最终形态） | 顶层编排：打开 PDF → 路由 → 分派 Dispatcher/TextExtractor → Assembler → 返回 |

> **不设"目标行数"指标**。行数是副产物，不是目的。盲目压行会导致过度抽类（例如为了减 50 行硬拆一个 `PdfContext`），反而增加认知负担。行数只作为**观察指标**（最终 `PdfDocumentParser` 可能落在 400–700 行之间都算合理），验收标准见 8.3。

#### 8.3 主验收标准（按重要性排序）

1. **职责隔离可验证**：`PdfDocumentParser` 对 Cache / BatchPlanner / Dispatcher / Assembler 的调用应能用一句话描述，不存在"Dispatcher 回头调用 Parser 的私有方法"这类回环依赖。
2. **不变量全部保留**（一条都不能破）：
   - **缓存 key 的稳定性**：页签名由 `(内容 hash, DPI, 渲染参数)` 组成，拆分后必须字节级一致，否则缓存命中率崩塌。
   - **超时传递**：顶层 deadline 必须穿透到 Dispatcher，不能在拆分过程中丢失。
   - **失败回退顺序**：VDP 失败 → OCR → 纯文本，拆分后必须保留这个降级链。
3. **性能不退化**：见 8.4 的基准测试门槛。
4. **（次级目标）主类行数下降**：行数只是观察结果，不是验收条件。不要为了压行数而过度抽类。

#### 8.4 验证

```bash
mvn test -pl bootstrap -Dtest='*Pdf*'
# 回归样本：准备 3 类 PDF（纯文本/扫描件/混合）跑端到端解析
# 对比 Phase 8 前后 Segment 输出的 diff，必须为空
```

**必做基准测试**（Phase 8 是性能敏感区）：
- 3 类 PDF 样本（纯文本/扫描件/混合）解析耗时前后对比
- VDP 缓存查询不变量：`cacheMisses + cacheHits == visualTrackPageCount × 2`（每页每次 parse 恰好一次查询）

> **Phase 8 状态（2026-04-11）**：✅ 已按"行为保持型深度拆分 + 输出不变证据"口径结项。
> - 主类从 1640 → 1189 → 535 行，新增 7 个 VDP 协作者
> - 三条不变量测试钉住：`PdfVdpCacheTest`（digest 格式稳定）、`PdfVdpBatchResultNormalizerTest`（batch 缺页补齐 + timeout 指标按页计数）、`GoldenPdfPerformanceBaselineTest`（两次 parse 字节级一致 + 缓存查询不变量）
> - 基线报告：`bootstrap/target/phase8-baseline/golden-pdf-performance-baseline.json`，通过 `-Dgroups=golden -Dtest=GoldenPdfPerformanceBaselineTest` 可复跑
> - 原先"暂未处理"的两项（真正的 batch size planner / MinerU 整 PDF 提交策略）**已从 Phase 8 scope 移出**，挪到 §12 延后处理，理由是都取决于 MinerU 外部约束变化，不应阻塞 Phase 8 结项

---

### Phase 9：数据对象精简（DTO/VO/Response 三元组合并）

**工时**：3 天 | **风险**：中（触及 Facade ↔ Controller 边界，需前端联调）

#### 9.1 问题

当前存在三类数据对象，形成**三元组**：

```
DTO（持久化边界）→ VO（展示层）→ Response（HTTP 响应包装）
```

实际观察：
- **薄 VO**：大量 VO 只是 DTO 的字段子集 + 少量格式化（日期 → 字符串、枚举 → label）
- **薄 Response**：大量 Response 只是 `List<VO>` 或 `{ item: VO, meta: PageMeta }` 的包装，无独立逻辑
- **冗余**：Phase 6A 删除 15 个 Entity 后，DTO 已承担原 Entity 职责，VO/Response 价值进一步稀释

三元组示例：`McpServerDTO` → `McpServerVO` → `GetMcpServerResponse`，每层字段高度重合。

#### 9.2 拆分策略（分类处理，不一刀切）

**分类 A：仅删除“VO 与 DTO 字段完全一致”的 VO**

判定条件必须同时满足：

1. VO 与 DTO 字段名 100% 一致
2. 字段类型 100% 一致
3. 无额外计算 / 脱敏 / 聚合 / 裁剪逻辑
4. 替换后 HTTP 序列化输出字节级一致

扫描结果（结合 2026-04-12 inventory review）：

| VO | DTO | 现状 | 结论 |
|---|---|---|---|
| `McpToolReferenceVO` | `McpToolReferenceDTO` | 字段完全一致 | ✅ 已在 9-α 删除 |
| `IntentVersionVO` | 无 DTO | 仅 2 字段，独立值对象 | ✅ 已在 9-α 改为 record，但不属于“删 VO → 用 DTO” |
| `ChatMessageVO` | `ChatMessageDTO` | DTO 额外含 `createdAt / updatedAt` | ❌ 保留 VO |
| `ChatSessionVO` | `ChatSessionDTO` | DTO 额外含 `userId / metadata / createdAt / updatedAt` | ❌ 保留 VO |
| `AgentVO` | `AgentDTO` | DTO 额外含 `userId / activeIntentVersion / createdAt / updatedAt` | ❌ 保留 VO |
| `KnowledgeBaseVO` | `KnowledgeBaseDTO` | DTO 额外含 `createdBy / metadata` | ❌ 保留 VO |
| `KnowledgeDocumentVO` | `KnowledgeDocumentDTO` | DTO 额外含 `storagePath / contentHash / failedReason / indexedAt / retryCount / metadata` | ❌ 保留 VO |
| `ChatSessionFileVO` | `ChatSessionFileDTO` | DTO 额外含 `sessionId / storagePath / metadata` | ❌ 保留 VO |
| `McpServerVO` | `McpServerDTO` | DTO 含敏感字段；VO 额外含 `unresolvedReferenceCount` | ❌ 保留 VO |

> **结论**：VO 删除阈值应显著高于 Response 删除阈值。只要 VO 不是与 DTO **字段完全一致**，就默认保留，避免把“DTO 子集”误当成删除候选。

**分类 B：保留 VO，但删除薄 Response**
判定条件：Response 只有一个字段（`List<VO>` 或 `VO`），无 meta/分页。
策略：Controller 直接返回 `ApiResponse<List<VO>>` 或 `ApiResponse<VO>`。
目标：~15 个 Response（如 `GetAgentsResponse` 仅含 `List<AgentVO>`）
> 归入 9-β（契约变化批），需要前端联调。

**分类 C：保留 VO，但合并 Request → 使用公共参数对象**
判定条件：Create/Update Request 字段 90% 重合（仅 id 有无之差）。
策略：合并为单个 `UpsertXxxRequest`，用 `@Validated(OnCreate.class)` / `@Validated(OnUpdate.class)` 区分。
目标：Agent、AdminUser、AssistantTemplate、KnowledgeBase、IntentNode、McpServer 的 Create/Update 对 = **6 对 → 6 个**
> 归入 9-β（契约变化批），需要前端联调。

**分类 D：保留现状**（当前至少 21 个 VO 保留）
- Dashboard 专属 VO（6 个）：含嵌套聚合结构，无 DTO，纯视图模型
- 有转换逻辑/无 DTO 的 VO（8 个）：McpDiscoveredToolVO、ChatRoutingCandidateVO、IntentNodeVO、AssistantTemplateVO、AdminUserVO、LoginUserVO、UserProfileVO、DashboardMcpAlertVO
- DTO 更宽、VO 仍承担裁剪边界的展示类（7 个）：ChatMessageVO、ChatSessionVO、AgentVO、KnowledgeBaseVO、KnowledgeDocumentVO、ChatSessionFileVO、McpServerVO
- 含分页 meta 的 Response
- 前端强依赖字段名的 VO

#### 9.3 执行顺序：两阶段（契约不变 → 契约变化）

前端联调窗口是真正的推进瓶颈。为避免后端改动被前端档期卡死，先做**不改 HTTP 响应体结构**的那一半，彻底合并后再开第二批。

**阶段 9-α：契约不变批（可独立合入，无需前端联调）**

| 批次 | 范围 | 说明 |
|------|------|------|
| 9-α-1 | 分类 A 试点：`McpToolReferenceVO` 删除 + `IntentVersionVO` record 化 | 仅处理“字段完全一致”或“无 DTO 的极小值对象” |
| 9-α-2 | 复核其余 VO 候选是否真的满足“字段完全一致” | 复核后结论：当前无新增可删 VO |
| 9-α-3 | 分类 D 的"隐藏机会"：内部辅助类的 VO 删除（不经过 Controller 的） | 纯内部重构 |

**验收**：每个 commit 前后用 `curl` 抓取受影响端点的真实响应，`diff` 必须为空。

**阶段 9-β：契约变化批（需要前端联调窗口）**

| 批次 | 范围 | 需前端同步 |
|------|------|-----------|
| 9-β-1 | 分类 B：薄 Response 删除（Controller 改返回 `ApiResponse<List<VO>>`） | 是，改响应体结构 |
| 9-β-2 | 分类 C：Create/Update Request 合并为 `UpsertXxxRequest` | 是，改请求体结构 |
| 9-β-3 | 仅补充扫描是否还有“VO 与 DTO 字段完全一致”的漏网项 | 若存在才处理；当前不是主线 |

**阶段 9-β 前置**：
- 与前端约定联调窗口
- 生成 OpenAPI 快照，明确列出变更端点
- 前端在同一 sprint 内同步 TypeScript 类型

**总量变化**：观察指标，不作为 KPI。DTO + VO + Response + Request 预计会明显下降，但具体数字取决于 9-α 和 9-β 各自落地多少。重要的是**重复消除 + 契约清晰**，不是"删了几个文件"。

#### 9.4 约束

- **必须与前端同步**：删除 VO/Response 前确认前端不直接引用字段名（TypeScript 类型生成可自动适配）
- **每批单独 commit**：便于前端按批次联调
- **API 路径不变**：仅改变响应体结构时，用 OpenAPI 快照对比

#### 9.5 验证

```bash
mvn clean compile
mvn test -pl bootstrap
# OpenAPI 快照对比：每批次生成 openapi.json，diff 仅应显示预期的结构变化
```

---

### Phase 10：依赖三角解耦（intent → agent → admin）

**工时**：3 天 | **风险**：高（架构变更，需多轮评审）

#### 10.1 问题

当前依赖链（Phase 2 之后仍然存在）：

```
intent/application/IntentTreeFacadeServiceImpl
  → agent/port/AgentRepository（Phase 2 归位后）
  → agent/port/AgentKnowledgeBaseRepository
admin/application/AgentFacadeServiceImpl
  → agent/port/AgentRepository
  → intent/port/IntentNodeRepository（读 intent 做级联）
agent/application/InternalAssistantService
  → admin 的某些缓存 / 配置
```

三个领域相互读端口，形成闭环。根本原因：**Agent 既是 intent 的叶子节点归属，又是 admin 编排的实体，还是运行时的加载单元**，单一实体被三个领域共享。

#### 10.2 解耦策略

**方案 A（推荐）：引入领域事件**
- Agent 变更（创建/更新/删除/发布）发布 `AgentChangedEvent`
- Intent 订阅事件，维护自己的 `agentId → intentNode` 索引（读模型）
- admin 继续作为写入源，不再需要 intent 反向读
- 消除 `intent → agent` 的直接端口依赖

**方案 B（备选）：抽取共享读模型到 support/**
- 新建 `support/readmodel/AgentSnapshot`，作为跨领域只读快照
- intent 和 admin 都读 `AgentSnapshot`，不读 `AgentRepository`
- `AgentRepository` 仅被 agent 领域自身使用

#### 10.3 拆分步骤（方案 A）

| 步骤 | 动作 |
|------|------|
| 1 | 定义 `AgentChangedEvent`（created/updated/deleted/published 子类型）放在 `agent/event/` |
| 2 | `admin/application/AgentFacadeServiceImpl` 写入成功后发布事件（Spring `ApplicationEventPublisher`） |
| 3 | `intent/application/` 新增 `IntentAgentIndexUpdater` 监听事件，维护 `agentId → List<IntentNode>` 索引 |
| 4 | 重构 `IntentTreeFacadeServiceImpl` 读 index 而非 `AgentRepository` |
| 5 | 删除 `intent/` 中对 `agent/port/AgentRepository` 的 import |
| 6 | 运行依赖检查：`intent/**` 不应 import `agent/port/**` |

#### 10.4 注意事项

- **事件必须同步发布**（Spring 默认同步），否则读模型最终一致性会让调用方读到旧数据
- **启动时预热**：应用启动时全量扫描 agent 重建 index，避免冷启动时 intent 读空
- **事务边界**：事件发布必须在 DB 事务内，避免"写成功但事件丢失"

#### 10.5 验证

```bash
mvn test -pl bootstrap
# 依赖检查（可用 ArchUnit 或简单 grep）：
grep -r "import com.yulong.chatagent.agent.port" bootstrap/src/main/java/com/yulong/chatagent/intent/
# 预期：零结果
```

**建议引入 ArchUnit** 固化依赖规则，防止回退：

```java
@Test
void intent_should_not_depend_on_agent_port() {
    noClasses().that().resideInAPackage("..intent..")
        .should().dependOnClassesThat().resideInAPackage("..agent.port..")
        .check(classes);
}
```

---

### Phase 11：单实现接口逐案评估

**工时**：1.5 天 | **风险**：低（纯删除，IDE 可自动 inline）

#### 11.1 问题

当前约 36 个单实现接口。Phase 计划原本"延后"，但 Phase 6A 删除 15 个 Entity 后，部分 Converter/Helper 接口价值进一步下降，值得重新评估。

> **不设删除数量目标**。接口删减必须是评估结果，不是 KPI。把"删 X 个"设为指标会诱使把本该保留的边界（如六边形端口）也一起删掉。本 Phase 的验收条件是"每个接口都被评估过且有明确的保留/删除理由"，而不是"删到多少以下"。

#### 11.2 分类标准（决定保留 vs 删除）

| 保留的理由 | 删除的理由 |
|------------|------------|
| 六边形端口（Repository/Port/Gateway）— 即使当前单实现，也是架构边界 | Converter / Mapper — 纯函数，接口无抽象价值 |
| 被测试 Mock（实际使用 `@MockBean`） | 被测试用具体类 Mock（Mockito `mock(ImplClass.class)`） |
| 框架要求（Spring AOP / 事务代理需要接口） | 无 AOP / 无事务 |
| 领域对外契约（FacadeService，前端契约的一部分） | 内部 Helper（`XxxResolver`、`XxxAssembler`） |

#### 11.3 执行步骤

1. 脚本扫描：列出所有单实现接口（`find` + `grep "implements"` 计数）
2. 对每个接口填表（保留/删除 + 理由）
3. 删除的接口：
   - IDE refactor → Inline Interface → 所有引用改为具体类
   - 删除接口文件
   - 重命名 `XxxImpl` → `Xxx`（如果无冲突）
4. 每批 5–8 个接口单独 commit

#### 11.4 候选列表（需首轮扫描确认）

- 各领域 `XxxConverter`（Phase 6A 未删除的 Agent/ChatMessage/ChatSession 等）— 倾向保留，因为有复杂 JSON↔enum 转换
- 各领域 `XxxAssembler` / `XxxMapper`（应用层装配器）— 倾向删除
- `FacadeService` 接口 — 保留，架构边界

**预期结果**：每个接口都有评估记录（保留/删除 + 理由）。实际删除数量由评估决定，不预设。

#### 11.5 验证

```bash
mvn clean compile
mvn test -pl bootstrap
```

---

### 后续 Phase 依赖与排期建议

**单人推进顺序**：

```
Phase 11 (接口评估) ──▶ Phase 8 (Pdf 内部拆分) ──▶ Phase 9-α (契约不变)
                                                       │
                                                       ▼
                                                  Phase 9-β (契约变化，需前端联调)
                                                       │
                                                       ▼
                                                  Phase 10 (依赖三角解耦)
```

**理由**：
- Phase 11 最轻，热身同时梳理接口全景
- Phase 8 纯后端、无外部依赖，能在一个专注窗口完成
- Phase 9 按 α/β 分批，α 可随时合入，β 等前端窗口
- Phase 10 放最后：前面三个 Phase 会澄清"哪些是真正的领域边界"，降低事件建模的误判风险

**总工时**：约 9.5 个工作日（不含前端联调等待时间）

---

## 12. 明确不包含的内容（延后处理）

| 项目 | 原因 |
|------|------|
| 拆分 bootstrap 为多个 Maven 模块 | 当前团队规模下单模块可接受，风险收益比不高 |
| 解决 intent → agent → admin 依赖三角 | ~~需引入领域事件或中介者模式，属于更深的架构变更~~ → 已纳入 **Phase 10**（方案 A：领域事件 + 读模型索引） |
| 合并单实现接口（36 个） | ~~这些接口提供六边形边界价值，合并会消除 port/adapter 区分，可在结构移动完成后逐案评估~~ → 已纳入 **Phase 11**（按分类标准逐案处理，预期删除 10–11 个） |
| `user/infrastructure/cache/` 包迁移 | RedisRefreshTokenStore 是用户领域专属的 Redis 基础设施，保留在 `user/` 下合理 |
| 新增 Maven 模块 | 按照约束，不引入新模块 |
| `PdfVdpBatchPlanner` 演化为真正的 batch size planner | Phase 8 第一/二批已完成行为保持型拆分。"按 DPI 预算 + 页数拆多批提交"属于**外因驱动演化**：只有 `MinerU` 停止支持 "整份 PDF + pageIndices" 或其性能随页数线性劣化时才需要。当前 planner 只做"缓存感知筛页"，这是正确的最小形态，不应在 Phase 8 scope 内硬推 |
| 重新评估 `MinerU` 整 PDF 提交策略 | 同上，完全取决于 MinerU 外部约束变化。若未来需要按页数切片提交，应作为独立的性能 / 协议演进工作项，而不是重构 Phase |
| Phase 6B 合并薄 VO 到 Response 类 | 会破坏前端 API 契约，当前 Dashboard 4 个独立端点的结构已合理；Phase 9 将按 9-α / 9-β 两批在更广范围内处理数据对象精简 |

---

## 13. 执行总结

**分支**: `reconstruct/code-reconstruction`
**执行日期**: 2026-04-11
**最终测试**: 342 tests 通过，0 新增回归（2 个 pre-existing 失败与重构无关）

### 量化成果

| 指标 | 变化 |
|------|------|
| Entity 文件 | 21 → 6（删除 15 个，消除双重转换层） |
| PdfDocumentParser | 1640 → 1189 行（-28%） |
| McpServerAdminFacadeServiceImpl | 523 → ~390 行（-25%） |
| DashboardFacadeServiceImpl | 588 → 425 行（-28%） |
| Framework 测试 | 0 → 7 个测试类，41 个测试用例 |
| Git 追踪体积 | 解除 MCP/.venv/（96MB）+ output/ 追踪 |

### 与计划的偏差

| 偏差项 | 原计划 | 实际 | 原因 |
|--------|--------|------|------|
| PdfVisualDispatchEngine | 独立提取 | 保留在 PdfDocumentParser | 与缓存/批处理/超时紧密耦合 |
| Dashboard 4 个 Aggregator | 4 个独立类 | 2 个（OverviewAggregator + McpMetricsComposer） | Performance+Alert 合并为 Composer；Trends 因 private enum 跳过 |
| 6 个 Entity 合并 | 全部合并进 DTO | 保留 Entity | Agent/ChatMessage/ChatSession 有 JSON↔enum 转换；KDEnhancement 有类型差异；TurnMetric/MqOutbox 无 DTO |
| Phase 6B VO 合并 | 合并进 DashboardOverviewVO | 跳过 | 会破坏 API 契约，当前结构已合理 |
