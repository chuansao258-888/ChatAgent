# MQ 异步架构重构 架构实现文档

> 对应计划：`docs/plans/MQ_REFACTOR_PLAN.md` + `docs/plans/MQ_ASYNC_REFACTOR_REMEDIATION_PLAN.md`
> 最后更新：2026-04-11

## 1. 概述

### 1.1 目标与范围

构建具备"确定性投递、状态感知、非阻塞容错"特性的分布式异步任务体系。覆盖两个核心任务：
- `knowledge.ingest.task`：知识文档入库（首期迁移，已上线）
- `chat.agent.dispatch`：Agent 执行分发（灰度迁移，可开关）

### 1.2 核心设计决策

1. **Transactional Outbox**：通过本地事务表 `t_mq_outbox` 保证 DB 与 MQ 发送的原子性。
2. **三态幂等锁**：基于 Redis 的 RUNNING（Watchdog 续期）/ COMPLETED / FAILED 状态机。
3. **专用重试拓扑**：独立重试队列 + `x-dead-letter-routing-key` 闭合回流路径。
4. **消息身份链**：固定 Header Schema（x-event-id, x-idempotency-key, x-trace-id 等），不可变。
5. **灰度迁移**：`SwitchingChatEventDispatcher` 按任务类型独立开关，不采用一刀切切换。
6. **双锁模型**：Session 执行锁 + Task 锁，防止同一会话的 Agent 任务并发执行。
7. **延迟重入队**：锁竞争时通过 retry queue 延迟重入，防止 RUNNING 锁热循环。

## 2. 整体架构

### 2.1 MQ 拓扑架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        生产者侧                                   │
│  BusinessService → OutboxEventPublisher (事务内写入)              │
│       ↓                                                          │
│  t_mq_outbox (PENDING)                                          │
│       ↓                                                          │
│  OutboxPollingPublisher (定时轮询, SKIP LOCKED)                   │
│       ↓                                                          │
│  RabbitMqMessagePublisher (Publisher Confirms)                   │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│                     RabbitMQ 拓扑                                 │
│                                                                  │
│  chat.direct (Exchange)                                         │
│  ├── chat.agent.dispatch (Queue) ─── DLX → dlx.direct           │
│  └── knowledge.ingest.task (Queue) ─ DLX → dlx.direct           │
│                                                                  │
│  retry.direct (Exchange)                                        │
│  ├── retry.agent.10s (TTL=10s) ──── DLK → agent.run (回流)      │
│  └── retry.ingest.30s (TTL=30s) ─── DLK → ingest.task (回流)    │
│                                                                  │
│  dlx.direct (Exchange)                                          │
│  └── chat.dlq (最终死信队列)                                     │
└───────────────────────┬─────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────┐
│                       消费者侧                                    │
│  AbstractRetryingMqConsumer                                      │
│  ├── 双锁获取 (Task Lock + Session Exec Lock)                    │
│  ├── LockWatchdog 续期                                           │
│  ├── 成功: basicAck + markCompleted                              │
│  ├── 可重试失败: 投递至 retry queue + basicAck                    │
│  ├── 重试耗尽: basicReject → DLQ + markFailed                    │
│  └── 不可恢复异常: basicReject → DLQ                              │
│                                                                  │
│  具体消费者:                                                      │
│  ├── KnowledgeIngestTaskListener (maxRetry=3)                    │
│  └── AgentRunTaskListener (maxRetry=3, 可开关)                   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 消息处理流程

```
消息到达消费者
    │
    ├── 读取 MqMessageIdentity (Headers)
    │
    ├── tryAcquire Task Lock (Redis)
    │   ├── ACQUIRED → 继续
    │   ├── DUPLICATE → 丢弃 (basicAck)
    │   └── WAIT_REQUIRED → 延迟重入 retry queue
    │
    ├── acquireSessionExecLock (仅 agent.run)
    │
    ├── 启动 LockWatchdog (双锁续期)
    │
    ├── processTask()
    │   ├── 成功 → markCompleted + basicAck
    │   ├── RetryableException → 投递 retry queue (retryCount++)
    │   └── 不可恢复异常 → markFailed + basicReject → DLQ
    │
    └── finally: 释放双锁 + 取消 Watchdog
```

