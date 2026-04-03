# MQ 异步架构 Stage 4C+ 补丁落地清单 (V3.7 Final)

## 1. 背景与目标
在针对 Stage 4A/4B/4C 的深度对抗性评审中，识别出了分布式环境下的关键缺陷：特别是 **RUNNING 锁热循环导致的 CPU 熔断**、分布式 SSE 消息丢失、以及重试幂等不足。本计划旨在加固架构底座，确保异步链路的“At-least-once”保证与高可用性。

---

## 2. 核心修补任务清单

### 任务 1：分布式 SSE 消息分发 (架构阻断项 - P0)
- **1.1 接口拆分**：`SseService` 拆分 `publish` (广播) 与 `deliverLocal` (本地投递)。
- **1.2 事务一致性保证**：`publish` 必须注册在 Spring 事务 `afterCommit` 中执行，防止数据“见光死”竞态。
- **1.3 前端补偿机制 (Self-Healing)**：
    - **ID 锚定**：由前端生成 `turnId` (UUIDv4) 随 POST 提交。正则校验：`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`。
    - **状态持久化**：提问时将 `turnId` 写入 `sessionStorage`，防止页面刷新导致补偿上下文丢失。
    - **判定逻辑**：轮询 `GET /api/chat-messages`。若 `turnId` 匹配的消息总数增加或出现新的 `TOOL`，则判定 Agent 活跃，**重置 30s 计时器**。
    - **UX Tradeoff**：15s 时提示“连接不稳，正在核实状态...”，30s 无更新则按超时处理。

### 任务 3：会话级并发拦截 (双维防御模型 - P2)
- **3.1 入口拦截 (Redis Session Lock)**：仅拦截提问入口。使用 Lua CAS 释放。策略：**Fail-open**（Redis 故障不阻塞提问）。
- **3.2 执行串行化 (Distributed Exec Lock)**：在消费者执行 Agent 逻辑前，强制获取 **Session 级执行锁**。
    - **生命周期**：Session 执行锁必须与 Task 锁共享 Watchdog 续期，并在任务结束（完成/失败/重入队）时同步释放。

### 任务 5：Stage 4A/4B 可靠性加固 (架构底座补丁 - P0/P2)
- **5.1 延迟重入队防御 (Hot Loop DoS)**：获取 Session 锁或 Task 锁失败（`WAIT_REQUIRED`）时，**严禁**直接 `nack(true)`。必须通过延迟队列延迟 1s-5s 后重入。
- **5.2 Outbox 幽灵重发防御 (P2)**
    - **写入端固化**：主键算法 `UUIDv5(namespace, eventType + ":" + turnId)`。使用项目专属固定 Namespace。
    - **幂等写入**：`rows = insert ... ON CONFLICT DO NOTHING`。若 `rows == 0` 则 `log.warn` 记录冲突。
    - **死锁防御 (Critical)**：`markDiscarded` (清理冲突行) 禁止在 Poller 批量循环内执行 `REQUIRES_NEW`。应改为异步清理或在批处理事务外执行。

---

## 3. 落地优先级建议
1. **P0**: 延迟重入 (5.1) & SSE 事务后置 (1.2)。
2. **P1**: **前端 ID 固化** & 写入端 UUIDv5 (5.2) & **执行串行化 (3.2)**。
3. **P2**: 会话锁 (3.1) & UI 状态持久化 (1.3)。

---

## 5. P2 任务详细落地计划

### 任务 3.2：分布式执行串行化 (Robust Dual-Locking)

#### 方案
升级 `AbstractRetryingMqConsumer` 生命周期，引入 **“双锁模型”**：在处理 Agent 任务前，必须同时持有 `session-exec-lock:{sessionId}` 和 `task-lock:{turnId}`。

#### 改动清单
| 步骤 | 文件 | 内容 |
|:---|:---|:---|
| 1 | `MqMessageIdentity` | 新增 `private String sessionId;` (Optional) 字段，支持从 Headers 传递 Session 维度。 |
| 2 | `DistributedLockManager` | 新增 `acquireSessionExecLock` 接口；支持 Session 锁的 CAS 释放；定义降级配置（FAIL_OPEN/FAIL_FAST）。 |
| 3 | `AbstractRetryingMqConsumer` | **生命周期重构 (Critical)**：使用 `try-with-resources` 或 `finally` 强制确保 **Session 执行锁在所有路径均被释放**；统一两个锁的 `WAIT_REQUIRED` 引导至延迟重入。 |
| 4 | `LockWatchdog` | **双锁续期**：支持同时 Watch 两个 Lease（Session + Task）。 |

---

### 任务 5.2：Outbox 幽灵重发防御

#### 冲突判定逻辑
重构 `OutboxRecordService.markSent` 签名，由抛异常改为返回 `boolean`。Poller 捕获冲突后调 `markDiscarded`。

#### DB 兜底索引方案
**部署顺序**：**1. UI 升级 (turnId)** -> **2. 后端逻辑升级 (ID 固化 + 双锁机制)** -> **3. Flyway 迁移**。

```sql
-- flyway:executeInTransaction=false
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_mq_outbox_sent_idempotency
    ON t_mq_outbox (((headers ->> 'x-idempotency-key')), event_type)
    WHERE status = 'SENT' AND (headers ->> 'x-idempotency-key') IS NOT NULL;
```

---

## 6. 实施细节与运维约束 (Implementation & Ops Notes)

### 6.1 Identity 兼容性 (P1)
- **要求**：`MqMessageIdentity` 的 Compact Constructor 必须允许 `sessionId` 为 null。
- **目的**：确保非会话类任务（如知识库摄入）在消息反序列化时不会因为缺少 sessionId 而崩溃。

### 6.2 索引完整性检查 (P2)
- **要求**：在 Flyway 迁移后的运维 Checklist 中增加 `indisvalid` 核验。
- **脚本**：`SELECT indisvalid FROM pg_index WHERE indexrelid = 'ux_mq_outbox_sent_idempotency'::regclass;`
- **目的**：预防 `CREATE INDEX CONCURRENTLY` 失败后留下的幽灵 INVALID 索引导致后续创建被跳过。

### 6.3 异步清理鲁棒性 (P2)
- **要求**：`markDiscarded` 的异步执行线程池必须配置为 **`CallerRunsPolicy`**。
- **目的**：确保在线程池满载时，清理逻辑能退避为同步执行，防止 Poison Pill 行因处理不及时而被无限次重新抓取。
