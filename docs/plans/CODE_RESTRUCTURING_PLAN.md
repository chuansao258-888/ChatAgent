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

**总工时**：约 9 个工作日。推荐执行顺序：Phase 0 → 1 → 3 → 2 → 4 → 7 → 5 → 6。

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

### 5C：拆分 DashboardFacadeServiceImpl（588 行 → ~350 行）

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

所有 Phase 均不引入数据库 schema 变更、API 契约变更或新依赖。变更均为纯结构性的（包移动、类重命名、import 更新）。

---

## 12. 明确不包含的内容（延后处理）

| 项目 | 原因 |
|------|------|
| 拆分 bootstrap 为多个 Maven 模块 | 当前团队规模下单模块可接受，风险收益比不高 |
| 解决 intent → agent → admin 依赖三角 | 需引入领域事件或中介者模式，属于更深的架构变更 |
| 合并单实现接口（36 个） | 这些接口提供六边形边界价值，合并会消除 port/adapter 区分，可在结构移动完成后逐案评估 |
| `user/infrastructure/cache/` 包迁移 | RedisRefreshTokenStore 是用户领域专属的 Redis 基础设施，保留在 `user/` 下合理 |
| 新增 Maven 模块 | 按照约束，不引入新模块 |

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
| DashboardFacadeServiceImpl | 588 → ~350 行（-40%） |
| Framework 测试 | 0 → 7 个测试类，41 个测试用例 |
| Git 追踪体积 | 解除 MCP/.venv/（96MB）+ output/ 追踪 |

### 与计划的偏差

| 偏差项 | 原计划 | 实际 | 原因 |
|--------|--------|------|------|
| PdfVisualDispatchEngine | 独立提取 | 保留在 PdfDocumentParser | 与缓存/批处理/超时紧密耦合 |
| Dashboard 4 个 Aggregator | 4 个独立类 | 2 个（OverviewAggregator + McpMetricsComposer） | Performance+Alert 合并为 Composer；Trends 因 private enum 跳过 |
| 6 个 Entity 合并 | 全部合并进 DTO | 保留 Entity | Agent/ChatMessage/ChatSession 有 JSON↔enum 转换；KDEnhancement 有类型差异；TurnMetric/MqOutbox 无 DTO |
| Phase 6B VO 合并 | 合并进 DashboardOverviewVO | 跳过 | 会破坏 API 契约，当前结构已合理 |
