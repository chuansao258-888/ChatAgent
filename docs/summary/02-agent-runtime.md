# Agent 运行时模块 (com.yulong.chatagent.agent)

## 模块概述

Agent 运行时是整个 ChatAgent 系统的**核心执行引擎**，实现了 AI Agent 的 ReAct（Reasoning + Acting）循环。当用户发送消息后，经过意图路由和编排层，最终由 Agent 运行时负责调用 LLM 进行推理、执行工具调用、组装上下文，并产出最终回答。

**核心代码路径：** `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/`

---

## 1. 总体架构

```
┌──────────────────────────────────────────────────────────┐
│                  ChatAgentFactory                        │
│  (AgentRuntimeContextLoader + LLMService + MessageBridge)│
└─────────────────────┬────────────────────────────────────┘
                      │ 创建
                      ▼
┌──────────────────────────────────────────────────────────┐
│                     ChatAgent                            │
│                                                          │
│  ┌─────────────────────────────────────────────────────┐ │
│  │             ReAct Loop (最多20步)                    │ │
│  │                                                     │ │
│  │  ┌──────────────┐     ┌──────────────────────────┐ │ │
│  │  │ AgentThinking │     │ AgentToolExecution       │ │ │
│  │  │ Engine        │────►│ Engine                   │ │ │
│  │  │ (思考阶段)    │     │ (工具执行阶段)            │ │ │
│  │  └──────────────┘     └──────────────────────────┘ │ │
│  │         ▲                      │                   │ │
│  │         └──────────────────────┘                   │ │
│  │              (工具结果写回记忆，继续思考)              │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                          │
│  依赖：                                                  │
│  ┌───────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ AgentMessage  │  │ LLMService   │  │ ToolCallbacks│ │
│  │ Bridge        │  │ (路由LLM服务) │  │ (工具回调列表)│ │
│  └───────────────┘  └──────────────┘  └──────────────┘ │
└──────────────────────────────────────────────────────────┘
```

---

## 2. Agent 创建流程

### 2.1 ChatAgentFactory (Agent 工厂)

**文件：** `AgentFactory.java`

Spring `@Component`，负责实例化 `ChatAgent`。纯工厂模式，不保留状态。

```
create(agentId, chatSessionId, turnId, intentResolution, rewrittenInput, userId)
    │
    ├─► AgentRuntimeContextLoader.load() → 加载所有运行时上下文
    │     │
    │     ├─ AgentDefinitionLoader.load()        → Agent 配置
    │     ├─ AgentMemoryLoader.load()            → L1 短期记忆
    │     ├─ AgentSessionFileSummaryResolver()   → 附件文件摘要
    │     ├─ AgentSessionSummaryResolver()       → L2 增量摘要
    │     ├─ AgentUserProfileSummaryResolver()   → L3 用户画像
    │     └─ AgentToolCallbackFactory.create()   → 工具回调列表
    │
    └─► new ChatAgent(上下文 + LLMService + MessageBridge + userId等)
```

### 2.2 AgentRuntimeContext (运行时上下文)

**文件：** `AgentRuntimeContext.java`

一个 Java Record，包含 Agent 运行所需的全部数据：

| 字段 | 类型 | 说明 |
|------|------|------|
| `agentId` | String | Agent 唯一标识 |
| `name` | String | Agent 名称 |
| `description` | String | Agent 描述 |
| `systemPrompt` | String | 组装好的系统提示词 |
| `model` | String | 目标模型名称 |
| `maxMessages` | int | 记忆窗口大小 |
| `memory` | List\<Message\> | L1 短期记忆消息 |
| `toolCallbacks` | List\<ToolCallback\> | 可用工具回调 |
| `sessionFileSummary` | String | 会话附件摘要 |
| `sessionSummary` | String | L2 历史摘要 |
| `userProfileSummary` | String | L3 用户画像 |

---

## 3. ReAct 循环核心

### 3.1 ChatAgent (Agent 编排器)

**文件：** `ChatAgent.java` (227行)

**关键常量：**
- `MAX_STEPS = 20` — 防止无限循环
- `DEFAULT_MAX_MESSAGES = 20` — 默认记忆窗口
- `RUNTIME_MEMORY_SLACK = 4` — 记忆缓冲量

