# ChatAgent 运营 Dashboard 落地方案 (V1.1)

## 1. 背景与目标

为了让管理员能够直观地监控 ChatAgent 系统的运行状态、用户活跃度以及 AI 回答的质量，我们需要建立一套运营 Dashboard。

**核心目标：**
- **数据可视化**：展示核心 KPI（用户、会话、消息）的实时数据与环比变化。
- **性能监控**：追踪 AI 响应延迟（P95/平均）与成功率，及时发现系统瓶颈。
- **质量洞察**：分析无知识库命中率与系统错误率，指导 RAG 效果优化。
- **即时响应**：支持不同时间窗口（24h, 7d, 30d）的数据切换。

---

## 2. 架构设计

参照 ragent 的实现，Dashboard 采用**两层直通架构**：

1.  **数据聚合层 (Backend API)**：
    - 设计正交的 API 接口，避免大接口导致的慢查询。
    - 直接查询业务表，不引入额外缓存层（Admin 后台低频访问，无需 Caffeine 等缓存）。
2.  **表现层 (Frontend)**：
    - 基于 React + TailwindCSS。
    - 采用"总览-趋势-洞察"的响应式布局。

---

## 3. 性能数据采集：`t_chat_turn_metric` 表

ragent 的 `/performance` 指标全部依赖独立的 `t_rag_trace_run` 表（记录每次 RAG 调用的耗时、状态）。ChatAgent 目前没有等价的持久化数据源。

**解决方案**：新建轻量级的 `t_chat_turn_metric` 表，在 `ChatEventProcessor.process()` 中于 `chatAgent.run()` 完成后写入一条记录。

### 3.1 表结构

```sql
CREATE TABLE t_chat_turn_metric (
    id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id    UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    turn_id       VARCHAR(64) NOT NULL,
    agent_id      UUID,
    status        VARCHAR(20) NOT NULL,   -- SUCCESS / ERROR
    error_type    VARCHAR(50),            -- LLM_TIMEOUT, RETRIEVAL_FAIL 等，成功时为 NULL
    duration_ms   BIGINT      NOT NULL,
    knowledge_hit BOOLEAN     DEFAULT TRUE,
    created_at    TIMESTAMP   DEFAULT NOW()
);

CREATE INDEX idx_chat_turn_metric_created_at ON t_chat_turn_metric (created_at);
CREATE INDEX idx_chat_turn_metric_status     ON t_chat_turn_metric (status);
```

### 3.2 采集点

`ChatEventProcessor.process()` 是所有聊天轮次的统一入口（line 36-72）。`ChatAgent.run()` 已经在内部跟踪了 `durationMs` 和 `agentState`（FINISHED / ERROR）。改造思路：

1. 让 `ChatAgent.run()` 返回一个轻量结果对象（`AgentRunResult`），包含 `durationMs`、`status`、`errorType`。
2. `ChatEventProcessor.process()` 拿到结果后，写入 `t_chat_turn_metric`。
3. 失败路径 `publishFailure()` 同样写入一条 status=ERROR 的记录。

### 3.3 `knowledge_hit` 判定

参照 ragent 通过精确匹配 assistant 消息内容 `"未检索到与问题相关的文档内容。"` 来统计 noDocRate 的做法。ChatAgent 可选择：
- **方案 A（推荐）**：在 `AgentRunResult` 中携带一个 `knowledgeHit` 布尔值，由检索工具在执行时设置。
- **方案 B**：后查 `chat_message` 表中该 turn 的 assistant 消息内容，做关键词匹配。

V1 先用方案 A，在工具执行层设置标记，写入 metric 时直接取值。

---

## 4. 后端 API 详细设计

所有接口位于 `/api/admin/dashboard/**` 路径下，受 `@RequireRole(UserRole.ADMIN)` 保护。

### 4.1 核心 KPI 总览 (`GET /overview`)

**参数**：`window`（可选，默认 `24h`）

