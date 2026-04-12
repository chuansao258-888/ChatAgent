# LLM 路由模块 (chatagent-infra)

## 模块概述

LLM 路由模块位于 `chatagent-infra`，是整个系统的**基础设施层**，负责管理多个大语言模型（LLM）供应商的接入、路由选择、健康检查、熔断保护和流式响应处理。这是项目中最具技术含量的模块之一，实现了类似 RAgent 风格的**首包探测路由**机制。

**核心代码路径：** `chatagent/infra/src/main/java/com/yulong/chatagent/chat/` 及 `chat/routing/`

---

## 1. 总体架构

```
┌─────────────────────────────────────────────────────────┐
│                    调用方 (Agent/Conversation)            │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────────────────┐
│                  RoutingLLMService (核心路由服务)           │
│                                                           │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ ModelSelector │  │ModelHealth   │  │FirstPacket     │  │
│  │ (候选者筛选)  │  │Store(熔断器) │  │Awaiter(首包)   │  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬────────┘  │
│         │                 │                   │           │
│         ▼                 ▼                   ▼           │
│  ┌──────────────────────────────────────────────────┐    │
│  │          ChatClientRegistry (模型注册表)           │    │
│  │   deepseek-chat | deepseek-reasoner              │    │
│  │   glm-4.6 | glm-5.1                              │    │
│  └──────────────────────────────────────────────────┘    │
│                        │                                  │
│         ┌──────────────┼──────────────┐                  │
│         ▼              ▼              ▼                   │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐           │
│  │ProviderDir │ │ReactiveStr │ │RoutingPrompt│           │
│  │ectStream   │ │eamAdapter  │ │Factory     │           │
│  │(原始SSE)   │ │(Spring AI) │ │(Prompt构建) │           │
│  └────────────┘ └────────────┘ └────────────┘           │
└───────────────────────────────────────────────────────────┘
```

---

## 2. 多模型注册与路由

### 2.1 ChatClientRegistry (模型注册表)

**文件：** `chat/ChatClientRegistry.java`

Spring 自动收集所有命名的 `ChatClient` Bean 到一个 Map 中：

```java
@Component
public class ChatClientRegistry {
    private final Map<String, ChatClient> clients; // Spring 自动注入

    public ChatClient getRequired(String key) { ... }   // 获取指定模型，不存在则抛异常
    public boolean supports(String key) { ... }          // 检查模型是否可用
    public Set<String> availableModels() { ... }         // 返回所有已注册模型名
}
```

### 2.2 MultiChatClientConfig (多模型配置)

**文件：** `chat/config/MultiChatClientConfig.java`

声明四个命名的 `ChatClient` Bean：

| Bean 名称 | 供应商 | 模型 | 说明 |
|-----------|--------|------|------|
| `deepseek-chat` | DeepSeek | deepseek-chat | DeepSeek 对话模型 |
| `deepseek-reasoner` | DeepSeek | deepseek-reasoner | DeepSeek 推理模型 (maxTokens=8192) |
| `glm-4.6` | 智谱AI | glm-4.6 | 智谱对话模型 |
| `glm-5.1` | 智谱AI | glm-5.1 | 智谱旗舰模型 (默认模型) |

**关键实现细节：** 通过反射 (`extractField`) 从 Spring AI 自动配置的模型中提取内部 API 对象（如 `DeepSeekApi`、`ZhiPuAiApi`），实现模型变体的复用而不需要重复配置 API Key。

### 2.3 ChatModelRouter (简单路由器)

**文件：** `chat/ChatModelRouter.java`

最基础的路由器，按名称解析 ChatClient。默认模型为 `glm-5.1`。

### 2.4 ChatModelProviderRegistry (供应商注册表)

**文件：** `chat/routing/ChatModelProviderRegistry.java`

使用 Spring `ObjectProvider` 实现优雅降级：仅在对应的 SDK Bean 存在时才注册供应商绑定。

| 绑定 | supportsThinking |
|------|-----------------|
| `DeepSeekBinding` | false |
| `ZhiPuAiBinding` | true |