### 2.3 消息分发路由

```
ConversationOrchestrator.handleUserTurn()
    │
    ▼
ChatEventDispatcher (接口)
    │
    ├── SwitchingChatEventDispatcher (@Primary)
    │   ├── chatagent.mq.enabled=false → LocalChatEventDispatcher
    │   ├── agent-run-enabled=false → LocalChatEventDispatcher
    │   └── both=true → MqChatEventDispatcher
    │
    ├── MqChatEventDispatcher
    │   └── OutboxEventPublisher (事务内写入 t_mq_outbox)
    │
    └── LocalChatEventDispatcher
        └── Spring ApplicationEventPublisher (进程内)
```

## 3. 文件清单

### 3.1 数据库迁移

| 文件路径 | 说明 |
|---|---|
| `V7__phase5_mq_outbox.sql` | 创建 `t_mq_outbox` 表 |
| `V8__phase5_mq_outbox_idempotency.sql` | 去重历史 SENT 行 |
| `V9__phase5_mq_outbox_idempotency_index.sql` | 唯一表达式索引 `(headers->>'x-idempotency-key', event_type) WHERE status='SENT'` |

### 3.2 后端代码

| 文件路径 | 职责 |
|---|---|
| **配置** | |
| `mq/config/ChatAgentMqProperties.java` | `@ConfigurationProperties(prefix = "chatagent.mq")` 绑定全部 MQ 配置 |
| `mq/config/MqRuntimeConfiguration.java` | `@EnableRabbit` + `@EnableScheduling`，定义两个 ListenerContainerFactory |
| `mq/config/RabbitMqTopologyConfiguration.java` | Exchange / Queue / Binding 拓扑声明 |
| **Outbox** | |
| `mq/outbox/OutboxEventPublisher.java` | 事务内写入 outbox 行（UUIDv5 确定性 ID） |
| `mq/outbox/OutboxPollingPublisher.java` | 定时轮询 PENDING 行，发布到 MQ（Publisher Confirms） |
| `mq/outbox/OutboxRecordService.java` | Outbox 状态机（PENDING → CLAIMED → SENT / FAILED） |
| `mq/outbox/OutboxRepository.java` | Outbox Repository 接口 |
| `mq/outbox/UuidV5Generator.java` | RFC 4122 UUIDv5 生成器 |
| `mq/outbox/event/AgentRunTaskPayload.java` | Agent 执行任务载荷 |
| `mq/outbox/event/KnowledgeIngestTaskPayload.java` | 知识文档入库任务载荷 |
| **锁与幂等** | |
| `mq/lock/DistributedLockManager.java` | Redis 三态锁（RUNNING/COMPLETED/FAILED）+ Session 执行锁 |
| `mq/lock/LockWatchdog.java` | 双锁续期守护线程（ScheduledExecutor, 2 daemon threads） |
| `mq/lock/MqTaskLockState.java` | 锁状态枚举 |
| `mq/lock/MqTaskLockAcquireOutcome.java` | 获取结果枚举（ACQUIRED/DUPLICATE/WAIT_REQUIRED） |
| `mq/lock/MqTaskLockLease.java` | Task 锁租约 |
| `mq/lock/MqSessionExecLockLease.java` | Session 执行锁租约 |
| **消息支持** | |
| `mq/support/MqMessageIdentity.java` | 消息身份链（不可变字段 + retryCount） |
| `mq/support/MqMessageHeaders.java` | Header 名称常量 + apply/read/fromMap |
| `mq/support/RabbitMqMessagePublisher.java` | 带 Publisher Confirms 的消息发布 |
| **消费者** | |
| `mq/consumer/AbstractRetryingMqConsumer.java` | 消费者基类（双锁 + 重试 + DLQ） |
| `mq/consumer/KnowledgeIngestTaskListener.java` | 知识文档入库消费者（maxRetry=3） |
| `mq/consumer/AgentRunTaskListener.java` | Agent 执行消费者（maxRetry=3，可开关） |
| **分发路由** | |
| `conversation/event/ChatEventDispatcher.java` | 分发接口 |
| `conversation/event/SwitchingChatEventDispatcher.java` | 开关路由（@Primary） |
| `conversation/event/MqChatEventDispatcher.java` | MQ 路径（Outbox） |
| `conversation/event/LocalChatEventDispatcher.java` | 本地路径（Spring Event） |
| **SSE 分布式** | |
| `framework/sse/SseService.java` | SSE 接口（publish 广播 / deliverLocal 本地） |
| `framework/sse/DefaultSseService.java` | Redis Pub/Sub 实现 |
| **Admin** | |
| `admin/controller/MqAdminController.java` | MQ 运维 REST 接口 |
| `admin/application/MqAdminFacadeServiceImpl.java` | DLQ 重放 + Outbox 状态查询 |
| **持久化** | |
| `support/persistence/entity/MqOutbox.java` | Outbox 实体 |
| `support/persistence/mapper/MqOutboxMapper.java` | MyBatis Mapper |
| `resources/mapper/MqOutboxMapper.xml` | SQL（含 `FOR UPDATE SKIP LOCKED`） |