**构造器核心操作 (第60-127行)：**
1. 创建 `MessageWindowChatMemory`，大小为 `max(configuredMaxMessages, requiredCapacity)`
2. 添加系统提示词为 `SystemMessage`
3. 加载初始记忆消息
4. 创建 `ToolCallingChatOptions` 并设置 `internalToolExecutionEnabled(false)` — **关键！** 这意味着 Spring AI **不会**自动执行工具，由 Agent 手动控制
5. 构建 `toolContext` Map（包含 userId、sessionId、turnId），工具通过此 Map 获取运行时上下文

### 3.2 run() 方法 — 主循环 (第173-217行)

```java
run() {
    1. 断言状态为 IDLE
    2. 设置线程本地变量：
       - CurrentTurnKnowledgeHitHolder.reset()
       - CurrentChatSessionHolder.set(sessionId)
       - CurrentTurnHolder.set(turnId)
    3. 循环（最多 MAX_STEPS=20 次）：
       step()
         ├─ thinkingEngine.think() → ChatResponse
         ├─ 无工具调用 → state=FINISHED, 返回
         └─ 有工具调用 → toolExecutionEngine.execute()
              └─ 调用了 terminate → state=FINISHED
    4. 达到 MAX_STEPS → 强制 FINISHED
    5. 成功 → return AgentRunResult.success(durationMs, knowledgeHit)
    6. 异常 → state=ERROR, throw AgentRunException
    7. finally → 清理所有线程本地变量
}
```

### 3.3 step() 方法 — 单步迭代 (第150-168行)

```
think() → ChatResponse
    │
    ├── 无工具调用 → FINISHED（模型直接给回答）
    │
    └── 有工具调用 → execute() → 结果写回记忆 → 继续循环
```

---

## 4. 思考引擎 (AgentThinkingEngine)

**文件：** `AgentThinkingEngine.java` (276行)

### 4.1 think() 方法 (第65-119行)

**无工具可用时 (第81-89行)：**
- 跳过工具决策流，直接调用 `streamFinalAnswer()` 流式输出最终答案
- 构建独立的 "最终答案模块" Prompt，不含工具回调
- 通过 `messageBridge.streamFinalResponse()` 阻塞等待流式完成

**有工具可用时 (第92-118行)：**
- 调用 `messageBridge.streamDecisionResponse()` — **关键设计：**
  - 模型输出同时流式展示给用户并缓冲
  - 如果模型产出工具调用 → 回滚已流式展示的内容，执行工具
  - 如果模型产出纯文本回答 → 直接作为最终答案，**不需要第二次模型调用**
  - 这就是 **"单次路由流"** 设计

### 4.2 消息清洗 (sanitizePromptMessages, 第160-248行)

防止 Spring AI 拒绝格式错误的消息历史：
- 跳过孤立的 `ToolResponseMessage`（前面没有对应 assistant tool call）
- 使用 `collectToolSequence()` 验证 assistant 消息的每个工具调用都有对应的工具响应
- 不完整的序列被整组跳过

---

## 5. 工具执行引擎 (AgentToolExecutionEngine)

**文件：** `AgentToolExecutionEngine.java` (104行)

### 5.1 execute() 方法 (第53-92行)

```
1. ChatResponse 无工具调用 → return false（提前退出）

2. 构建 Prompt → ToolCallingManager.executeToolCalls()
   └── 使用 StaticToolCallbackResolver 查找匹配的工具回调

3. 工具执行结果写回记忆：
   - 清空当前记忆
   - 从工具执行结果中重新加载完整对话历史

4. 持久化工具响应：
   - 提取 ToolResponseMessage
   - 逐个工具响应调用 messageBridge.persistAndPublish()

5. 检查 terminate 工具：
   - 如果调用了 terminate → return true（终止循环）
```

---

## 6. 三层记忆体系