---

## 3. 首包探测路由 (First Packet Detection)

> **这是本项目最核心的技术亮点之一，面试重点讲解。**

### 3.1 设计思想

传统方案在流式场景下，一旦选择了某个模型，即使该模型响应极慢，也只能等到超时才能切换。**首包探测**的核心思路是：

1. 并行地（串行尝试）向候选模型发起流式请求
2. 等待第一个有效数据包（"首包"）到达
3. 首包成功到达 → 提交该模型，刷出缓冲区数据给客户端
4. 首包超时/失败 → 丢弃该流，尝试下一个候选模型

### 3.2 FirstPacketAwaiter (首包等待器)

**文件：** `chat/routing/FirstPacketAwaiter.java` (44行)

核心是一个 `CountDownLatch` + 三个 `AtomicBoolean`/`AtomicReference`：

```
状态：
- hasContent (AtomicBoolean)  -- 是否收到了实际内容
- eventFired (AtomicBoolean)  -- latch 是否已触发（防止重复 countDown）
- error (AtomicReference)     -- 错误信息

信号方法：
- markContent()    -- 收到内容，设 hasContent=true，触发 latch
- markComplete()   -- 流正常结束（无内容），触发 latch
- markError(t)     -- 流异常，触发 latch

等待方法：
- await(timeout) → Result
  - SUCCESS     -- 在超时内收到了内容
  - TIMEOUT     -- 超时，没有任何事件
  - NO_CONTENT  -- 流结束但没有内容
  - ERROR       -- 流发生错误
```

### 3.3 RoutingLLMService (核心路由服务)

**文件：** `chat/routing/RoutingLLMService.java` (461行)

#### 同步路径 (`chatWithRouting`, 第53-110行)

```
1. ModelSelector.selectChatCandidates(false) → 获取候选模型列表
2. 遍历候选模型（按优先级排序）：
   a. healthStore.tryAcquire(candidateId) → 检查熔断器
   b. RoutingPromptFactory.create() → 构建供应商特定的 Prompt
   c. target.chatClient().prompt().call() → 同步调用
   d. 成功 → markSuccess，记录指标，返回
   e. 失败 → markFailure，记录指标，尝试下一个
3. 所有候选都失败 → 抛 RuntimeException
```

#### 流式路径 (`routeAndStream`, 第153-246行) -- **首包探测核心**

```
1. ModelSelector.selectChatCandidates(deepThinking) → 候选列表
2. 遍历候选模型：
   a. healthStore.tryAcquire() → 熔断检查
   b. 创建 FirstPacketAwaiter + ProbeBufferingCallback
   c. 优先使用 ProviderDirectStreamSupport.submit()（原始SSE）
      → 不支持则回退到 ReactiveStreamAdapter.submit()（Spring AI）
   d. awaiter.await(firstPacketTimeoutSeconds) → 等待首包
   e. 首包成功 → wrapper.commit()（刷出缓冲事件），markSuccess，返回
   f. 首包失败 → markFailure，dispose 流，尝试下一个
3. 全部失败 → callback.onError()
```

#### ProbeBufferingCallback (探测缓冲回调, 第262-323行)

**关键设计：** 在首包探测阶段，所有流事件被缓冲而非直接发送给客户端。只有当首包探测成功（`commit()` 被调用）后，缓冲的事件才会被刷出到下游。如果探测失败，缓冲的事件随流一起被丢弃，客户端不会收到任何不完整数据。

---

## 4. 熔断器 (ModelHealthStore)

> **高可用核心组件，面试重点讲解。**

### 4.1 三态状态机

**文件：** `chat/routing/ModelHealthStore.java` (207行)

```
              连续失败 >= 3次
  CLOSED ──────────────────────► OPEN
    ▲                              │
    │                              │ 等待 openDurationMs (5分钟)
    │ 探测成功                      │
    │                              ▼
    └─────────── HALF_OPEN ◄───────┘
                    │
                    │ 探测失败
                    ▼
                  OPEN (重新计时)
```

