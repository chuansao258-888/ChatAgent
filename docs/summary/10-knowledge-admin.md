# 知识库管理 & Admin 模块

## 模块概述

知识库管理模块负责知识库和文档的全生命周期管理（CRUD、上传、摄取调度），Admin 模块提供管理后台的所有功能（Agent 配置、模板管理、路由管理、Dashboard、MCP 管理、用户管理、MQ 管理）。

**核心代码路径：**
- 知识库：`chatagent/bootstrap/src/main/java/com/yulong/chatagent/knowledge/`
- 文件管理：`chatagent/bootstrap/src/main/java/com/yulong/chatagent/file/`
- RBAC：`chatagent/bootstrap/src/main/java/com/yulong/chatagent/access/`
- Admin：`chatagent/bootstrap/src/main/java/com/yulong/chatagent/admin/`

---

## 1. 知识库管理

### 1.1 KnowledgeBaseFacadeService

**文件：** `knowledge/application/KnowledgeBaseFacadeServiceImpl.java`

| 操作 | 说明 |
|------|------|
| getKnowledgeBases | 管理员权限检查 → 列出所有知识库 |
| getKnowledgeBase | Admin + ResourceAccessGuard 鉴权 |
| createKnowledgeBase | 生成 UUID，status=ACTIVE，visibility=SHARED |
| updateKnowledgeBase | ResourceAccessGuard 鉴权 → 补丁更新名称/描述 |
| deleteKnowledgeBase | **事务级联删除**：文档 → chunks → Milvus → Agent 绑定 → 意图绑定 → 知识库本身 |

**删除后清理 (post-commit)：**
- 删除存储的文件
- 触发信号缓存清除

### 1.2 KnowledgeDocumentFacadeService

**文件：** `knowledge/application/KnowledgeDocumentFacadeServiceImpl.java` (~420行)

#### uploadKnowledgeDocument (文档上传)

```
1. Admin + ResourceAccessGuard + KB 状态检查 (必须 ACTIVE)
2. createOrReplaceDocument():
   a. FileTypeDetector 验证文件类型
   b. SHA-256 内容哈希计算
   c. DocumentStorageService.save() → 存储文件
   d. 持久化文档元数据
3. 摄取调度 (post-commit):
   - MQ 启用 → OutboxEventPublisher (幂等键)
   - MQ 未启用 → 同步摄取
```

#### 内容去重

- 计算 SHA-256 哈希
- 如果哈希未变且状态为 COMPLETED → 跳过重新摄取（no-op 优化）

#### deleteKnowledgeDocument (文档删除)

```
级联：chunks → Milvus 向量 → 元数据记录
post-commit: 删除存储文件 + 清除信号缓存
```

### 1.3 AssistantKnowledgeBaseFacadeService

管理单个内置助手与知识库的绑定：

```
getAssistantKnowledgeBases():
    → 查找绑定的 KB ID → 过滤 ACTIVE → 返回 VO

setAssistantKnowledgeBase(kbIds):
    → 标准化 + 去重 ID
    → 断言每个 KB 都可管理且 ACTIVE
    → agentKnowledgeBaseRepository.replaceBindings() 原子替换
```

### 1.4 KnowledgeDocumentStatusSseService

文档状态实时推送：
- Stream key: `knowledge-base-documents:{kbId}`
- 事件类型: `DOCUMENT_STATUS_UPDATED`
- 通过 SSE 集群广播

### 1.5 Controller 路由

| Controller | 路径 | 权限 |
|-----------|------|------|
| KnowledgeBaseController | `/api/admin/knowledge-bases` | @RequireRole(ADMIN) |
| KnowledgeDocumentController | `/api/admin/knowledge-bases/{kbId}/documents` | @RequireRole(ADMIN) |
| KnowledgeDocumentStatusSseController | `/api/sse/admin/knowledge-bases/{kbId}/documents` | @RequireRole(ADMIN) |
| AssistantKnowledgeBaseController | `/api/admin/assistant/knowledge-bases` | @RequireRole(ADMIN) |

---

## 2. 会话文件管理

### 2.1 ChatSessionFileFacadeService

**文件：** `file/application/ChatSessionFileFacadeServiceImpl.java`

| 操作 | 说明 |
|------|------|
| getChatSessionFiles | 认证 + assertCanReadSession → 列出文件 |
| uploadFile | 事务级：验证所有权 → 存储 → 创建元数据 → post-commit 调度摄取 |
| detachFile | 事务级联：chunks → Milvus → 存储文件 → 元数据 |

**摄取失败处理：** 清理存储文件和数据库记录，防止残留。

### 2.2 Controller 路由

