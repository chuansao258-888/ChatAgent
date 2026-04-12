# 会话编排模块 (com.yulong.chatagent.conversation)

## 模块概述

会话编排模块是用户消息处理的**入口和调度中心**。它负责管理聊天会话和消息的生命周期，协调意图路由、Agent 调度、SSE 流式推送和增量摘要等子系统。每一条用户消息都经过此模块进入系统处理流水线。

**核心代码路径：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/`

---

## 1. 总体架构

```
用户消息 POST /api/chat-messages
         │
         ▼
┌─────────────────────────────────────────────┐
│        ChatMessageController                │
│   SessionConcurrencyGuard.acquire()         │ ← Redis 分布式锁
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│     ConversationOrchestratorService         │
│                                             │
│  Phase 1: 验证请求                           │
│  Phase 2: 组装上下文                         │
│    └─ ChatSessionFacadeService.getChatSession│
│    └─ ChatMessageFacadeService.createMessage │
│    └─ 加载最近12条消息                        │
│  Phase 3: 验证一致性                         │
│  Phase 4: 调度                               │
│    └─ ConversationTurnPreparationService     │ ← 意图路由
│    ├─ 直接回复路径                            │
│    └─ Agent 调度路径                          │
└──────────────────┬──────────────────────────┘
                   │
          ┌────────┴────────┐
          ▼                 ▼
   ┌─────────────┐  ┌──────────────────┐
   │ 直接回复    │  │ ChatEvent        │
   │ (SYSTEM/    │  │ Dispatcher       │
   │  CLARIFY)   │  │                  │
   └─────────────┘  │ ┌─Local─────┐   │
                    │ │Spring Event│   │
                    │ └────────────┘   │
                    │ ┌─MQ────────┐   │
                    │ │Outbox+MQ  │   │
                    │ └────────────┘   │
                    └────────┬─────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ ChatEvent        │
                    │ Processor        │
                    │ → ChatAgent.run()│
                    └────────┬─────────┘
                             │
                    ┌────────┴─────────┐
                    │ Turn完成事件      │
                    │ → 增量摘要触发    │
                    └──────────────────┘
```

---

## 2. ConversationOrchestratorService (编排服务)

**文件：** `application/ConversationOrchestratorService.java`

### 2.1 handleUserTurn() — 核心方法 (@Transactional)

**Phase 1 — 验证 (第83-95行)**
- 断言请求非空、sessionId/content 非空、role 必须为 USER
- turnId 如提供，需匹配标准小写 UUID 格式

**Phase 2 — 上下文组装 (第97-118行)**
```
1. normalizeRequest() → 修剪字段，自动生成 turnId
2. ChatSessionFacadeService.getChatSession() → 加载会话
3. 解析 agentId（从会话）
4. ChatMessageFacadeService.createChatMessage() → 持久化用户消息
5. getChatMessagesBySessionIdRecently() → 加载最近12条消息
6. 打包为 ConversationTurnContext (不可变 Record)
```

**Phase 3 — 一致性验证 (第120-126行)**
- 重新加载历史，确认刚持久化的用户消息可见 — 防御性一致性检查

**Phase 4 — 调度 (第128-161行)**
```
ConversationTurnPreparationService.prepare() → TurnPreparationResult
    │
    ├── directReply 不为空（SYSTEM/CLARIFY/无意图树）：
    │   → 直接持久化助手消息
    │   → 推送 AI_GENERATED_CONTENT + AI_DONE
    │   → 发布轮次完成事件
    │
    └── 需要Agent处理：
        → 构建 ChatEvent
        → ChatEventDispatcher.dispatch()
```

---

## 3. 事件分发系统

### 3.1 事件架构

```
ChatEvent (领域事件)
    │ agentId, sessionId, turnId, chatMessageId
    │ userInput, recentHistorySize
    │ intentResolution, rewrittenInput, userId
    │
    ▼
ChatEventDispatcher (接口)
    │
    ├── SwitchingChatEventDispatcher (@Primary)
    │   │ 根据 feature flag 决定分发策略：
    │   │
    │   ├── LocalChatEventDispatcher (默认/回退)
    │   │   → ApplicationEventPublisher.publishEvent()
    │   │   → ChatEventListener (@Async) → ChatEventProcessor
    │   │
    │   └── MqChatEventDispatcher (MQ启用时)
    │       → OutboxEventPublisher (事务性发件箱)
    │       → RabbitMQ → AgentRunTaskListener → ChatEventProcessor
    │
    ▼