```
┌─────────────────────────────────────────────────────────┐
│                    L3 用户画像                           │
│  AgentUserProfileSummaryResolver                        │
│  来源：UserProfile 表，跨会话持久化                       │
│  用途：长期偏好、用户特征                                  │
├─────────────────────────────────────────────────────────┤
│                    L2 增量摘要                           │
│  AgentSessionSummaryResolver                            │
│  来源：IncrementalSummarizer 异步生成，Redis 分布式锁保护  │
│  用途：超出 L1 窗口的历史对话压缩摘要                      │
├─────────────────────────────────────────────────────────┤
│                    L1 短期记忆                           │
│  AgentMemoryLoader                                      │
│  来源：最近 N 轮完整对话消息                               │
│  默认：token budget 80% 安全上限                          │
│  用途：Agent 可直接访问的近期对话                          │
└─────────────────────────────────────────────────────────┘
```

### 6.1 AgentMemoryLoader (L1 记忆加载)

**文件：** `AgentMemoryLoader.java` (232行)

- **Token 预算：** `agentConfig.getChatOptions().getTokenBudget()` × 80% 安全系数
- **轮次滑动窗口：** 按 turnId 分组消息，从最近的轮次开始向前加载
- **原子性保证：** 完整轮次要么全部加载要么不加载，不在轮次中间截断
- **Token 估算：** 中文字符算2 token，其他算1 token

### 6.2 系统提示词组装 (DefaultAgentRuntimeContextLoader)

**文件：** `DefaultAgentRuntimeContextLoader.java` (295行)

`buildSystemPrompt()` (第110-170行) 构建分层的系统提示词：

```
1. 基础系统提示词（或默认模板）
2. [Historical Context Summary] — L2 增量摘要
3. [Intent Routing Context] — 意图路由结果：
   - 意图类型、路径、范围知识库、缩窄的工具列表、搜索提示
4. [Session Context] — 会话上下文：
   - 附件文件、用户画像、最新轮指导
5. [MCP Tool Safety] — MCP 工具安全提示（如有 MCP 工具）
6. [Tool Strategy] — 工具使用策略指导
```

**先验回答检测 (第186-255行)：** 启发式检测用户是否在追问之前的回答（如"你是怎么知道的？"），如果是，指导模型解释推理过程而非重新调用工具。

---

## 7. 工具体系

### 7.1 工具类型

| 类型 | 说明 |
|------|------|
| `FIXED` | 始终附加，不可关闭 |
| `OPTIONAL` | 需要显式启用 |

### 7.2 内置工具

| 工具 | 类名 | 类型 | 说明 |
|------|------|------|------|
| 会话文件搜索 | `SessionFileTools` | FIXED | 通过 RAG 检索会话附件和知识库 |
| 数据库查询 | `DataBaseTools` | OPTIONAL | 只读 SQL 查询，拒绝非 SELECT |
| 邮件发送 | `EmailTools` | OPTIONAL | 发送邮件 |
| 文件系统 | `FileSystemTools` | OPTIONAL | 读写文件（含目录遍历防护） |
| 直接回答 | `DirectAnswerTool` | FIXED | 已禁用 |
| 终止 | `TerminateTool` | FIXED | 已禁用（模型会过早终止） |

### 7.3 SessionFileTools — 知识检索核心工具

**文件：** `tools/SessionFileTools.java` (64行)

`knowledgeQuery()` 执行流程：
```
1. 从 ThreadLocal 获取 chatSessionId 和 turnId
2. 获取当前 IntentResolution（可能为 null）
3. ragService.similaritySearchBySession() → RAG 检索
4. CurrentTurnKnowledgeHitHolder.recordRetrievalResult() → 记录是否有命中
5. retrievalHitFormatter.formatWithCitations() → 格式化带引用的检索结果
6. CurrentTurnCitationHolder.put() → 存储引用元数据
7. 返回格式化的 Prompt 文本
```

### 7.4 工具注册与动态解析

**AgentToolCallbackFactory** (runtime 包) 根据以下因素决定可用工具：
1. Agent 配置的 `allowedTools` 列表
2. 意图路由结果（可能缩窄工具范围）
3. MCP Rollout 策略（控制 `mcp_` 前缀的工具）
4. 如果工具实现 `DirectToolCallbackSource` 接口，直接使用其回调

---

