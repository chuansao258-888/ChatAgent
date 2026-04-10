# Dashboard 运营看板 架构实现文档

> 对应计划：`docs/plans/DASHBOARD_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

为管理员提供运营 Dashboard，实时监控 ChatAgent 系统的运行状态、用户活跃度和 AI 回答质量。已落地的三块核心功能：

1. **性能数据采集基建**：`t_chat_turn_metric` 表 + `AgentRunResult` 结果对象
2. **后端 Dashboard API**：Overview / Performance / Trends 三组接口
3. **前端 Admin Dashboard 页面**：KPI 卡片 + 图表 + 洞察侧边栏

### 1.2 核心设计决策

1. **两层直通架构**：后端直接查业务表，不引入缓存层（Admin 后台低频访问，无需 Caffeine 等缓存）。
2. **正交 API 接口**：三个独立端点（overview / performance / trends），避免大接口慢查询。
3. **Thread-local 传递 knowledge_hit**：用 `CurrentTurnKnowledgeHitHolder` 在工具执行层设置标记，写入 metric 时直接取值，避免后查消息表做关键词匹配。
4. **AgentRunResult 结构化返回**：将 `ChatAgent.run()` 从日志-only 改为返回结构化结果，Dashboard 可消费。
5. **recharts 图表库**：前端使用 recharts 的 AreaChart / LineChart / ReferenceLine / ResponsiveContainer。

## 2. 整体架构

### 2.1 架构概览

```
┌─────────────────────────────────────────────────────┐
│                  前端表现层 (React)                    │
│  AdminOverviewPage.tsx                              │
│  ┌────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │ KPI卡片│ │趋势图表  │ │性能雷达  │ │运营洞察  │ │
│  └───┬────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ │
│      └───────────┴───────────┴───────────┘         │
│               admin.ts (API 调用)                    │
└───────────────────────┬─────────────────────────────┘
                        │ HTTP
┌───────────────────────┼─────────────────────────────┐
│              后端聚合层 (Spring Boot)                  │
│  DashboardController (@RequireRole ADMIN)           │
│  ├── GET /api/admin/dashboard/overview              │
│  ├── GET /api/admin/dashboard/performance           │
│  └── GET /api/admin/dashboard/trends                │
│  DashboardFacadeServiceImpl                         │
│  DashboardMapper (MyBatis XML)                      │
└───────────────────────┬─────────────────────────────┘
                        │ SQL
┌───────────────────────┼─────────────────────────────┐
│              数据层 (PostgreSQL)                       │
│  ┌─────────────────┐  ┌──────────────────────────┐  │
│  │ chat_session    │  │ t_chat_turn_metric       │  │
│  │ chat_message    │  │ (性能指标专用表)          │  │
│  └─────────────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### 2.2 核心数据流

```
用户发送消息
    │
    ▼
ChatEventProcessor.process()
    │
    ├── chatAgent.run() → AgentRunResult (durationMs, status, errorType, knowledgeHit)
    │       │
    │       └── 检索工具执行时 → CurrentTurnKnowledgeHitHolder.recordRetrievalResult()
    │
    ├── 成功路径: recordMetricQuietly(event, runResult) → INSERT t_chat_turn_metric
    │
    └── 失败路径: publishFailure() → recordMetricQuietly(event, failureResult) → INSERT t_chat_turn_metric
```

## 3. 文件清单

### 3.1 数据库迁移

| 文件路径 | 说明 |
|---|---|
| `chatagent/bootstrap/src/main/resources/db/migration/V12__dashboard_chat_turn_metrics.sql` | 创建 `t_chat_turn_metric` 表及索引 |

### 3.2 后端代码