### 4.2 核心参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `failureThreshold` | 3 | 连续失败多少次触发熔断 |
| `openDurationMs` | 300,000 (5分钟) | 熔断开启持续时间 |
| `halfOpenFlightTimeoutMs` | 120,000 (2分钟) | 半开状态探测超时 |

### 4.3 防污染机制 -- probeGeneration

HALF_OPEN 状态只允许一个探测请求通过。为防止**过期的探测结果**污染状态：

- 每次进入 HALF_OPEN 时递增 `probeGeneration` 计数器
- `markSuccess/markFailure` 只接受匹配当前 generation 的结果
- 飞行超时保护：如果探测请求卡住超过 2 分钟，允许发起新探测

### 4.4 tryAcquire 核心逻辑 (第32-85行)

```java
switch (state) {
    case CLOSED:  → 允许调用
    case OPEN:
        if (now < openUntil) → 拒绝
        else → 转为 HALF_OPEN，递增 probeGeneration，允许
    case HALF_OPEN:
        if (有探测在飞行 && 未超时) → 拒绝
        else → 允许（作为探测请求）
}
```

---

## 5. ModelSelector (候选模型筛选)

**文件：** `chat/routing/ModelSelector.java` (106行)

筛选与排序流程：

```
1. 确定首选模型（deepThinking=true → 使用 deepThinkingModel，否则 defaultModel）
2. 应用运行时覆盖 (RoutingRuntimeOverridesStore)
3. 过滤：enabled=true + id/key 非空 + thinking 支持
4. 按 priority 升序排列（数字越小优先级越高）
5. 将首选模型提升到列表首位
6. 验证 springClientKey 在 ChatClientRegistry 中存在
```

**重要设计决策：** ModelSelector **不做熔断检查**。熔断检查延迟到 RoutingLLMService 实际调用前，避免批量 HALF_OPEN 泄漏。

---

## 6. 流式响应处理

### 6.1 双通道架构

| 通道 | 实现类 | 特点 |
|------|--------|------|
| **主通道** | `ProviderDirectStreamSupport` | 绕过 Spring AI，直接解析原始 SSE，性能最优 |
| **回退通道** | `ReactiveStreamAdapter` | 使用 Spring AI 内置流式，兼容性好 |

### 6.2 ProviderDirectStreamSupport (原始SSE解析)

**文件：** `chat/routing/ProviderDirectStreamSupport.java` (544行)

**这是最高性能的流式处理路径**，直接使用 WebClient 接收供应商的原始 SSE 文本，手动解析：

- **DeepSeek 原始流** (`submitDeepSeek`, 第72-101行)：通过反射获取 `WebClient` 和构建请求，接收 `String` Flux，手动解析 SSE payload
- **智谱原始流** (`submitZhiPu`, 第103-133行)：同样的反射模式，获取 `WebClient` 和 `completionsPath`

**SSE 手动解析器** (`extractSsePayloads`, 第294-344行)：
- 处理多行 `data:` 字段
- 处理注释行 (`:`)
- 识别 `[DONE]` 信号

**RawToolCallAccumulator** (第486-517行)：跨 SSE chunk 累积工具调用的增量参数（arguments 是分片到达的），在 `finishReason=TOOL_CALLS` 时刷出完整调用。

**CancellationAwareStreamCallback** (第434-484行)：包装回调，在流被取消后静默丢弃所有后续事件，防止首包探测失败后的过期事件到达下游。

### 6.3 ReactiveStreamAdapter (Spring AI 回退)

**文件：** `chat/routing/ReactiveStreamAdapter.java` (77行)

使用 Spring AI 的 `ChatClient.prompt().stream().chatResponse()` Flux，从每个 chunk 中提取内容文本、工具调用和推理内容。

### 6.4 StreamCallback (流式回调接口)

**文件：** `chat/routing/StreamCallback.java`