## 8. 消息桥接 (AgentMessageBridge)

**文件：** `AgentMessageBridgeImpl.java` (485行)

连接 Agent 输出到两个通道：**数据库持久化** + **SSE 实时推送**。

### 8.1 SSE 事件类型

| 事件 | 用途 |
|------|------|
| `AI_GENERATED_CONTENT` | AI 生成内容快照 |
| `AI_THINKING` | 推理/思考内容 |
| `AI_DONE` | 轮次完成信号 |
| `AI_ERROR` | 错误通知 |
| `TURN_ROLLBACK` | 回滚已流式展示的内容 |

### 8.2 streamDecisionResponse() — 单次路由流 (第254-357行)

```
1. 创建空的持久化消息（获取 ID）
2. llmService.streamDecisionWithRouting() → 同时流式+缓冲
3. 检测到工具调用：
   → 删除已持久化的临时消息
   → 发送 TURN_ROLLBACK SSE 事件
   → 返回缓冲的响应
4. 无工具调用：
   → 更新持久化消息为最终内容
   → 发送最终内容快照 + AI_DONE
   → 不需要第二次模型调用！
```

### 8.3 streamFinalResponse() — 最终答案流 (第101-251行)

无工具场景下的阻塞式流式输出：
- `onContent()` → 追加到 StringBuilder，推送 AI_GENERATED_CONTENT
- `onThinking()` → 推送 AI_THINKING
- `onComplete()` → 更新 DB，推送 AI_DONE
- `onError()` → 追加错误后缀，更新 DB，推送 AI_ERROR + AI_DONE
- 使用 `CountDownLatch` + 可配置超时等待流式完成

---

## 9. 线程本地上下文持有者

所有工具回调通过 ThreadLocal 获取运行时上下文，避免通过 LLM Prompt 传参：

| 持有者 | 内容 | 设置时机 |
|--------|------|---------|
| `CurrentChatSessionHolder` | sessionId | ChatAgent.run() 开始 |
| `CurrentTurnHolder` | turnId | ChatAgent.run() 开始 |
| `CurrentIntentResolutionHolder` | IntentResolution | ChatEventProcessor.process() |
| `CurrentTurnKnowledgeHitHolder` | 知识检索是否命中 | SessionFileTools 执行时 |
| `CurrentTurnCitationHolder` | 引用元数据 | SessionFileTools 执行时 |

**清理机制：** 所有 ThreadLocal 在 `ChatAgent.run()` 的 finally 块中清理，防止跨会话泄漏。

---

## 10. AgentRunResult (运行结果)

**文件：** `AgentRunResult.java` (59行)

| 字段 | 说明 |
|------|------|
| `status` | SUCCESS / ERROR |
| `durationMs` | 运行耗时 |
| `errorType` | LLM_TIMEOUT / UPSTREAM_ERROR / TOOL_EXECUTION_ERROR / RETRIEVAL_FAIL / UNEXPECTED_ERROR |
| `knowledgeHit` | 是否命中知识检索 |

错误分类通过 `classifyError()` 检查异常链自动归类，用于 Dashboard 指标统计。

---

## 11. 技术亮点总结

### ReAct 循环
- 标准的 Reasoning + Acting 循环，最多 20 步防止无限循环
- 手动控制工具执行（`internalToolExecutionEnabled=false`），不依赖框架自动执行

### 单次路由流 (Single Routed Stream)
- 模型输出同时流式展示+缓冲，有工具调用则回滚，无工具调用则直接成为最终答案
- 避免了"先流式输出、再调第二次模型生成最终答案"的性能浪费

### 三层记忆体系
- L1 短期记忆（Token 预算 + 轮次滑动窗口）
- L2 增量摘要（事件驱动异步生成）
- L3 用户画像（跨会话持久化）

### 工具系统设计
- FIXED + OPTIONAL 分类
- MCP 工具动态注册
- 意图路由可缩窄工具范围
- ThreadLocal 传递运行时上下文

### 安全防护
- ThreadLocal finally 清理防止跨会话数据泄漏
- 消息历史清洗防止格式错误
- 文件系统工具的目录遍历防护
- SQL 工具只允许 SELECT
