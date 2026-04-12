# MQ 异步处理模块 (com.yulong.chatagent.mq)

## 模块概述

MQ 模块基于 RabbitMQ 构建了**分布式异步任务系统**，实现了事务性发件箱 (Transactional Outbox) 模式确保消息不丢失、三态分布式锁保证幂等消费、结构化重试与死信队列处理不可恢复的故障。支持两种任务类型：Agent 运行调度和知识文档摄取。

**核心代码路径：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/mq/`

---

## 1. 总体架构

```
┌──────────────────────────────────────────────────────────┐
│                    生产者端                                │
│                                                          │
│  业务方法 (@Transactional)                                │
│       │                                                  │
│       ▼                                                  │
│  OutboxEventPublisher.publish()                          │
│       │ → 写入 t_mq_outbox (在同一事务中)                  │
│       │ → ON CONFLICT DO NOTHING (UUIDv5 确定性ID)        │
│                                                          │
│  OutboxPollingPublisher (定时 2s)                         │
│       │ → SELECT ... FOR UPDATE SKIP LOCKED               │
│       │ → 发布到 RabbitMQ                                 │
│       │ → publisher confirm 确认                          │
│       │ → markSent                                        │
└──────────────────────────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────┐
│                RabbitMQ 拓扑                              │
│                                                          │
│  chat.direct (Exchange)                                  │
│    ├── chat.agent.dispatch (Queue, DLX → retry.direct)   │
│    └── knowledge.ingest.task (Queue, DLX → retry.direct) │
│                                                          │
│  retry.direct (Exchange)                                 │
│    ├── retry.agent.10s (Queue, TTL=10s, DLX → chat.direct)│
│    └── retry.ingest.30s (Queue, TTL=30s, DLX → chat.direct)│
│                                                          │
│  dlx.direct (Exchange)                                   │
│    └── chat.dlq (Queue) — 终极死信队列                    │
└──────────────────────────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────┐
│                    消费者端                                │
│                                                          │
│  AbstractRetryingMqConsumer                              │
│    ├── 1. 读取消息身份头                                   │
│    ├── 2. 分布式锁获取 (task lock)                        │
│    ├── 3. 会话执行锁获取 (session exec lock)              │
│    ├── 4. LockWatchdog 启动                               │
│    ├── 5. processTask()                                   │
│    ├── 6. 成功 → markCompleted + ack                      │
│    ├── 7. 可重试失败 → 发布到 retry exchange + ack         │
│    └── 8. 不可重试 → reject to DLQ + markFailed           │
│                                                          │
│  AgentRunTaskListener (agent.run)                         │
│  KnowledgeIngestTaskListener (ingest.task)                │
└──────────────────────────────────────────────────────────┘
```

---

## 2. RabbitMQ 拓扑设计

### 2.1 三级 Exchange 架构

**文件：** `config/RabbitMqTopologyConfiguration.java`

```
chat.direct (主 Exchange)
    │ 消息进入主队列处理
    │ 处理失败 → DLX routing 到 retry.direct
    ▼
retry.direct (重试 Exchange)
    │ 重试队列有 TTL 延迟 (10s/30s)
    │ TTL 到期 → DLX routing 回 chat.direct
    ▼
dlx.direct (死信 Exchange)
    │ 重试耗尽的消息
    ▼
chat.dlq (终极死信队列)
    │ 管理员可手动重放
```

### 2.2 队列配置

| 队列 | TTL | DLX 路由 |
|------|-----|---------|
| `chat.agent.dispatch` | - | → retry.direct (retry.agent) |
| `knowledge.ingest.task` | - | → retry.direct (retry.ingest) |
| `retry.agent.10s` | 10s | → chat.direct (agent.run) |
| `retry.ingest.30s` | 30s | → chat.direct (ingest.task) |
| `chat.dlq` | - | 终点 |

**重试闭环：** 主队列 → 重试队列(TTL延迟) → 回到主队列 → ... → DLQ

---

## 3. 事务性发件箱 (Transactional Outbox)

### 3.1 设计思想

传统方案在业务事务中直接发送 MQ 消息，存在**消息丢失风险**：数据库提交成功但 MQ 发送失败。发件箱模式将消息写入同一数据库事务中的 outbox 表，由独立的轮询发布器异步发送到 MQ，确保**至少一次投递**。

### 3.2 OutboxEventPublisher (发件箱写入)

**文件：** `outbox/OutboxEventPublisher.java`

```java
publish(eventType, idempotencyKey, payload, headers):
    1. UuidV5Generator.generate(namespace, eventType + ":" + idempotencyKey)
       → 确定性 UUID，同一事件不会重复插入
    2. 序列化 payload 和 headers
    3. INSERT INTO t_mq_outbox AS PENDING
    4. ON CONFLICT DO NOTHING (幂等)