| 文件路径 | 职责 |
|---|---|
| **采集层** | |
| `chatagent/.../agent/AgentRunResult.java` | Agent 运行结构化结果（durationMs, status, errorType, knowledgeHit） |
| `chatagent/.../agent/AgentRunException.java` | 携带 AgentRunResult 的异常类 |
| `chatagent/.../agent/runtime/CurrentTurnKnowledgeHitHolder.java` | Thread-local 知识命中标记 |
| `chatagent/.../conversation/metrics/ChatTurnMetricRecorder.java` | 聊天轮次指标持久化 |
| `chatagent/.../support/persistence/entity/ChatTurnMetric.java` | `t_chat_turn_metric` 实体 |
| `chatagent/.../support/persistence/mapper/ChatTurnMetricMapper.java` | MyBatis Mapper 接口 |
| `chatagent/.../resources/mapper/ChatTurnMetricMapper.xml` | MyBatis XML (INSERT) |
| **API 层** | |
| `chatagent/.../admin/controller/DashboardController.java` | Dashboard REST 控制器 (3 端点) |
| `chatagent/.../admin/application/DashboardFacadeService.java` | Facade 接口 |
| `chatagent/.../admin/application/DashboardFacadeServiceImpl.java` | Facade 实现 |
| `chatagent/.../admin/model/vo/DashboardOverviewVO.java` | 总览返回结构 |
| `chatagent/.../admin/model/vo/DashboardPerformanceVO.java` | 性能返回结构 |
| `chatagent/.../admin/model/vo/DashboardTrendsVO.java` | 趋势返回结构 |
| `chatagent/.../support/persistence/mapper/DashboardMapper.java` | Dashboard MyBatis Mapper |
| `chatagent/.../resources/mapper/DashboardMapper.xml` | Dashboard 聚合 SQL |

### 3.3 前端代码

| 文件路径 | 职责 |
|---|---|
| `ui/src/components/admin/pages/AdminOverviewPage.tsx` | Dashboard 主页面（KPI卡片 + 图表 + 洞察） |
| `ui/src/components/admin/AdminSideNav.tsx` | 侧边导航（Dashboard 入口） |
| `ui/src/api/admin.ts` | Dashboard API 调用函数 |
| `ui/src/types/admin.ts` | TypeScript 类型定义 |

### 3.4 配置文件

| 文件路径 | 说明 |
|---|---|
| `chatagent/bootstrap/src/main/resources/application.yaml` | 全局配置 |

## 4. 核心功能实现

### 4.1 性能数据采集（t_chat_turn_metric）

**表结构：**

```sql
CREATE TABLE t_chat_turn_metric (
    id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id    UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    turn_id       VARCHAR(64) NOT NULL,
    agent_id      UUID,
    status        VARCHAR(20) NOT NULL,   -- SUCCESS / ERROR
    error_type    VARCHAR(50),            -- LLM_TIMEOUT, RETRIEVAL_FAIL 等
    duration_ms   BIGINT      NOT NULL,
    knowledge_hit BOOLEAN     DEFAULT TRUE,
    created_at    TIMESTAMP   DEFAULT NOW()
);

CREATE INDEX idx_chat_turn_metric_created_at ON t_chat_turn_metric (created_at);
CREATE INDEX idx_chat_turn_metric_status     ON t_chat_turn_metric (status);
```

**采集入口：** `ChatEventProcessor.process()`

**实现逻辑：**

1. `ChatAgent.run()` 改为返回 `AgentRunResult`，包含 `durationMs`、`status`、`errorType`
2. 成功时：`AgentRunResult.success(durationMs, knowledgeHit)`
3. 失败时：抛出 `AgentRunException`，携带 `AgentRunResult.failure(...)`
4. `ChatEventProcessor` 在成功和失败两条路径都调用 `recordMetricQuietly()` 写入 metric

**示例代码：**

```java
// ChatAgent.run() 成功时返回结构化结果
return AgentRunResult.success(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit());

// 失败时把结构化结果包进异常
throw new AgentRunException(
    "Error running agent", e,
    AgentRunResult.failure(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
);

// ChatEventProcessor 成功路径记录 metric
AgentRunResult runResult = chatAgent.run();
recordMetricQuietly(event, runResult);

// ChatEventProcessor 失败兜底路径记录 metric
recordMetricQuietly(event, resolveFailureMetric(ex));
```