**返回结构**（对齐 ragent 的 `DashboardOverviewVO`）：
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

**VO 结构**（与 ragent 完全对齐）：
- `DashboardOverviewVO`：`window`, `compareWindow`, `updatedAt`, `kpis`
- `DashboardOverviewGroupVO`：6 个 KPI 字段（`totalUsers`, `activeUsers`, `totalSessions`, `sessions24h`, `totalMessages`, `messages24h`）
- `DashboardOverviewKpiVO`：`value(Long)`, `delta(Long)`, `deltaPct(Double)`

**环比计算逻辑**（参照 ragent 的 `WindowRange`）：
- 当前窗口：`[now - duration, now)`
- 上一个窗口：`[now - 2*duration, now - duration)`
- `deltaPct = (current - prev) * 100.0 / prev`，prev 为 0 时返回 null

### 4.2 AI 性能指标 (`GET /performance`)

**参数**：`window`（可选，默认 `24h`）

**数据来源**：`t_chat_turn_metric` 表。

**返回结构**（对齐 ragent 的 `DashboardPerformanceVO`）：
```json
{
  "window": "24h",
  "avgLatencyMs": 3200,
  "p95LatencyMs": 8500,
  "successRate": 95.2,
  "errorRate": 4.8,
  "noDocRate": 12.3,
  "slowRate": 8.1
}
```

**指标计算**（与 ragent 完全一致）：
- `avgLatencyMs`：窗口内 status=SUCCESS 的 `duration_ms` 平均值。
- `p95LatencyMs`：窗口内 status=SUCCESS 的 `duration_ms` 第 95 百分位（内存排序取下标）。
- `successRate`：`success / (success + error) * 100`。
- `errorRate`：`error / (success + error) * 100`。
- `noDocRate`：`knowledge_hit=false 的记录数 / 总 success 记录数 * 100`。
- `slowRate`：`duration_ms > 20000 的记录数 / 总记录数 * 100`（ragent 阈值为 20s）。

### 4.3 变化趋势数据 (`GET /trends`)

**参数**：
- `metric`：`sessions` | `messages` | `activeUsers` | `avgLatency` | `quality`
- `window`：`24h` | `7d` | `30d`（默认 `7d`）
- `granularity`：`hour` | `day`（可选，自动推断：window ≤ 48h 用 hour，否则用 day）

**返回结构**（对齐 ragent 的 `DashboardTrendsVO`）：
```json
{
  "metric": "sessions",
  "window": "7d",
  "granularity": "day",
  "series": [
    {
      "name": "会话数",
      "data": [{ "ts": 1712640000000, "value": 10.0 }, ...]
    }
  ]
}
```

**各 metric 的 series 输出**：
| metric | series | 数据来源 |
|--------|--------|---------|
| `sessions` | `["会话数"]` | `chat_session.created_at` 按时间分桶 COUNT |
| `messages` | `["消息数"]` | `chat_message.created_at` 按时间分桶 COUNT |
| `activeUsers` | `["活跃用户"]` | `chat_message` JOIN `chat_session` 后按时间分桶 COUNT DISTINCT user_id |
| `avgLatency` | `["平均响应时间"]` | `t_chat_turn_metric` 按时间分桶 AVG(duration_ms) WHERE status='SUCCESS' |
| `quality` | `["错误率", "无知识率"]` | `t_chat_turn_metric` 按时间分桶计算比例 |

---

## 5. 数据统计 SQL 策略

ChatAgent 使用原生 MyBatis XML（非 MyBatis-Plus），聚合查询需要在 `DashboardMapper.xml` 中编写。

### 5.1 核心差异：active_users 需要 JOIN

ragent 的 `conversation_message` 表直接带有 `user_id`，可以一步 `COUNT(DISTINCT user_id)`。ChatAgent 的 `chat_message` 只有 `session_id`，`user_id` 在 `chat_session` 表上，需要 JOIN：

