# ChatAgent Dashboard 落地说明

## 1. 本次实现范围

本轮实现已经把 `docs/plans/DASHBOARD_PLAN.md` 的核心主链路落到代码里，覆盖了三块内容：

1. 性能数据采集基建
2. 后端 Dashboard API
3. 前端 Admin Dashboard 页面

当前实现的目标不是只把页面“画出来”，而是先把后台真实指标链路跑通，让 dashboard 能基于真实业务表和真实聊天轮次数据工作。

---

## 2. 已落地的代码位置

### 2.1 性能采集

- `chatagent/bootstrap/src/main/resources/db/migration/V12__dashboard_chat_turn_metrics.sql`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/AgentRunResult.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/AgentRunException.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/CurrentTurnKnowledgeHitHolder.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/metrics/ChatTurnMetricRecorder.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/persistence/entity/ChatTurnMetric.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/persistence/mapper/ChatTurnMetricMapper.java`
- `chatagent/bootstrap/src/main/resources/mapper/ChatTurnMetricMapper.xml`

### 2.2 Dashboard API

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/admin/controller/DashboardController.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/admin/application/DashboardFacadeService.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/admin/application/DashboardFacadeServiceImpl.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/admin/model/vo/DashboardOverviewVO.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/admin/model/vo/DashboardPerformanceVO.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/admin/model/vo/DashboardTrendsVO.java`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/persistence/mapper/DashboardMapper.java`
- `chatagent/bootstrap/src/main/resources/mapper/DashboardMapper.xml`

### 2.3 前端页面

- `ui/src/components/admin/pages/AdminOverviewPage.tsx`
- `ui/src/components/admin/AdminSideNav.tsx`
- `ui/src/api/admin.ts`
- `ui/src/types/admin.ts`

---

## 3. 后端采集的实际落地方式

### 3.1 为什么先做 `AgentRunResult`

原来的 `ChatAgent.run()` 只有日志，没有结构化结果。这样 dashboard 很难知道：

- 这轮是否成功
- 总耗时是多少
- 是不是知识命中失败
- 错误属于哪一类

所以本次先把 `run()` 改成返回 `AgentRunResult`，失败时抛出带结果对象的 `AgentRunException`。

### 3.2 成功 / 失败路径如何写 metric

现在的链路是：

1. `ChatEventProcessor.process()` 调用 `chatAgent.run()`
2. 成功时，直接记录一条 `SUCCESS`
3. 失败时，异常会带着 `AgentRunResult` 冒泡
4. `publishFailure()` 在发兜底消息之前记录一条 `ERROR`

这样有两个好处：

- 不会丢失败指标
- 失败路径里还能保留真实耗时和错误类型

### 3.3 `knowledge_hit` 的实现策略

本轮采用的是“推荐方案 A”的简化版：

- 在 `SessionFileTools.knowledgeQuery()` 中拿到检索结果后
- 调用 `CurrentTurnKnowledgeHitHolder.recordRetrievalResult(!results.isEmpty())`
- `ChatAgent.run()` 结束时读取这个 thread-local 状态，写入 `knowledgeHit`

语义上是：

- 如果本轮没有发生检索，默认视为 `knowledgeHit=true`
- 如果检索过且至少一次命中，记为 `true`
- 如果检索过但全空，记为 `false`

### 3.4 示例代码

```java
// ChatAgent.run() 成功时返回结构化结果
return AgentRunResult.success(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit());
```

```java
// 失败时把结构化结果包进异常
throw new AgentRunException(
    "Error running agent",
    e,
    AgentRunResult.failure(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
);
```

```java
// ChatEventProcessor 成功路径记录 metric
AgentRunResult runResult = chatAgent.run();
recordMetricQuietly(event, runResult);
```

```java
// ChatEventProcessor 失败兜底路径记录 metric
recordMetricQuietly(event, resolveFailureMetric(ex));
```

---

## 4. Dashboard API 的实现思路

### 4.1 控制器

新增三个接口：

- `GET /api/admin/dashboard/overview`
- `GET /api/admin/dashboard/performance`
- `GET /api/admin/dashboard/trends`

全部挂在 `DashboardController` 下，并受 `@RequireRole(UserRole.ADMIN)` 保护。

### 4.2 Overview 的计算方式

`overview` 里有两类指标：

1. 累积指标
   - `totalUsers`
   - `totalSessions`
   - `totalMessages`
2. 窗口指标
   - `activeUsers`
   - `sessions24h`
   - `messages24h`

实现时统一用当前窗口和上一个窗口做对比：

- 当前窗口：`[now - duration, now)`
- 上一个窗口：`[now - 2 * duration, now - duration)`

其中：

- 累积值通过 `countXXXBefore(end)` 统计
- 窗口值通过 `countXXXBetween(start, end)` 统计

### 4.3 Performance 的计算方式

`performance` 直接查 `t_chat_turn_metric`：

- `avgLatencyMs`
- `successRate`
- `errorRate`
- `noDocRate`
- `slowRate`

其中 `p95LatencyMs` 采用“拉出成功耗时列表 + Java 排序取 95 分位”的方式，和计划文档保持一致。

### 4.4 Trends 的计算方式

趋势接口按 `metric` 分流：

- `sessions`
- `messages`
- `activeUsers`
- `avgLatency`
- `quality`

其中：

- `avgLatency` 返回两条线：`Avg latency` + `P95 latency`
- `quality` 返回两条线：`Error rate` + `Knowledge miss rate`

这样前端不需要拼很多额外接口就能直接画复合图。

### 4.5 关键 SQL 示例

```sql
SELECT COUNT(1) FILTER ( WHERE status = 'SUCCESS' ) AS "successCount",
       COUNT(1) FILTER ( WHERE status = 'ERROR' )   AS "errorCount",
       AVG(duration_ms) FILTER ( WHERE status = 'SUCCESS' ) AS "avgLatencyMs"
FROM t_chat_turn_metric
WHERE created_at >= #{start}
  AND created_at < #{end}
```

```sql
SELECT date_trunc(#{bucketUnit}, created_at) AS bucket,
       AVG(duration_ms) AS "avgLatencyMs",
       percentile_cont(0.95) WITHIN GROUP ( ORDER BY duration_ms ) AS "p95LatencyMs"
FROM t_chat_turn_metric
WHERE created_at >= #{start}
  AND created_at < #{end}
  AND status = 'SUCCESS'
GROUP BY bucket
ORDER BY bucket
```

```sql
SELECT date_trunc(#{bucketUnit}, cm.created_at) AS bucket,
       COUNT(DISTINCT cs.user_id) AS value
FROM chat_message cm
JOIN chat_session cs ON cm.session_id = cs.id
JOIN t_user tu ON cs.user_id = tu.id
WHERE cm.created_at >= #{start}
  AND cm.created_at < #{end}
  AND tu.deleted = FALSE
GROUP BY bucket
ORDER BY bucket
```

---

## 5. 前端 Dashboard 页的落地方式

### 5.1 为什么直接替换 Admin 首页

当前 `AdminOverviewPage.tsx` 只是 Phase 1C 的说明性页面，不是实际运营看板。

所以本轮直接把它升级成 Dashboard，而不是新建一个重复入口，原因是：

- 管理员进入 `/admin` 就能看到核心状态
- 现有路由和导航改动最小
- 不会同时维护两个“首页”

### 5.2 页面结构

当前页面结构按“总览 - 趋势 - 洞察”组织：

1. 顶部健康条
2. 6 个 KPI 卡片
3. Workload / Active Users / Quality / Latency 图表
4. 右侧 AI Health + Operator Insights + Recommended Checks

### 5.3 为什么用了 `recharts`

计划文档里明确要求引入图表库。本轮已经通过：

```bash
npm install recharts
```

并在页面中使用了：

- `AreaChart`
- `LineChart`
- `ReferenceLine`
- `ResponsiveContainer`

### 5.4 前端示例代码

```ts
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

```tsx
<ReferenceLine
  y={5000}
  stroke="rgba(255,122,69,0.55)"
  strokeDasharray="4 4"
  label={{ value: "5s", fill: "rgba(255,122,69,0.75)" }}
/>
```

```tsx
<Progress
  type="circle"
  percent={Number(performance.successRate.toFixed(1))}
  strokeColor="#52c41a"
/>
```

---

## 6. 当前验证结果

### 6.1 已通过

- `ui` 前端生产构建通过
  - 命令：`npm run build`
- 后端改动相关单测通过
  - 命令：`./mvnw.cmd -pl bootstrap -am "-Dtest=ChatEventProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- dashboard service/controller 专项单测通过
  - 命令：`./mvnw.cmd -pl bootstrap -am "-Dtest=DashboardFacadeServiceImplTest,DashboardControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- 真实 PostgreSQL migration 已执行
  - 命令：`./mvnw.cmd -pl bootstrap org.flywaydb:flyway-maven-plugin:11.7.2:migrate -Dflyway.url=... -Dflyway.user=... -Dflyway.password=... -Dflyway.locations=filesystem:bootstrap/src/main/resources/db/migration`
  - 结果：`public` schema 从 `v11` 升级到 `v12`
  - 校验：`flyway_schema_history` 已存在 `12 | dashboard chat turn metrics | true`
  - 校验：`t_chat_turn_metric` 表已创建成功
- 真实接口联调验收通过
  - 本次以本地 admin JWT 直接访问运行中的后端服务
  - 验收接口：`/api/user/me`、`/api/admin/dashboard/overview`、`/api/admin/dashboard/performance`、`/api/admin/dashboard/trends?metric=quality`
  - 验收结果：
    - `user/me` 返回管理员身份成功
    - `performance` 返回值与临时 smoke 数据完全一致：`avgLatencyMs=1200`、`p95LatencyMs=1200`、`successRate=50`、`errorRate=50`、`noDocRate=100`、`slowRate=50`
    - `quality trend` 返回值与临时 smoke 数据完全一致：`Error rate=50`、`Knowledge miss rate=100`
  - 说明：联调时临时插入了一组可回收 smoke 数据，验收完成后已删除，`t_chat_turn_metric` 当前已回到 `0` 行

### 6.2 已知情况

整仓 `mvn test` 里仍有一些环境依赖型测试，不适合作为这次 dashboard 改动的唯一验收手段；本轮优先保证：

- dashboard 相关主代码可编译
- 前端可构建
- 核心采集链路的单测可通过
- dashboard service/controller 层测试可通过
- 真实数据库 migration 可执行
- 真实接口联调结果与数据库 smoke 数据一致

---

## 7. 下一步建议

如果继续沿着这版往下推进，建议按下面顺序做：

1. 让真实聊天流量持续写入 `t_chat_turn_metric`，观察 24h / 7d 窗口下的真实 dashboard 表现
2. 用生产或准生产数据校正前端洞察文案、阈值和告警颜色
3. 视数据量决定是否给趋势查询增加缓存或物化视图
4. 如果后续要做 dashboard 二期，再补更细粒度的 model-level / agent-level 指标拆分

---

## 8. 本轮取舍总结

本轮故意优先选择“低侵入、可快速上线”的方案：

- 不引入缓存层
- 不改现有 admin 路由骨架
- 不新建复杂 repository 层
- 先用 thread-local 把 `knowledge_hit` 串起来

这样可以更快把 dashboard 从 0 推到“有真实数据、有真实页面、有可验证代码”的阶段。后续如果要继续演进，再把测试覆盖和查询性能做深。