### 3.3 配置文件

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `chatagent.mq.enabled` | — | MQ 总开关 |
| `chatagent.mq.dispatchers.agent-run-enabled` | — | Agent 执行 MQ 分发开关 |
| `spring.rabbitmq.publisher-confirm-type` | `correlated` | Publisher Confirms |
| `chatagent.mq.locks.policy.agent-run` | `FAIL_OPEN` | Agent 锁策略（Redis 故障不阻塞） |
| `chatagent.mq.locks.policy.knowledge-ingest` | `FAIL_FAST` | 入库锁策略（Redis 故障拒绝） |

## 4. 核心功能实现

### 4.1 Transactional Outbox

**实现位置：** `mq/outbox/OutboxEventPublisher.java`

**实现逻辑：**

1. 在业务事务内写入一行到 `t_mq_outbox`（状态 PENDING）
2. 使用 UUIDv5（`eventType + idempotencyKey`）生成确定性 ID
3. `ON CONFLICT DO NOTHING` 处理重复插入

**Outbox 状态机：**

```
PENDING → CLAIMED (Poller 抢占, SKIP LOCKED)
    → SENT (Publisher Confirm 成功)
    → FAILED (超过 5 次发送失败)
```

**Poller 抢占语义：** `SELECT ... FOR UPDATE SKIP LOCKED`，多实例安全。

```java
// OutboxEventPublisher — 事务内写入
public void publish(String eventType, String exchange, String routingKey,
                    Object payload, MqMessageIdentity identity) {
    MqOutbox record = MqOutbox.builder()
        .id(UuidV5Generator.generate(eventType, identity.idempotencyKey()))
        .eventType(eventType)
        .exchange(exchange)
        .routingKey(routingKey)
        .payload(serializePayload(payload))
        .headers(MqMessageHeaders.toMap(identity))
        .status("PENDING")
        .retryCount(0)
        .build();
    outboxRepository.insert(record); // ON CONFLICT DO NOTHING
}
```

### 4.2 三态幂等锁

**实现位置：** `mq/lock/DistributedLockManager.java`

**状态机：**

```
RUNNING ─── markCompleted ──→ COMPLETED (TTL 24h)
    │
    ├── markFailed ──→ FAILED (TTL 1h, 可被下次消费自然失效)
    │
    └── Watchdog 续期 (每 20s)
```

**Redis Key 前缀：**
- Task Lock: `chatagent:mq:task-lock:{idempotencyKey}`
- Session Exec Lock: `chatagent:mq:session-exec-lock:{sessionId}`

**获取结果：**

| 结果 | 行为 |
|---|---|
| ACQUIRED | 成功获取，启动 Watchdog |
| DUPLICATE | 已完成/运行中，丢弃消息 |
| WAIT_REQUIRED | 锁竞争中，延迟重入 retry queue |

