# MQ 异步架构重构方案 (RabbitMQ 企业级增强版 V4)

## 1. 重构目标
构建具备“确定性投递、状态感知、非阻塞容错”特性的分布式异步任务体系。

- **Transactional Outbox**：通过本地事务表保证 DB 与 MQ 发送的原子性。
- **三态幂等锁**：基于 Redis 的 RUNNING (Watchdog 续期) / COMPLETED / FAILED 状态机。
- **专用重试拓扑**：拆分独立重试队列，显式指定 `x-dead-letter-routing-key` 闭合回流路径。
- **全链路可观测**：指标覆盖处理耗时、重试频率、Outbox 积压。

### 1.1 首期迁移范围与顺序
为控制迁移风险，首期 **只迁移 ingest 类后台任务**，不直接迁移 `chat.agent.dispatch` 主聊天链路。

- **Phase 1 / Stage 4A**：优先迁移 `knowledge.ingest.task`，验证 RabbitMQ 拓扑、Outbox、Retry、DLQ、Replay 全链路。
- **Phase 2 / Stage 4B**：沉淀任务级幂等抽象、消费者重试基类、回放与运维接口。
- **Phase 3 / Stage 4C**：最后灰度迁移 `chat.agent.dispatch`，并按任务类型独立开关，不采用“一刀切”切换。

这样做的原因是：
- `ingest.task` 属于纯后台 fire-and-forget 任务，失败最多导致文档状态卡住，不直接破坏用户对话体验。
- `chat.agent.dispatch` 当前仍承载 SSE 推送、fallback assistant message、turn completion、intent 上下文、citation 上下文等链路，迁移成本和回归面显著更大。

### 1.2 消息身份链（Header Schema）
所有进入 MQ 的消息必须携带稳定、可透传、可回放的身份链。以下字段作为 **固定消息头契约**：

| Header | 含义 | 是否可变 | 说明 |
| :--- | :--- | :--- | :--- |
| `x-event-id` | 事件唯一标识 | 否 | 贯穿生产、消费、重试、死信、重放的主键 |
| `x-idempotency-key` | 幂等键 | 否 | 锁与去重判断依据；`knowledge.ingest` 不应只使用 `documentId`，而应使用**文档版本键**（如 `documentId:contentHash`）以支持 replace 后重新入库 |
| `x-trace-id` | 链路追踪标识 | 否 | 日志与审计关联 |
| `x-task-type` | 任务类型 | 否 | 如 `knowledge.ingest`、`agent.run` |
| `x-original-exchange` | 原始交换机 | 否 | DLQ replay 时用于恢复原始投递路径 |
| `x-original-routing-key` | 原始路由键 | 否 | DLQ replay / retry 回流必需 |
| `x-first-published-at` | 首次发布时间 | 否 | 便于排查长期堆积与慢任务 |
| `x-retry-count` | 当前重试次数 | 是 | 每次进入 retry 队列时递增 |

约束：
- `x-event-id` 与 `x-idempotency-key` 不可在重试或重放过程中被重写。
- retry / DLQ replay 必须保留全部不可变 header，只允许递增 `x-retry-count`。
- 运维接口与审计日志必须支持按 `x-event-id` 和 `x-idempotency-key` 查询。

---

## 2. 基础设施：RabbitMQ 拓扑 (Topology)

### 2.1 队列设计
| 队列名称 | 交换机 (Exchange) | 路由键 (Routing Key) | DLX (死信交换机) | DLK (死信路由键) | 说明 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `chat.agent.dispatch` | `chat.direct` | `agent.run` | `dlx.direct` | `dead.letter` | 主 Agent 任务 |
| `knowledge.ingest.task`| `chat.direct` | `ingest.task` | `dlx.direct` | `dead.letter` | 文档入库任务 |
| `retry.agent.10s` | `retry.direct` | `retry.agent` | `chat.direct` | `agent.run` | Agent 重试回流 |
| `retry.ingest.30s` | `retry.direct` | `retry.ingest`| `chat.direct` | `ingest.task`| 入库重试回流 |
| `chat.dlq` | `dlx.direct` | `dead.letter` | - | - | 最终死信队列 |