```

**关键：** 写入在调用方的 `@Transactional` 方法内执行，与业务操作原子性保证。

### 3.3 OutboxPollingPublisher (轮询发布)

**文件：** `outbox/OutboxPollingPublisher.java`

```
publishDueRows() (定时 2s):
    1. claimBatch() → SELECT ... FOR UPDATE SKIP LOCKED
       → 多实例安全，不会重复 claim
    2. 逐条发布到 RabbitMQ：
       - publisher confirm 确认 (超时 5s)
       - markSent() → 乐观锁更新状态
       - 乐观锁冲突 → scheduleDiscardedConflict()
    3. cleanupSentRows() (每天) → 删除超过保留期(7天)的已发送行
```

### 3.4 Outbox 状态机

```
PENDING → CLAIMED → SENT
                 \→ FAILED → PENDING (重试)
                 \→ PERMANENTLY_FAILED (超过最大重试次数)
                 \→ DISCARDED (乐观锁冲突)
```

---

## 4. 分布式锁系统

### 4.1 DistributedLockManager (分布式锁管理器)

**文件：** `lock/DistributedLockManager.java` (413行)

#### 三态锁状态机

```
                tryAcquire (新任务)
RUNNING ◄────────────────────────────
  │  │                              │
  │  │ LockWatchdog 续期 (每20s)     │ tryAcquire (已有RUNNING)
  │  │                              │ → 返回 WAIT_REQUIRED
  │  ▼                              │
  │  任务完成 → COMPLETED            │
  │  (TTL 24h, 防止重复消费)         │
  │                                 │
  │  任务失败 → FAILED               │
  │  (TTL 1h, 可被重新 claim)        │
  │         │                       │
  └─────────┘ tryAcquire 检测到 FAILED
              → 重置为 RUNNING
```

#### 锁获取流程 (tryAcquire, 第46-94行)

```
1. 尝试 setIfAbsent 最多 3 次
2. 已存在锁：
   a. COMPLETED → 返回 DUPLICATE (跳过)
   b. FAILED → CLAIM_FAILED_SCRIPT (原子转为 RUNNING)
   c. RUNNING → 返回 WAIT_REQUIRED (需要等待)
3. 设置成功 → 返回 ACQUIRED (继续处理)
```

#### Lua 脚本保证原子性

| 脚本 | 用途 |
|------|------|
| RENEW_SCRIPT | 验证 RUNNING + token 匹配后续期 |
| SET_STATE_SCRIPT | 验证 token 匹配后修改状态 |
| RELEASE_SCRIPT | 仅删除 RUNNING 且 token 匹配的锁 |
| CLAIM_FAILED_SCRIPT | 仅在 FAILED 状态下 claim |

### 4.2 LockWatchdog (锁看门狗)

**文件：** `lock/LockWatchdog.java`

```
watch(registration):
    1. 每 20s 执行一次 renewQuietly()
    2. 续期 task lock + session exec lock
    3. 返回 AutoCloseable Registration (取消定时任务)
```

**使用方式：**
```java
try (LockWatchdog.Registration reg = watchdog.watch(taskLease, sessionLease)) {
    processTask(); // 长时间运行的任务
} // finally 自动取消续期
```

### 4.3 双锁模型

| 锁 | Key 前缀 | 用途 |
|----|---------|------|
| Task Lock | `chatagent:mq:task-lock:{idempotencyKey}` | 幂等消费，防止同一任务重复执行 |
| Session Exec Lock | `chatagent:mq:session-exec-lock:{sessionId}` | 会话互斥，防止同一会话的 Agent 并发执行 |

### 4.4 Redis 故障策略 (RedisFailurePolicy)

| 策略 | 适用场景 | 行为 |
|------|---------|------|
| `FAIL_OPEN` | Agent Run, Session Exec | Redis 故障不阻塞，继续执行（丢失幂等保护） |
| `FAIL_FAST` | Knowledge Ingest | Redis 故障直接拒绝，重新入队 |

---

## 5. 消费者框架

### 5.1 AbstractRetryingMqConsumer (抽象消费者基类)

**文件：** `consumer/AbstractRetryingMqConsumer.java` (365行)

```
consume(message):
    1. 读取 MqMessageIdentity (7个不可变 header)
    2. 设置 TraceContext
    3. 获取 task lock (distributedLockManager.tryAcquire)
       → DUPLICATE → ack + 跳过
       → WAIT_REQUIRED → requeueWithDelay
       → Redis 失败 → 根据 RedisFailurePolicy 决定
    4. 获取 session exec lock (Agent Run 专用)
    5. 启动 LockWatchdog
    6. 调用 processTask()
       → 成功 → markCompleted + ack
       → 可重试失败 → handleRetryableFailure
       → 不可重试失败 → handleTerminalFailure
    7. handleRetryableFailure:
       → retryCount >= maxRetryCount → reject to DLQ + markFailed
       → 否则 → releaseRunning + 发布到 retry exchange + ack
    8. handleTerminalFailure:
       → markFailed + reject