ChatEventProcessor (共享处理器)
```

### 3.2 SwitchingChatEventDispatcher — 分发策略切换

**文件：** `event/SwitchingChatEventDispatcher.java`

检查 `ChatAgentMqProperties.isEnabled()` 和 `dispatchers.isAgentRunEnabled()`：
- MQ 启用 → 委托给 `MqChatEventDispatcher`（事务性发件箱 + RabbitMQ）
- MQ 未启用 → 委托给 `LocalChatEventDispatcher`（Spring ApplicationEvent）

### 3.3 ChatEventProcessor — 共享处理核心

**文件：** `event/ChatEventProcessor.java`

无论走本地还是 MQ 路径，最终都到达这里：

```
process(ChatEvent):
    1. 检查 ChatModelAvailability.hasConfiguredProvider()
       → 无模型配置则发回退响应 + 记录指标
    2. CurrentIntentResolutionHolder.set(intentResolution)
    3. ChatAgentFactory.create() → 创建 Agent
    4. chatAgent.run() → 执行 ReAct 循环
    5. recordMetricQuietly() → 记录轮次指标
    6. publishTurnCompletion() → 触发增量摘要
    7. finally → CurrentIntentResolutionHolder.clear()
```

**rollbackTurn() (第92-103行)：** 删除指定轮次的助手和工具消息 + 发送 TURN_ROLLBACK SSE 事件，用于 MQ 重试前回滚。

**publishFailure() (第108-121行)：** 回滚轮次 + 持久化错误助手消息 + 发送 AI_GENERATED_CONTENT + AI_ERROR + AI_DONE。

---

## 4. SSE 流式推送

### 4.1 SseController

**文件：** `controller/SseController.java`

单端点 `GET /api/sse/connect/{chatSessionId}`：
- `ResourceAccessGuard.assertCanReadSession()` 鉴权
- `SseService.connect()` 返回 `SseEmitter`

### 4.2 SSE 消息类型

**文件：** `model/SseMessage.java`

| 类型 | Payload | 说明 |
|------|---------|------|
| `AI_GENERATED_CONTENT` | ChatMessageVO | AI 生成内容快照 |
| `AI_PLANNING` | statusText | 规划状态 |
| `AI_THINKING` | statusText | 思考/推理内容 |
| `AI_EXECUTING` | statusText | 工具执行状态 |
| `AI_ERROR` | statusText | 错误通知 |
| `AI_DONE` | done=true | 轮次完成信号 |
| `TURN_ROLLBACK` | turnId | 回滚指令 |

---

## 5. 会话并发保护

### 5.1 SessionConcurrencyGuard

**文件：** `application/SessionConcurrencyGuard.java`

基于 Redis 的分布式互斥锁，防止同一会话的并发请求重叠：

```java
acquire(sessionId):
    1. setIfAbsent(key=lock:{sessionId}, value=UUID token, TTL=120s)
    2. 成功 → 返回 SessionLock (AutoCloseable)
    3. 失败 → 抛 SessionConflictException (HTTP 409)
```

**释放机制 (Lua 脚本原子操作)：**
```lua
-- 只有 token 匹配才删除，防止误删其他请求的锁
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
```

**Fail-Open 行为：** Redis 不可用时，如果 `failOpen=true`（默认），静默返回空锁，不阻塞业务。

### 5.2 使用方式

```java
// ChatMessageController.createChatMessage()
try (SessionLock ignored = sessionConcurrencyGuard.acquire(request.getSessionId())) {
    return ApiResponse.success(conversationOrchestratorService.handleUserTurn(request));
}
// try-with-resources 自动释放锁
```

---

## 6. 增量摘要系统

### 6.1 整体流程

```
轮次完成
    │
    ▼
ConversationTurnCompletionPublisher.publishCompletedTurn()
    │ 查询 max seq_no，发布 ConversationTurnCompletedEvent
    ▼
AsyncSummaryListener (异步监听器)
    │
    ├── countTurns() <= l1WindowTurns (默认8) → 跳过
    ├── RedisLockManager.tryLock() → 获取摘要锁
    ├── SummaryWatermarkService.isAnchorCovered() → 是否已摘要过
    └── IncrementalSummarizer.summarize()