```sql
-- 窗口内活跃用户数
SELECT COUNT(DISTINCT cs.user_id) AS cnt
FROM chat_message cm
JOIN chat_session cs ON cm.session_id = cs.id
WHERE cm.created_at >= #{start} AND cm.created_at < #{end}

-- 按天分桶的活跃用户趋势
SELECT to_char(cm.created_at, 'YYYY-MM-DD') AS d,
       COUNT(DISTINCT cs.user_id)            AS cnt
FROM chat_message cm
JOIN chat_session cs ON cm.session_id = cs.id
WHERE cm.created_at >= #{start} AND cm.created_at < #{end}
GROUP BY d
```

### 5.2 时间分桶聚合

使用 PostgreSQL 的 `to_char` 做时间分桶（与 ragent 完全一致）：
- 按天：`to_char(created_at, 'YYYY-MM-DD')`
- 按小时：`to_char(created_at, 'YYYY-MM-DD HH24:00:00')`

返回结果为 `List<Map<String, Object>>`，在 Java 层转换为 `Map<LocalDate, Long>` 或 `Map<LocalDateTime, Long>`，然后用循环填充零值点（保证折线图连续）。

### 5.3 性能指标查询（基于 t_chat_turn_metric）

```sql
-- P95 延迟：拉取窗口内所有 SUCCESS 记录的 duration_ms，在 Java 层排序取百分位
SELECT duration_ms
FROM t_chat_turn_metric
WHERE created_at >= #{start} AND created_at < #{end}
  AND status = 'SUCCESS'

-- noDocRate 按天趋势
SELECT to_char(created_at, 'YYYY-MM-DD') AS d, COUNT(*) AS cnt
FROM t_chat_turn_metric
WHERE created_at >= #{start} AND created_at < #{end}
  AND knowledge_hit = FALSE
GROUP BY d
```

### 5.4 索引建议

确保以下列有索引（大部分已存在）：
- `chat_session.created_at`
- `chat_message.created_at`
- `t_chat_turn_metric.created_at`
- `t_chat_turn_metric.status`

---

## 6. 前端 UI 组件规划

1.  **HealthAlertBar (顶部)**：
    - 根据 `errorRate` 自动判定健康状态。
    - 绿色: 运行正常；黄色: 需关注；红色: 风险偏高。
2.  **KPI 网格 (Top Rows)**：
    - 六个带图标的卡片，展示绝对值和带颜色的百分比变化标签。
3.  **折线图矩阵 (Middle Rows)**：
    - **会话趋势图**：展现调用量。
    - **活跃用户图**：展现粘性。
    - **性能趋势图**：同时画出平均和 P95 延迟，并在 2s 和 5s 处标出警戒虚线。
4.  **智能洞察侧边栏 (Right Sidebar)**：
    - **AI 性能雷达**：环形进度条展示成功率。
    - **运营洞察卡片**：根据数据自动生成文本，如"今日错误率上升 3%，请检查 LLM 额度"。

---

## 7. 实施步骤 (Phases)

**Phase 1: 性能数据采集基建**
- 新建 `t_chat_turn_metric` 表及索引。
- 新建 `AgentRunResult` 结果对象。
- 改造 `ChatAgent.run()` 返回 `AgentRunResult`。
- 在 `ChatEventProcessor.process()` 和 `publishFailure()` 中写入 metric 记录。

**Phase 2: 后端 Dashboard API**
- 新建 `DashboardController`（3 个端点）+ `DashboardService` + `DashboardMapper`。
- 实现 `WindowRange` 时间窗口工具类（参照 ragent 的内部类）。
- 在 `DashboardMapper.xml` 中编写全部聚合 SQL。
- 注意 `activeUsers` 查询需要 JOIN `chat_session`。

**Phase 3: 前端看板落地**
- 引入 `recharts` 库。
- 绘制响应式 Dashboard 页面，对接后端 API。
- 实现智能洞察逻辑。