### 4.2 knowledge_hit 判定

**实现位置：** `CurrentTurnKnowledgeHitHolder.java`

**实现逻辑：** Thread-local 传递知识命中状态。

- 在检索工具执行时（如 `SessionFileTools.knowledgeQuery()`）调用 `recordRetrievalResult(!results.isEmpty())`
- `ChatAgent.run()` 结束时读取 thread-local 状态写入 `knowledgeHit`
- **语义**：未发生检索默认 `true`；检索过至少一次命中记 `true`；检索过但全空记 `false`

### 4.3 Overview API (GET /overview)

**实现位置：** `DashboardFacadeServiceImpl.java`

**实现逻辑：**

1. 计算当前窗口 `[now - duration, now)` 和上一个窗口 `[now - 2*duration, now - duration)`
2. 累积指标通过 `countXXXBefore(end)` 统计
3. 窗口指标通过 `countXXXBetween(start, end)` 统计
4. `deltaPct = (current - prev) * 100.0 / prev`，prev 为 0 时返回 null

**返回结构：**

```json
{
  "window": "24h",
  "compareWindow": "prev_24h",
  "updatedAt": 1712640000000,
  "kpis": {
    "totalUsers":    { "value": 1250, "delta": 12, "deltaPct": 5.2 },
    "activeUsers":   { "value": 85,   "delta": -2, "deltaPct": -2.1 },
    "totalSessions": { "value": 5400, "delta": 320, "deltaPct": null },
    "sessions24h":   { "value": 320,  "delta": 25,  "deltaPct": 8.4 },
    "totalMessages": { "value": 15000,"delta": 1500,"deltaPct": null },
    "messages24h":   { "value": 1500, "delta": 140, "deltaPct": 10.2 }
  }
}
```

### 4.4 Performance API (GET /performance)

**实现位置：** `DashboardFacadeServiceImpl.java`

**实现逻辑：** 查询 `t_chat_turn_metric` 表计算指标。

**指标计算公式：**

| 指标 | 公式 |
|---|---|
| `avgLatencyMs` | AVG(duration_ms) WHERE status='SUCCESS' |
| `p95LatencyMs` | Java 层排序取 95 百分位（从 SUCCESS 记录的 duration_ms 列表） |
| `successRate` | `success / (success + error) * 100` |
| `errorRate` | `error / (success + error) * 100` |
| `noDocRate` | `knowledge_hit=false 记录数 / 总 SUCCESS 记录数 * 100` |
| `slowRate` | `duration_ms > 20000 记录数 / 总记录数 * 100` |

**关键 SQL：**

```sql
SELECT COUNT(1) FILTER (WHERE status = 'SUCCESS') AS "successCount",
       COUNT(1) FILTER (WHERE status = 'ERROR')   AS "errorCount",
       AVG(duration_ms) FILTER (WHERE status = 'SUCCESS') AS "avgLatencyMs"
FROM t_chat_turn_metric
WHERE created_at >= #{start} AND created_at < #{end}
```

### 4.5 Trends API (GET /trends)

**实现位置：** `DashboardFacadeServiceImpl.java`

**实现逻辑：** 按 `metric` 参数分流查询，使用 `date_trunc` 做时间分桶。

| metric | series 输出 | 数据来源 |
|---|---|---|
| `sessions` | `["会话数"]` | `chat_session.created_at` 分桶 COUNT |
| `messages` | `["消息数"]` | `chat_message.created_at` 分桶 COUNT |
| `activeUsers` | `["活跃用户"]` | `chat_message` JOIN `chat_session` 分桶 COUNT DISTINCT user_id |
| `avgLatency` | `["平均响应时间"]` | `t_chat_turn_metric` 分桶 AVG WHERE SUCCESS |
| `quality` | `["错误率", "无知识率"]` | `t_chat_turn_metric` 分桶计算比例 |

