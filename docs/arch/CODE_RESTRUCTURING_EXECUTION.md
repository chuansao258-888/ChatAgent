# ChatAgent 代码结构重构执行记录

> 分支：`reconstruct/code-reconstruction`
> 基于提交：`9613e77` (main)
> 执行日期：2026-04-11
> 参考方案：[CODE_RESTRUCTURING_PLAN.md](../plans/CODE_RESTRUCTURING_PLAN.md)

---

## 状态总览

| Phase | 内容 | 状态 | Commit | 文件变更 | 备注 |
|-------|------|------|--------|----------|------|
| 0 | 根目录清理 & Git 卫生 | ⏳ 待执行 | - | - | - |
| 1 | MCP 工具类归位 | ⏳ 待执行 | - | - | - |
| 3 | RAG 命名统一 | ⏳ 待执行 | - | - | - |
| 2 | Intent 控制器 + Agent 端口归位 | ⏳ 待执行 | - | - | - |
| 4 | 双重持久化统一 | ⏳ 待执行 | - | - | - |
| 7A | chat 包分裂修复 | ⏳ 待执行 | - | - | - |
| 5 | 上帝类拆分 | ⏳ 待执行 | - | - | - |
| 6 | 数据对象精简 | ⏳ 待执行 | - | - | - |
| 7B | Framework 模块测试 | ⏳ 待执行 | - | - | - |

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
| `git ls-files output/` 返回空 | ⏳ |
| `mvn compile` 通过 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 1：MCP 工具类归位

**执行时间**: 待执行
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
| `mvn compile` 通过 | ⏳ |
| `grep "admin\.application\.Mcp"` 零结果 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 3：RAG 命名统一

**执行时间**: 待执行
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
| `mvn compile` 通过 | ⏳ |
| 空目录 rag/service 已删除 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 2：Intent 控制器 + Agent 端口归位

**执行时间**: 待执行
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
| `mvn compile` 通过 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 4：双重持久化统一

**执行时间**: 待执行
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
| `mvn compile` 通过 | ⏳ |
| 空目录已清理 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 7A：chat 包分裂修复

**执行时间**: 待执行
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
| `mvn compile` 通过 | ⏳ |
| 空 chat/ 目录已删除 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 5：上帝类拆分

**执行时间**: 待执行
**风险**: 中高

### 执行动作

#### 5A. 拆分 PdfDocumentParser（1640 行）
| 新类 | 职责 | 预估行数 |
|------|------|----------|
| PdfDocumentParser（瘦身） | 管线编排 | ~200 |
| PdfPageRenderer | PDF 转图片渲染 | ~80 |
| PdfQualityRouter | 路由决策、字符密度 | ~200 |
| PdfPageTextExtractor | 逐页文本提取 | ~300 |
| PdfVisualDispatchEngine | VDP 调度、缓存 | ~400 |

#### 5B. 拆分 McpServerAdminFacadeServiceImpl（523 行）
| 新类 | 职责 |
|------|------|
| McpServerAdminFacadeServiceImpl（瘦身） | 核心 CRUD |
| McpServerCrudHelper | DTO 组装、字段映射 |
| McpServerDeleteHandler | 删除编排 |

#### 5C. 拆分 DashboardFacadeServiceImpl（588 行）
| 新类 | 职责 |
|------|------|
| DashboardFacadeServiceImpl（瘦身） | 顶层编排 |
| DashboardOverviewAggregator | 会话/用户/消息聚合 |
| DashboardMcpPerformanceAggregator | MCP 性能指标 |
| DashboardMcpAlertAggregator | 告警查询 |
| DashboardTrendsAggregator | 时序数据 |

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ⏳ |
| `mvn test` 通过 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 6：数据对象精简（保守策略）

**执行时间**: 待执行
**风险**: 低

### 执行动作

#### 1. Lombok 化 Entity
- `Agent.java`：删除手写 equals/hashCode/toString
- `ChatMessage.java`：删除手写 equals/hashCode/toString
- `ChatSession.java`：删除手写 equals/hashCode/toString

### 验证结果

| 检查项 | 结果 |
|--------|------|
| `mvn compile` 通过 | ⏳ |
| `mvn test` 通过 | ⏳ |

### 问题与备注

（执行后填写）

---

## Phase 7B：Framework 模块测试

**执行时间**: 待执行
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
| `mvn test` 全部通过 | ⏳ |

### 问题与备注

（执行后填写）