```

**重试入队不是 nack+requeue，而是发布到 retry exchange**，确保经过 TTL 延迟。

### 5.2 AgentRunTaskListener (Agent 运行消费者)

**文件：** `consumer/AgentRunTaskListener.java`

```
processTask():
    1. 转换 payload → ChatEvent
    2. 如果是重试或 forceRollback → rollbackTurn()
       → 删除该轮次的助手和工具消息 + TURN_ROLLBACK SSE
    3. ChatEventProcessor.process(chatEvent)
```

| 配置 | 值 |
|------|-----|
| 最大重试次数 | 3 |
| 重试路由键 | retry.agent |
| 不可重试异常 | IllegalArgumentException, ClientException |
| 并发数 | 5 |
| 预取 | 5 |

### 5.3 KnowledgeIngestTaskListener (知识摄取消费者)

**文件：** `consumer/KnowledgeIngestTaskListener.java`

```
processTask():
    1. loadDocument() → 验证文档存在、未删除、属于正确 KB
    2. clearExistingContentFirst → 清理旧 chunks + Milvus 向量
    3. knowledgeDocumentIngestionService.ingestSync()
```

| 配置 | 值 |
|------|-----|
| 最大重试次数 | 3 |
| 重试路由键 | retry.ingest |
| 可重试异常 | RetryableKnowledgeDocumentIngestionException |
| 并发数 | 2 |
| 预取 | 1 |

---

## 6. 消息身份链 (Message Identity)

### 6.1 MqMessageHeaders (消息头规范)

**文件：** `support/MqMessageHeaders.java`

7 个不可变 header：

| Header | 说明 |
|--------|------|
| `x-event-id` | 事件唯一 ID |
| `x-idempotency-key` | 幂等键 |
| `x-trace-id` | 链路追踪 ID |
| `x-task-type` | 任务类型 |
| `x-session-id` | 会话 ID |
| `x-original-exchange` | 原始 Exchange |
| `x-original-routing-key` | 原始路由键 |

**不可变性：** 这些 header 在重试过程中保持不变，确保全链路可追踪。

### 6.2 MqMessageIdentity (消息身份记录)

**文件：** `support/MqMessageIdentity.java`

包含所有 header + `firstPublishedAt` 和 `retryCount`。
- `initial()` 工厂方法创建新身份
- `withRetryCount()` 创建递增 retryCount 的新实例（其他字段不变）

---

## 7. 分发策略切换

### SwitchingChatEventDispatcher

**文件：** `conversation/event/SwitchingChatEventDispatcher.java`

```
dispatch(chatEvent):
    if (mqEnabled && agentRunEnabled):
        → MqChatEventDispatcher
            → OutboxEventPublisher.publish()
    else:
        → LocalChatEventDispatcher
            → ApplicationEventPublisher.publishEvent()
```

支持渐进式迁移：通过配置开关逐个任务类型切换到 MQ 路径。

---

## 8. DLQ 重放

管理员可通过 Admin API (`/api/admin/mq`) 重放 DLQ 消息：
- 可选重置重试次数
- 可选设置 `forceRollback=true`（Agent Run 任务在重试前强制回滚）

---

## 9. 技术亮点总结

### 高可用
- **事务性发件箱：** 业务操作和消息写入同一事务，保证不丢失
- **至少一次投递：** Outbox 轮询 + publisher confirm
- **SKIP LOCKED：** 多实例安全地 claim outbox 行

### 幂等消费
- **三态分布式锁：** RUNNING / COMPLETED / FAILED
- **UUIDv5 确定性 ID：** 同一事件不会重复写入 outbox
- **COMPLETED 状态 24h TTL：** 防止 MQ 重投递导致重复消费
- **FAILED 状态可重新 claim：** 故障恢复后可重新处理

### 高并发
- **双锁模型：** Task Lock (幂等) + Session Exec Lock (会话互斥)
- **Fail-Open / Fail-Fast：** 按任务类型选择 Redis 故障策略
- **LockWatchdog 续期：** 长时间任务不会因锁过期被抢占

### 重试与降级
- **闭环重试拓扑：** 主队列 → TTL 延迟重试队列 → 回到主队列
- **可配置最大重试次数：** Agent Run 和 Ingest 独立配置
- **终极死信队列：** 不可恢复的消息进入 DLQ，管理员可手动重放
- **TURN_ROLLBACK：** Agent 重试前回滚已产出的消息

### 可追踪性
- **消息身份链：** 7 个不可变 header 贯穿全链路
- **TraceContext 传播：** 消费者继承生产者的 traceId
- **Outbox 状态追踪：** PENDING → CLAIMED → SENT 完整状态机