```java
public interface StreamCallback {
    void onSignal();                    // 任何 SSE 事件（心跳检测）
    void onContent(String content);     // 常规文本内容
    void onThinking(String thinking);   // 推理/思考内容
    void onToolCalls(List<ToolCall>);   // 工具调用结果
    void onComplete();                  // 流正常完成
    void onError(Throwable t);          // 流异常
}
```

---

## 7. 配置体系

### 7.1 ChatRoutingProperties

**文件：** `chat/routing/ChatRoutingProperties.java`

绑定到 `chat.routing.*`：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `defaultModel` | glm-5.1 | 默认模型 |
| `deepThinkingModel` | - | 深度思考模式使用的模型 |
| `firstPacketTimeoutSeconds` | 60 | 首包超时时间 |
| `streamTotalTimeoutSeconds` | 300 | 流式总超时 |
| `httpConnectTimeoutSeconds` | 10 | HTTP 连接超时 |
| `httpReadTimeoutSeconds` | 65 | HTTP 读取超时 |

### 7.2 候选模型配置 (CandidateConfig)

| 配置项 | 说明 |
|--------|------|
| `id` | 唯一标识 |
| `springClientKey` | 对应的 ChatClient Bean 名称 |
| `priority` | 优先级（默认100，越小越高） |
| `enabled` | 是否启用 |
| `supportsThinking` | 是否支持思考模式 |
| `thinkingStrategy` | 思考策略：NONE / ZHIPU_THINKING_FLAG / MODEL_OVERRIDE |

### 7.3 运行时覆盖 (RoutingRuntimeOverridesStore)

**文件：** `chat/routing/RoutingRuntimeOverridesStore.java`

使用 `ConcurrentHashMap` 存储每个模型的运行时覆盖，允许运维人员**不重启**即可动态调整模型优先级、启用/禁用等。通过 Admin API (`/api/admin/chat-routing`) 管理。

---

## 8. 指标监控 (RoutingMetrics)

**文件：** `chat/routing/RoutingMetrics.java`

基于 Micrometer 的完整指标体系：

| 指标名 | 类型 | Tag |
|--------|------|-----|
| `chatagent.llm.routing.attempts` | Counter | mode, model, outcome, fallback_available |
| `chatagent.llm.routing.latency` | Timer | 同上 |
| `chatagent.llm.circuit.decisions` | Counter | model, decision (denied_open, denied_half_open...) |
| `chatagent.llm.circuit.events` | Counter | model, event (half_open, closed, opened...) |

**优雅降级：** 如果 `MeterRegistry` 不在上下文中，返回 no-op 实例，不影响业务。

---

## 9. RoutingPromptFactory (Prompt 构建)

**文件：** `chat/routing/RoutingPromptFactory.java`

根据目标供应商类型构建对应的 `Prompt`：

- 智谱 → `ZhiPuAiChatOptions`
- DeepSeek → `DeepSeekChatOptions`
- 使用 `ModelOptionsUtils.copyToTarget()` 进行跨类型转换

**深度思考支持：**
- `ZHIPU_THINKING_FLAG`：调用 `setThinking(Thinking.enabled())`
- `MODEL_OVERRIDE`：覆盖 DeepSeek 的模型名为推理模型

---

## 10. 技术亮点总结

### 首包探测 (First Packet Detection)
- 流式场景下的智能模型切换，避免绑定到慢速模型
- 探测缓冲机制确保客户端不收到不完整数据
- 可配置的首包超时时间

### 高可用
- 多供应商冗余：DeepSeek 和智谱AI 互为备份
- 熔断器自动隔离故障模型
- 运行时覆盖支持不停机调整

### 高性能
- 原始 SSE 解析绕过 Spring AI 抽象层，减少开销
- 双通道架构：最优性能 + 兼容性回退
- 工具调用增量累积，避免重复解析

### 熔断机制
- 三态状态机 (CLOSED → OPEN → HALF_OPEN)
- probeGeneration 防污染
- 飞行超时防止探测卡死
- 可配置的失败阈值和恢复时间