**核心参数**：
- `retry.agent.10s`：`x-message-ttl: 10000`, `x-dead-letter-routing-key: agent.run`。
- `retry.ingest.30s`：`x-message-ttl: 30000`, `x-dead-letter-routing-key: ingest.task`。
- **并发控制**：Agent (`prefetch: 5`, `concurrency: 5`)；Ingest (`prefetch: 1`, `concurrency: 2`)。

**落地顺序约束**：
- 首批上线只启用 `knowledge.ingest.task` 及其对应 retry / DLQ 回流。
- `chat.agent.dispatch` 的 exchange / queue 可以在拓扑层预声明，但业务流量切换必须放到 Stage 4C 之后。

---

## 3. 关键组件实现

### 3.1 幂等锁与 Watchdog
1. **状态机**：
   - `RUNNING`：NX 抢占成功，开启 Watchdog。
   - `COMPLETED`：任务成功，设置 24h TTL。
   - `FAILED`：不可重试异常，设置 **1h TTL**（允许后续手动 DLQ 重放时自然失效）。
2. **Watchdog**：使用 `ScheduledThreadPoolExecutor(2)` 复用线程，每 20s 对 `RUNNING` 锁执行续期。

**设计约束**：
- MQ 幂等锁与现有摘要/会话场景中的 Redis mutex 不是同一抽象，不直接复用现有 `RedisLockManager`。
- 这里的锁语义是 **at-least-once 消费去重状态机**，不是短生命周期的互斥锁。
- 幂等键建议按任务类型约定：
  - `knowledge.ingest`：优先使用文档版本级键，例如 `documentId:contentHash`
  - `agent.run`：优先使用 `turnId`
  - 若任务不存在天然业务主键，必须在生产端显式生成 `x-idempotency-key`

**知识库文档 replace 语义**：
- `replace document` 的正确语义不是“同一个文档实体重复发同一个 ingest 事件”，而是“同一 `documentId` 下产生了一个**新的文档版本**，需要重新执行一轮 ingest / index”。
- 因此，替换文档时：
  - 文档记录重置为 `PENDING`
  - 旧 chunk / 旧向量需要清理
  - 新的 `knowledge.ingest` 事件必须使用**版本级**幂等键（推荐 `documentId:contentHash`）
- 如果继续把幂等键固定成 `documentId`，Outbox/锁层会把 replace 误判成重复事件，导致新文件不会重新进入 RAG 流程。