```

### 6.2 IncrementalSummarizer

**文件：** `summary/IncrementalSummarizer.java`

```
1. resolvePendingRange() → 获取未摘要的 seq_no 范围
2. 加载现有摘要
3. extractPendingTurns() → 提取待摘要的轮次
4. 提取"锚定实体"（日期、金额、订单号）→ 正则匹配
5. 合并新旧锚定实体
6. LLM 生成新摘要（含现有摘要 + 新轮次）
   → LLM 失败则回退到确定性拼接摘要
7. 长度上限检查（chatagent.memory.summary-max-chars，默认500）
8. 警告：锚定实体是否丢失
9. chatSessionSummaryRepository.saveOrUpdate()
```

### 6.3 SummaryWatermarkService (水位线追踪)

**文件：** `summary/SummaryWatermarkService.java`

追踪哪些 seq_no 范围已被摘要：
- `lastSummarizedSeqNo` — 已摘要到的位置
- `anchorSeqNo` — 当前锚点位置
- `hasPendingMessages()` → anchor > last

### 6.4 TurnBasedContextExtractor (轮次提取器)

**文件：** `summary/TurnBasedContextExtractor.java`

按 turnId 分组消息为 `AtomicConversationTurn`：
- 过滤掉工具调用消息（assistant 的结论不包含工具调用细节）
- 每个轮次包含：turnId、startSeqNo、endSeqNo、userMessages、assistantConclusion

### 6.5 RedisLockManager (摘要专用锁)

5分钟 TTL 的 Redis 互斥锁，使用相同的 Lua compare-and-delete 模式。防止同一会话的并发摘要操作。

### 6.6 乐观并发控制

**MyBatisChatSessionSummaryRepository** 使用 `updateBySessionIdAndVersion()` 实现乐观锁：
- 更新时 version+1
- 版本不匹配则更新失败

---

## 7. 会话与消息生命周期

### 7.1 ChatSessionFacadeService

| 操作 | 说明 |
|------|------|
| `createChatSession` | 解析 agentId，设置时间戳，持久化 |
| `deleteChatSession` | 事务级联：删除会话 → 删除摘要 → 清理附件（Milvus + 文件存储） |
| `getChatSession` | ResourceAccessGuard 鉴权 |
| `updateChatSession` | 更新标题 |

### 7.2 ChatMessageFacadeService

| 操作 | 说明 |
|------|------|
| `createChatMessage` | 设置时间戳，持久化 |
| `appendChatMessage` | 追加内容（流式场景） |
| `deleteAssistantAndToolMessagesForTurn` | 批量删除指定轮次的助手+工具消息（MQ重试回滚用） |
| `requireOwnedSessionIfAuthenticated` | 有 LoginUser 则鉴权，系统/内部调用则放行 |

---

## 8. 指标记录

### ChatTurnMetricRecorder

**文件：** `metrics/ChatTurnMetricRecorder.java`

每次轮次完成后记录到 `t_chat_turn_metric` 表：

| 字段 | 来源 |
|------|------|
| sessionId | 会话 |
| userId | 从会话查找 |
| turnId | Agent 运行时 |
| agentId | Agent 配置 |
| status | AgentRunResult.Status |
| errorType | AgentRunResult.errorType |
| durationMs | AgentRunResult.durationMs |
| knowledgeHit | AgentRunResult.knowledgeHit |

异常时静默记录，不影响业务流程。

---

## 9. 技术亮点总结

### 高并发
- **SessionConcurrencyGuard：** Redis 分布式锁防止同一会话并发请求重叠
- **Fail-Open 设计：** Redis 故障不阻塞业务
- **Lua 脚本原子释放：** 防止误删其他请求的锁

### 高可用
- **双分发策略：** Local / MQ 可切换，`SwitchingChatEventDispatcher` 运行时选择
- **Fail-Open 行为：** 关键依赖（Redis）故障时优雅降级

### 事件驱动架构
- **ChatEvent 领域事件：** 解耦编排层和执行层
- **ConversationTurnCompletedEvent：** 触发增量摘要，不阻塞主流程
- **异步摘要：** 专用线程池 + Redis 锁 + 水位线追踪

### 增量摘要
- **事件驱动触发：** 每轮完成后异步检查
- **锚定实体：** 正则提取关键信息，防止摘要丢失重要事实
- **LLM + 确定性回退：** LLM 失败时回退到拼接摘要
- **乐观并发控制：** version 字段防止并发更新冲突
