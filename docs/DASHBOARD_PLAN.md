# ChatAgent 运营 Dashboard 落地方案 (V1.0)

## 1. 背景与目标

为了让管理员能够直观地监控 ChatAgent 系统的运行状态、用户活跃度以及 AI 回答的质量，我们需要建立一套类似于 `ragent` 的运营 Dashboard。

**核心目标：**
- **数据可视化**：展示核心 KPI（用户、会话、消息）的实时数据与环比变化。
- **性能监控**：追踪 AI 响应延迟（P95/平均）与成功率，及时发现系统瓶颈。
- **质量洞察**：分析无知识库命中率与系统错误率，指导 RAG 效果优化。
- **即时响应**：支持不同时间窗口（24h, 7d, 30d）的数据切换。

---

## 2. 架构设计

参考 `ragent` 的成熟实践，Dashboard 采用**三段式分层解耦架构**：

1.  **数据采集层**：利用现有的数据库（PostgreSQL）进行增量统计。对于高性能要求的指标（如实时 QPS），未来可扩展至 Redis 计数器。
2.  **数据聚合层 (Backend API)**：
    - 设计正交的 API 接口，避免大接口导致的慢查询。
    - 引入短期缓存（如 Caffeine），防止管理员频繁刷新导致数据库压力过大。
3.  **表现层 (Frontend)**：
    - 基于 React + TailwindCSS。
    - 采用“总览-趋势-洞察”的响应式布局。

---

## 3. 后端 API 详细设计

所有接口位于 `/api/admin/dashboard/**` 路径下，受 `@RequireRole(UserRole.ADMIN)` 保护。

### 3.1 核心 KPI 总览 (`/overview`)
- **功能**: 返回关键业务指标及其环比变化。
- **返回结构**:
  ```json
  {
    "updatedAt": 1712640000000,
    "kpis": {
      "totalUsers": { "value": 1250, "deltaPct": 5.2 },
      "activeUsers": { "value": 85, "deltaPct": -2.1 },
      "totalSessions": { "value": 5400, "deltaPct": 12.5 },
      "sessionsWindow": { "value": 320, "deltaPct": 8.4 },
      "messagesWindow": { "value": 1500, "deltaPct": 10.2 }
    }
  }
  ```

### 3.2 AI 性能指标 (`/performance`)
- **功能**: 返回 RAG 与 LLM 链路的质量与速度指标。
- **指标定义**:
  - `avgLatencyMs`: 平均响应耗时。
  - `p95LatencyMs`: P95 响应耗时（更能反映用户体感的长尾延迟）。
  - `successRate`: 成功会话比例（排除系统错误）。
  - `errorRate`: 系统或大模型接口报错比例。
  - `noDocRate`: 知识库检索未命中（导致 AI 无法回答）的比例。

### 3.3 变化趋势数据 (`/trends`)
- **功能**: 为折线图提供时间序列数据。
- **参数**:
  - `metric`: `SESSIONS` | `ACTIVE_USERS` | `LATENCY` | `QUALITY`
  - `window`: `24h` | `7d` | `30d`
  - `granularity`: `hour` | `day`
- **返回结构**:
  ```json
  {
    "metric": "SESSIONS",
    "series": [
      { "name": "当前周期", "data": [{ "ts": 1712640000, "value": 10 }, ...] },
      { "name": "上个周期", "data": [{ "ts": 1712640000, "value": 8 }, ...] }
    ]
  }
  ```

---

## 4. 数据统计逻辑 (SQL 策略)

为了在 V1 实现最轻量落地，直接查询业务表：

- **用户指标**: 查询 `t_user` 表的 `created_at`。
- **会话/消息指标**: 查询会话记录表（如 `t_chat_session` / `t_chat_message`）。
- **性能/延迟**: 需要在 `t_chat_message` 中新增 `latency_ms` (耗时) 和 `error_type` (错误类型) 字段，或建立专门的 `t_metric_log` 审计表。

**优化建议**：
对于趋势图，使用 PostgreSQL 的 `date_trunc` 函数进行聚合。为防止慢查询，建议对 `created_at` 字段建立索引。

---

## 5. 前端 UI 组件规划

模仿 `ragent` 的高质感 UI 风格：

1.  **HealthAlertBar (顶部)**：
    - 根据 `errorRate` 自动判定健康状态。
    - 绿色: 运行正常；黄色: 需关注；红色: 风险偏高。
2.  **KPI 网格 (Top Rows)**：
    - 四个带图标的卡片，展示绝对值和带颜色的百分比变化标签。
3.  **折线图矩阵 (Middle Rows)**：
    - **会话趋势图**：展现调用量。
    - **活跃用户图**：展现粘性。
    - **性能趋势图**：同时画出平均和 P95 延迟，并在 2s 和 5s 处标出警戒虚线。
4.  **智能洞察侧边栏 (Right Sidebar)**：
    - **AI 性能雷达**：环形进度条展示成功率。
    - **运营洞察卡片**：根据数据自动生成文本，如“今日错误率上升 3%，请检查 OpenAI 额度”。

---

## 6. 实施步骤 (Phases)

**Phase 1: 后端基建与 KPI 接口**
- 在数据库中补充性能统计相关字段。
- 实现 `DashboardController` 与 `DashboardService`。
- 引入 Caffeine 缓存对统计结果进行 5 分钟的短效缓存。

**Phase 2: 趋势聚合与图表 API**
- 编写聚合 SQL（处理时间窗口与环比逻辑）。
- 提供 `/trends` 接口支持。

**Phase 3: 前端看板落地**
- 引入 `recharts` 或 `echarts` 库。
- 绘制响应式 Dashboard 页面，对接后端 API。
- 实现智能洞察逻辑。