| 方法 | 路径 |
|------|------|
| GET | `/api/chat-sessions/{sessionId}/files` |
| POST | `/api/chat-sessions/{sessionId}/files/upload` |
| DELETE | `/api/chat-sessions/{sessionId}/files/{sessionFileId}` |

---

## 3. RBAC 访问控制

### 3.1 @RequireRole 注解

```java
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface RequireRole {
    UserRole[] value();
}
```

### 3.2 RequireRoleInterceptor

```
preHandle():
    1. 解析 @RequireRole (方法优先，回退到类)
    2. UserContext.requireUser() → LoginUser
    3. UserRole.matchesAny(user.role, requiredRoles)
    4. 不匹配 → ClientException(FORBIDDEN)
```

### 3.3 ResourceAccessGuard

| 方法 | 检查逻辑 |
|------|---------|
| `assertCanReadSession(user, sessionId)` | 查找会话 → userId == user.userId |
| `assertCanManageKnowledgeBase(user, kbId)` | role == ADMIN + KB 存在 |

---

## 4. Admin 管理后台

### 4.1 AdminAccessService

**文件：** `admin/application/AdminAccessService.java`

最基础的管理员权限门卫：
```java
requireAdmin():
    UserContext.requireUser()
    → role 必须为 "admin" (不区分大小写)
    → 否则 ClientException(FORBIDDEN)
```

### 4.2 DashboardFacadeService — 运营仪表盘

**文件：** `admin/application/DashboardFacadeServiceImpl.java` (~425行)

#### 三个正交 API

| 端点 | 返回数据 |
|------|---------|
| Overview (getOverview) | 用户/会话/消息数 + 24h 变化 + 环比百分比 |
| Performance (getPerformance) | 平均延迟、P95、成功率、错误率、无文档率、慢请求率 |
| Trends (getTrends) | 时间序列数据 (sessions/messages/activeUsers/latency/quality) |

#### 性能指标计算

```
avgLatencyMs → 平均响应延迟
P95 → Java 端排序取 95 百分位
successRate → 成功数 / 总数
errorRate → 错误数 / 总数
noDocRate → knowledgeHit=false 的比例
slowRate → durationMs > 20000 的比例
```

#### 时间趋势

- 支持窗口：24h / 7d / 30d
- 自动粒度：≤48h → 每小时，>48h → 每天
- 使用 PostgreSQL `date_trunc` 进行时间分桶

#### MCP 性能指标

`DashboardMcpMetricsComposer` 附加：
- 服务器熔断状态
- 平均延迟
- QPS
- 错误率
- 未解析引用计数

### 4.3 AgentFacadeService — Agent 配置管理

CRUD for Agent，`requireOwnedAgent()` 确保只能操作自己的 Agent。

### 4.4 AssistantTemplateFacadeService — 模板管理

模板 CRUD + `initializeAssistantFromTemplate()` 从模板初始化助手。

### 4.5 ChatRoutingAdminFacadeService — 模型路由管理

| 操作 | 说明 |
|------|------|
| getRoutingState | 返回每个候选模型的完整状态 (配置 vs 实际优先级/启用/思考策略/熔断状态) |
| updateCandidateOverride | 应用运行时覆盖 (验证 thinkingStrategy 合法性) |
| clearCandidateOverride | 清除覆盖 |

### 4.6 McpServerAdminFacadeService — MCP 服务器管理

详见 [06-mcp-integration.md](06-mcp-integration.md)。

### 4.7 UserAdminFacadeService — 用户管理

详见 [08-user-auth.md](08-user-auth.md)。

### 4.8 MqAdminFacadeService — MQ 管理

| 操作 | 说明 |
|------|------|
| outbox 状态 | 查看 outbox 各状态的计数 |
| DLQ 重放 | 重放死信消息，可重置重试次数 + 强制回滚 |

仅在 `chatagent.mq.enabled=true` 时激活。

---

## 5. 技术亮点总结

### 知识库管理
- **事务级联删除：** 文档 → chunks → Milvus → 绑定，确保数据一致性
- **内容去重：** SHA-256 哈希，未变化的文档跳过摄取
- **摄取调度双模式：** MQ (异步可靠) / 同步 (回退)
- **实时状态推送：** SSE 文档摄取进度

### Admin Dashboard
- **正交 API：** Overview / Performance / Trends 独立端点，避免慢查询
- **线程本地 knowledge_hit 传播：** CurrentTurnKnowledgeHitHolder 在工具执行时设置
- **MCP 指标集成：** 单一仪表盘覆盖全系统状态

### RBAC
- **注解式鉴权：** @RequireRole 声明式，零侵入
- **双层权限：** 角色级 (@RequireRole) + 资源级 (ResourceAccessGuard)
- **Admin 防护：** 防止自我操作、最后管理员保护