### 4.3 LockWatchdog

**实现位置：** `mq/lock/LockWatchdog.java`

**实现逻辑：** `ScheduledThreadPoolExecutor(2)` 每 20s 对 RUNNING 锁执行续期。支持同时 Watch 两个 Lease（Session + Task）。`Registration` 实现 `AutoCloseable`，关闭时取消续期。

### 4.4 消费者基类 — AbstractRetryingMqConsumer

**实现位置：** `mq/consumer/AbstractRetryingMqConsumer.java`

**核心流程：**

1. 从 Headers 读取 `MqMessageIdentity`
2. `tryAcquire` Task Lock → 处理 DUPLICATE / WAIT_REQUIRED
3. 如果是 agent.run 任务，`acquireSessionExecLock`
4. 启动 `LockWatchdog`（双锁续期）
5. 调用子类 `processTask()`
6. 成功 → `markCompleted` + `basicAck`
7. RetryableException → 投递到 retry queue（retryCount++）
8. 重试耗尽 → `markFailed` + `basicReject` → DLQ
9. 不可恢复异常 → `basicReject` → DLQ

```java
// 重试投递示例
Message retryMessage = MessageBuilder.fromMessage(originalMessage)
    .setHeader("x-retry-count", getRetryCount(headers) + 1)
    .setHeaderIfAbsent("x-event-id", eventId)
    .setHeaderIfAbsent("x-idempotency-key", idempotencyKey)
    .build();
rabbitTemplate.send("retry.direct", retryRoutingKey, retryMessage);
channel.basicAck(tag, false);
```

### 4.5 SSE 分布式广播

**实现位置：** `framework/sse/DefaultSseService.java`

**实现逻辑：**

- `publish(streamKey, message)`：序列化为 JSON，发送到 Redis channel `chatagent:sse:broadcast`
- `deliverLocal(streamKey, message)`：查找本地 `SseEmitter` 直接投递
- Redis 订阅端 `SseMessageReceiver` 反序列化后调用 `deliverLocal`

**关键约束：** `publish` 必须注册在 Spring 事务 `afterCommit` 中执行，防止数据"见光死"竞态。

### 4.6 DLQ 重放

**实现位置：** `admin/application/MqAdminFacadeServiceImpl.java`

**实现逻辑：**

1. `basicGet` 从 `chat.dlq` 取出消息
2. 可选重置 `x-retry-count` 为 0
3. agent.run 消息设置 `forceRollback=true`（清理上次部分输出）
4. 投递到原始 exchange/routing-key

**Admin API：**
- `GET /api/admin/mq/outbox/retry` — Outbox 状态 + 队列深度
- `POST /api/admin/mq/dlq/replay` — 重放 DLQ 消息

## 5. 消息身份链 Header Schema

| Header | 含义 | 可变 |
|---|---|---|
| `x-event-id` | 事件唯一标识 | 否 |
| `x-idempotency-key` | 幂等键（文档版本级 `documentId:contentHash`） | 否 |
| `x-trace-id` | 链路追踪标识 | 否 |
| `x-task-type` | 任务类型（knowledge.ingest / agent.run） | 否 |
| `x-session-id` | 会话 ID（agent.run 携带） | 否 |
| `x-original-exchange` | 原始交换机 | 否 |
| `x-original-routing-key` | 原始路由键 | 否 |
| `x-first-published-at` | 首次发布时间 | 否 |
| `x-retry-count` | 重试次数 | 是（递增） |

## 6. 已知限制与后续规划

- **无 MQ Micrometer 指标**：当前仅有 Admin REST 端点提供状态查询，未接入 Prometheus。
- **`chat.agent.dispatch` 尚未正式切换**：当前通过 `chatagent.mq.dispatchers.agent-run-enabled` 控制灰度。
- **前端补偿机制待完善**：分布式 SSE 场景下的前端 ID 锚定和状态持久化尚未落地。