**关键 SQL（趋势分桶）：**

```sql
-- Latency 趋势
SELECT date_trunc(#{bucketUnit}, created_at) AS bucket,
       AVG(duration_ms) AS "avgLatencyMs",
       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS "p95LatencyMs"
FROM t_chat_turn_metric
WHERE created_at >= #{start} AND created_at < #{end} AND status = 'SUCCESS'
GROUP BY bucket ORDER BY bucket

-- Active Users 趋势（需要 JOIN）
SELECT date_trunc(#{bucketUnit}, cm.created_at) AS bucket,
       COUNT(DISTINCT cs.user_id) AS value
FROM chat_message cm
JOIN chat_session cs ON cm.session_id = cs.id
JOIN t_user tu ON cs.user_id = tu.id
WHERE cm.created_at >= #{start} AND cm.created_at < #{end} AND tu.deleted = FALSE
GROUP BY bucket ORDER BY bucket
```

**注意：** activeUsers 查询需要 JOIN `chat_session`（user_id 在 session 表上而非 message 表上）。

### 4.6 前端 Dashboard 页面

**实现位置：** `AdminOverviewPage.tsx`

**页面结构：**

1. 顶部健康条（HealthAlertBar）：根据 `errorRate` 判定绿/黄/红
2. 6 个 KPI 卡片（带环比变化标签）
3. 图表矩阵（会话趋势、活跃用户、性能趋势、质量趋势）
4. 右侧洞察侧边栏（AI 性能雷达 + 运营洞察 + 推荐检查）

**数据加载（并行请求）：**

```typescript
const [
  overviewResponse,
  performanceResponse,
  sessionsTrend,
  messagesTrend,
  activeUsersTrend,
  latencyTrend,
  qualityTrend,
] = await Promise.all([
  getDashboardOverview(selectedWindow),
  getDashboardPerformance(selectedWindow),
  getDashboardTrends({ metric: "sessions", window: selectedWindow }),
  getDashboardTrends({ metric: "messages", window: selectedWindow }),
  getDashboardTrends({ metric: "activeUsers", window: selectedWindow }),
  getDashboardTrends({ metric: "avgLatency", window: selectedWindow }),
  getDashboardTrends({ metric: "quality", window: selectedWindow }),
]);
```

**性能警戒线示例：**

```tsx
<ReferenceLine
  y={5000}
  stroke="rgba(255,122,69,0.55)"
  strokeDasharray="4 4"
  label={{ value: "5s", fill: "rgba(255,122,69,0.75)" }}
/>
```

## 5. 配置说明

| 配置项 | 默认值 | 说明 |
|---|---|---|
| 时间窗口 | `24h` | Overview/Performance 默认查询窗口 |
| slowRate 阈值 | `20000ms` | 超过 20s 的请求视为慢请求 |
| 分桶粒度 | 自动推断 | window ≤ 48h 用 hour，否则用 day |

## 6. 验证结果

| 验证项 | 状态 | 说明 |
|---|---|---|
| 前端生产构建 | 通过 | `npm run build` |
| 采集链路单测 | 通过 | `ChatEventProcessorTest` |
| Dashboard 服务单测 | 通过 | `DashboardFacadeServiceImplTest` |
| Dashboard 控制器单测 | 通过 | `DashboardControllerTest` |
| 数据库 Migration | 通过 | Flyway v11 → v12 |
| 接口联调 | 通过 | Overview/Performance/Trends 均返回正确数据 |

## 7. 已知限制与后续规划

- **无缓存层**：当前直接查业务表，Admin 后台低频访问可接受；后续视数据量决定是否加缓存或物化视图。
- **待积累真实数据**：需让真实聊天流量持续写入 `t_chat_turn_metric`，观察 24h/7d 窗口下的真实表现。
- **洞察文案待校准**：前端洞察文案、阈值和告警颜色需用生产数据校正。
- **Dashboard 二期**：可补更细粒度的 model-level / agent-level 指标拆分。