### 3.2 Transactional Outbox
1. **重试上限**：Polling Publisher 尝试发送超过 **5 次**后，将记录标记为 `FAILED` 并停止自动轮询。
2. **清理逻辑**：定时任务清理 7 天前的 `SENT` 记录。
3. **抢占语义**：Outbox Poller 必须支持多实例安全抢占，避免同一条记录被多个实例重复发送。
   - **优先方案**：使用 PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`
   - **备选方案**：增加 `claimed_at` + `claimed_by` + `version`，只有成功 claim 的实例才允许发送
4. **表结构建议**：
   - `status`：`PENDING / CLAIMED / SENT / FAILED`
   - `next_retry_at`
   - `last_error`
   - `claimed_at`
   - `claimed_by`
   - `retry_count`
   - `version`
3. **配置开启**：
   ```yaml
   spring.rabbitmq:
      publisher-confirm-type: correlated
      publisher-returns: true
   ```

### 3.3 消费者重试逻辑 (代码层)
```java
try {
    process(payload);
    channel.basicAck(tag, false);
} catch (RetryableException e) {
    if (getRetryCount(headers) < 3) {
        try {
            // 显式投递至对应重试队列，必须保留并更新消息头
            Message retryMessage = MessageBuilder.fromMessage(originalMessage)
                    .setHeader("x-retry-count", getRetryCount(headers) + 1)
                    .setHeaderIfAbsent("x-event-id", eventId)
                    .setHeaderIfAbsent("x-idempotency-key", idempotencyKey)
                    .setHeaderIfAbsent("x-trace-id", traceId)
                    .setHeaderIfAbsent("x-original-exchange", originalExchange)
                    .setHeaderIfAbsent("x-original-routing-key", originalRoutingKey)
                    .setHeaderIfAbsent("x-first-published-at", firstPublishedAt)
                    .build();
            rabbitTemplate.send("retry.direct", retryRoutingKey, retryMessage);
            channel.basicAck(tag, false);
        } catch (Exception mqEx) {
            // 重要：若重试消息发送失败（如 MQ 断连），必须 requeue 原消息
            log.error("Failed to move message to retry queue, requeueing original.", mqEx);
            channel.basicNack(tag, false, true); 
        }
    } else {
        channel.basicReject(tag, false); // 进 DLQ
    }
} catch (Exception e) {
    // 格式错误或不可恢复异常，直接进 DLQ 防止死循环
    channel.basicReject(tag, false); 
}
```

---

## 4. 可观测性指标 (Micrometer)
- `chatagent.mq.consume.duration`：任务从入队到 Ack 的全耗时。
- `chatagent.mq.outbox.size`：`t_mq_outbox` 表中 PENDING 记录数。
- `chatagent.mq.retry.count`：重试发生次数 (tag: queue)。
- `chatagent.mq.outbox.publish.outcome`：Outbox 发布结果 (tag: exchange, routingKey, outcome=`success|confirm_timeout|returned|failed`)。
- `chatagent.mq.dlq.size`：DLQ 当前积压数量 (tag: queue)。
- `chatagent.mq.lock.acquire.outcome`：幂等锁结果 (tag: taskType, outcome=`acquired|duplicate|expired|failed`)。

---

## 5. 开发任务清单

### Stage 4A.0：MQ 基建接入
- [ ] 引入 `spring-boot-starter-amqp`，补齐 RabbitMQ 连接配置与本地 dev profile。
- [ ] 声明 Exchange / Queue / Binding 拓扑，重点校验 Retry 队列的 TTL 与 DLK 回流参数。
- [ ] 固化消息头 schema（`x-event-id`、`x-idempotency-key`、`x-trace-id`、`x-original-routing-key` 等）。

### Stage 4A：基线与事务保障（仅 ingest 任务）
- [ ] 落地 `t_mq_outbox` 表（含 `status / next_retry_at / last_error / claimed_at / claimed_by / retry_count / version`）。
- [ ] 实现 Outbox Poller，支持多实例安全抢占、5 次发送上限判定与 Confirm 机制。
- [ ] 首批迁移 `knowledge.ingest.task`，验证 Outbox -> Retry -> DLQ -> Replay 全链路。

### Stage 4B：任务级幂等与重试抽象
- [ ] 实现任务级 `DistributedLockManager`（含 FAILED 状态 1h TTL）。
- [ ] 实现 `LockWatchdog`（基于线程池复用）。
- [ ] 实现消费者基类，封装重试 header 继承、分流重试逻辑与统一日志。
- [ ] 补齐 `Admin APIs`：`outbox/retry` 与 `dlq/replay`。

### Stage 4C：`chat.agent.dispatch` 灰度迁移
- [ ] 通过按任务类型独立开关的 `EventDispatcher` / `TaskDispatcher` 策略实现灰度切换。
- [ ] 最后迁移 `chat.agent.dispatch`，单独验证 SSE、fallback assistant message、turn completion 与上下文传递。
- [ ] 全链路集成测试：验证重试 3 次后准确进入 `chat.dlq`，并验证 DLQ replay 不会破坏幂等性。
