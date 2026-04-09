# Ragent 风格首包探测路由引擎 — Phase 1-7 代码完成基线

## Context
目标是分阶段把 ragent 的“首包探测 + 路由降级”迁入 ChatAgent。当前 Phase 1-7 的项目代码已经全部落地：Phase 1 已交付模型候选配置、注册表对齐、同步降级和安全边界；Phase 2 已交付断路器防死锁/防污染基线；Phase 3-5 已交付模型选择器、响应式流原语和路由降级核心闭环；Phase 6 已交付 Agent loop seam、SSE 适配和 speculative live passthrough/rollback 闭环；Phase 7 已交付运行时路由状态观测、candidate override 管理、provider raw SSE string parser 和深度思考策略配置。此前拟定的 Phase 8 真实监控平台接入计划现已取消，不再纳入当前主线计划；已落地的 observability 资产仅保留为可选参考，不作为当前待执行项。

**Phase 1 入口条件：**
1. **模型注册键必须可达**：`glm-4` 候选的 `spring-client-key` 必须匹配真实 `ChatClient` bean `glm-4.6`。
2. **同步路径不能使用首包阈值误杀**：`chatWithRouting` 不再用 `firstPacketTimeoutSeconds` 等待完整 `.call()` 结果。
3. **SSE 必须是完整消息 upsert**：后端发送完整 `ChatMessageVO`，前端按 `id` 更新已有消息，而不是丢弃同 id chunk。
4. **流式取消必须有工程级兜底**：Spring AI 1.1.0 的 DeepSeek/ZhiPuAI properties 没有 provider-local `client.read-timeout` 字段，当前通过全局 `RestClientCustomizer` / `WebClientCustomizer` 注入超时；已知 provider raw SSE 路径还会在 `dispose()` 后抑制迟到 chunk/complete/error。
5. **已知非等价点必须显式留档**：当前流式路径对 DeepSeek/Zhipu 已优先走 ChatAgent 自持 raw SSE string parser，从 provider WebClient 的 `bodyToFlux(String.class)` 直接解析 `[DONE]`、JSON chunk、`reasoning_content` / `tool_calls` / `content`；不支持的 provider 仍回退 Spring AI `ChatClient.stream()`。但我们仍未做到跨 provider 全独立 HTTP transport，也没有拿到 ragent `OkHttp Call.cancel()` 等价硬取消句柄，只能做到上游订阅 dispose + 下游迟到事件抑制。
6. **有工具场景必须消除双调用**：工具可用时改为单次 routed stream 决策；若最终无 tool calls，则直接保留并完成同一次响应中的 provisional assistant 消息；若最终出现 tool calls，则回滚 provisional assistant 消息，不再二次请求模型。

---

## 正式开始前：已完成准备工作

这一节记录的是已经在真实工程源码中落地并验证过的 Phase 1 前置基线，不是后续待办计划。正式开始 Phase 1 时，应以这里的状态作为起点。

1. **模型候选配置已对齐注册表**：`chatagent/bootstrap/src/main/resources/application.yaml` 中 `glm-4` 候选的 `spring-client-key` 已从不可达的 `glm-4` 修正为真实 `ChatClient` bean key `glm-4.6`，避免 fallback 在 `ModelSelector` 阶段被静默过滤。
2. **同步路径已撤掉首包阈值误杀**：`RoutingLLMService.chatWithRouting` 不再把同步 `.call()` 包进 `CompletableFuture` 后用 `firstPacketTimeoutSeconds` 等完整响应，工具决策链路不会再因为“不是首包探测”的 60s 倒计时被错误熔断。
3. **HTTP 超时兜底已改为可绑定的工程级配置**：不再使用 Spring AI DeepSeek/ZhiPuAI 当前不绑定的 provider-local `client.read-timeout` 键，而是通过 `chat.routing.http-connect-timeout-seconds` / `chat.routing.http-read-timeout-seconds` 和 `ChatModelHttpClientTimeoutConfig` 注入 `RestClientCustomizer` / `WebClientCustomizer`。
4. **流式消息桥接已改成完整 `ChatMessageVO` upsert**：`AgentMessageBridgeImpl.streamFinalResponse` 会先创建空内容的 ASSISTANT 消息拿到真实 `id`、`turnId`、`role` 等字段，推流过程中持续发送完整 DTO，并在完成或错误时回写数据库与发送 `AI_DONE`。
5. **前端已支持同 id 流式更新**：`AgentChatView.tsx` 的 `addMessage` / `mergeRealtimeMessages` 已从“同 id 直接丢弃”改成 upsert merge，避免只显示第一片 chunk 或被补偿轮询用空内容覆盖。
6. **HALF_OPEN 陈旧探针污染已拦截**：`ModelHealthStore` 已引入 `tryAcquire` 返回的 probe generation，`markSuccess(id, generation)` / `markFailure(id, generation)` 只接受匹配当前 HALF_OPEN generation 的探针结果。
7. **Agent loop 已切到单次流式决策**：无工具可用时直接走最终答案流式输出；有工具可用时通过 `streamDecisionWithRouting(...)` 在单次 routed stream 中组装文本/thinking/tool calls，并把文本/thinking 作为 provisional assistant 消息实时透传；若最终无 tool calls 则直接 finalize，若最终出现 tool calls 则执行 rollback，不再二次调用模型。
8. **Phase 1 关键回归测试已补一部分**：新增 `ModelSelectorTest` 覆盖 `glm-4 -> glm-4.6` fallback 可达性，新增 `RoutingLLMServiceTest` 覆盖同步路径不再使用首包阈值等待完整响应，新增 `ModelHealthStoreTest` 覆盖 HALF_OPEN 陈旧成功/失败回调不会污染新状态，新增 `AgentChatView` 用例覆盖同 id 流式 chunk upsert。
9. **基线验证已通过**：后端 `mvn -o -pl bootstrap -am test` 通过，当前测试结果为 233 tests pass；前端 `npm.cmd run test` 和 `npm.cmd run build` 均通过。前端 build 只保留既有 chunk size warning。
10. **路由可观测性已补强**：`RoutingLLMService` 会记录候选列表、attempt 序号、probe generation、同步/首包耗时、fallback 是否可用和最终全线失败；`ModelHealthStore` 会记录 OPEN / HALF_OPEN / CLOSED 状态转换、飞行探针超时重试、陈旧探针成功/失败被忽略等关键事件。
11. **路由指标已接入可选 Micrometer**：新增 `RoutingMetrics`，在存在 `MeterRegistry` 时记录 `chatagent.llm.routing.attempts`、`chatagent.llm.routing.latency`、`chatagent.llm.circuit.decisions`、`chatagent.llm.circuit.events`；没有 `MeterRegistry` 时 no-op，不改变启动要求。
12. **Selector 副作用边界已收紧**：`ModelSelector` 不再注入 `ModelHealthStore`，只负责 enabled / thinking / registry 过滤和排序；断路器 `tryAcquire` 只保留在 `RoutingLLMService` 真正发起模型调用前执行。
13. **Phase 2 断路器代码已完成**：`ModelHealthStore` 已支持 configurable `half-open-flight-timeout-ms`、HALF_OPEN generation token、防陈旧成功/失败污染、飞行探针超时重试、只读 `snapshot()` 状态快照和 Micrometer circuit events/decisions 埋点。
14. **Phase 3 模型选择器代码已完成**：`ModelSelector` 已完成空候选防护、disabled/blank key/spring-client-key 过滤、deepThinking `supportsThinking` 过滤、默认/深度思考首选模型置顶、registry 可达性过滤和选择日志。
15. **Phase 4 流式原语代码已完成**：`FirstPacketAwaiter` 负责首包 SUCCESS/ERROR/TIMEOUT/NO_CONTENT 判定；`ReactiveStreamAdapter` 使用响应式订阅，正文 delta 与 thinking delta 分离派发，thinking 优先读 provider-specific getter，再回退 metadata；DeepSeek/Zhipu 则优先走 `ProviderDirectStreamSupport` 的 raw SSE string parser。
16. **Phase 5 路由核心代码已完成**：`RoutingLLMService` 同步路径不再首包误杀，流式路径支持首包缓冲、submit 同步失败 fallback、所有候选被断路器跳过的显式错误、best-effort dispose、日志和指标记录。
17. **Phase 6 Agent/SSE 适配代码已完成**：`AgentThinkingEngine` 内部保留 final stream seam，不在 Orchestrator 外部追加不存在的 hook；无工具直流，有工具走单次 routed decision stream；`AgentMessageBridgeImpl` 支持把单次流式决策透传成 provisional `ChatMessageVO` 快照，并在 tool-call 分支执行 rollback、在纯文本分支执行 finalize + `AI_DONE`。
18. **reasoning 提取路径已补源码级回归证明**：新增 `ReactiveStreamAdapterTest` 验证 DeepSeek/Zhipu provider-specific `AssistantMessage.getReasoningContent()` 会被当前适配器派发；同时保留 metadata fallback 测试，避免再误判为 metadata-only 实现。
19. **深度思考策略代码已完成**：`ChatRoutingProperties.CandidateConfig` 已支持 `supportsThinking`、`thinkingStrategy`、`thinkingModel`；`RoutingPromptFactory` 会按候选策略注入 `ZHIPU_THINKING_FLAG` 或 `MODEL_OVERRIDE`，`application.yaml` 默认将 `glm-4` 标为 deep-thinking 候选。
20. **运行时路由管理代码已完成**：新增 `/api/admin/chat-routing/state`、`/api/admin/chat-routing/candidates/override` 和 `/api/admin/chat-routing/candidates/{candidateId}/override`，支持查看 effective candidate、断路器快照、registry 可达性和 orphan override。
21. **provider raw SSE 直连流代码已完成**：`RoutingLLMService` 对已知 provider 优先绕过 `ChatClient.stream()` 和 Spring AI provider chunk merger，改从 `DeepSeekApi` / `ZhiPuAiApi` 内部 WebClient 的 raw SSE string 流直接解析并分发 content / thinking / tool calls。
22. **基础错误事件已完成**：SSE 新增 `AI_ERROR` 事件，后端流式中断和异步失败兜底路径会显式推送错误状态，前端能显示对应 error status。
23. **基础错误恢复 UX 已完成**：前端会把 `AI_ERROR` 保留成可见的错误条，并提供 `Retry` / `Dismiss` 基础交互；`Retry` 会直接重发当前失败回合对应的上一条 user 输入。
24. **路由健康告警基线已完成**：新增 `chat.routing.observability` 配置和 `chatRouting` Actuator health indicator，可在无可路由候选、所有候选断路器 OPEN、OPEN 比例超过阈值、存在 orphan runtime override 时输出 DOWN / OUT_OF_SERVICE / DEGRADED 状态。
26. **raw SSE 取消防污已完成**：`ProviderDirectStreamSupport` 对 DeepSeek/Zhipu raw SSE 路径返回取消感知句柄，`dispose()` 会取消上游 WebClient 订阅，并通过 guarded callback 抑制迟到的 signal/content/thinking/tool-calls/error/complete 继续污染前端或持久化状态。
27. **raw SSE payload 规范化已完成**：`ProviderDirectStreamSupport` 在 JSON 反序列化前会统一处理纯 JSON、`[DONE]`、`data:` 前缀、多行 SSE event、空行分隔和 comment 行，降低真实网关/代理返回形态差异导致解析失败的概率。
28. **provider 直连兼容回退已完成**：当 Spring AI provider 私有字段/方法因版本差异导致 raw SSE 直连不可用时，`ProviderDirectStreamSupport` 会返回空结果并回退 `ReactiveStreamAdapter`；真实流式运行中的模型错误仍按当前候选失败处理，不被静默吞掉。
29. **raw SSE 解析错误上下文已完成**：JSON chunk 解析失败时会抛出带 provider、modelKey、chunkType、payloadLength 的异常，便于定位 provider 兼容问题；异常消息不包含 payload 原文，避免把模型输出或上下文片段写入日志。
30. **非冒烟回归测试已补一轮**：新增 `ProviderDirectStreamSupportTest` 覆盖 raw SSE payload 规范化、provider 直连回退、取消后迟到事件抑制和 parse error 安全上下文；扩展 `AgentChatView.test.tsx` 覆盖 `AI_ERROR` retry/dismiss、`TURN_ROLLBACK` provisional assistant 清除和同 id chunk 更新不被短内容覆盖。
31. **Agent loop 三分支单测已补**：新增 `AgentThinkingEngineTest` 覆盖无工具时直接 `streamFinalResponse`、有工具且返回 tool calls 时只 `persistAndPublish` 并继续工具 loop、有工具但返回纯文本时命中 live passthrough finalize 且不触发第二次模型调用。
32. **Admin controller HTTP 测试已补**：新增 `ChatRoutingAdminControllerTest` 覆盖 `/api/admin/chat-routing/state`、override 更新和 override 清除接口的 admin 鉴权、HTTP 参数绑定、JSON 返回值序列化，以及非 admin 请求在调用 facade 前被拒绝。

## Phase 1-7 当前完成状态

**状态：项目代码已完成，测试/冒烟后置。**

Phase 1-7 的完成定义是：把 ChatAgent 当前架构内的 ragent 风格首包探测路由基线、Agent loop seam、SSE 适配、单次流式工具决策 live passthrough/rollback、深度思考策略和运行时运维入口全部落地到可编译、可运行、可观测、可配置的状态；后续仅保留测试、冒烟和生产运营化事项。

已完成闭环：

1. **配置闭环**：候选模型配置、`glm-4 -> glm-4.6` registry 对齐、HTTP connect/read timeout、stream total timeout 均已落地。
2. **路由闭环**：同步 `chatWithRouting` 不再用首包阈值误杀完整响应；流式 `streamChat` 使用首包探测、候选 fallback、首包前缓冲和断路器状态回写。
3. **断路器闭环**：HALF_OPEN 飞行锁、飞行超时重试、probe generation 防陈旧回调污染均已落地。
4. **SSE 闭环**：最终答案流式输出使用完整 `ChatMessageVO`，前端按同 id upsert，content/error 推送使用不可变快照，完成/错误/兜底均以 `AI_DONE` 收尾且不关闭会话级 SSE。
5. **Agent loop 闭环**：final stream seam 明确留在 `AgentThinkingEngine.think()` 内部；无工具直接流式，有工具走单次 routed stream 决策，纯文本分支走 speculative live passthrough 并在同一 assistant 消息上 finalize，tool-call 分支则 rollback provisional assistant 消息后继续工具 loop；最终流式 prompt 保留 session file / user profile 附加上下文。
6. **reasoning 闭环**：DeepSeek/Zhipu 的流式路径已优先走 ChatAgent raw SSE string parser，直接解析原始 JSON chunk 的 `delta.reasoning_content`；不支持 provider 直连的链路才回退 Spring AI provider-specific `getReasoningContent()` 与 metadata。
7. **可观测闭环**：日志覆盖候选、attempt、fallback、首包耗时、断路器状态变化；Micrometer 指标覆盖 routing attempts/latency 和 circuit decisions/events。
8. **选择器闭环**：`ModelSelector` 只做无副作用候选过滤/排序，坏配置和不可达 registry key 会被跳过并记录；运行时 override 会在选择前叠加到 effective candidate。
9. **流式原语闭环**：首包等待器和响应式适配器已经覆盖 SUCCESS / ERROR / TIMEOUT / NO_CONTENT 和 content/thinking/tool-call 分流；已知 provider 还能走更低一层的 raw SSE string parser。
10. **失败边界闭环**：同步/流式路径都能区分模型失败、submit 失败、首包失败和候选全被断路器跳过。
11. **深度思考闭环**：`glm-4` 默认作为 `deepThinkingModel`，`RoutingPromptFactory` 会按候选策略启用 `ZhiPuAi` thinking flag 或 provider model override。
12. **运行时运维闭环**：管理员可以在线查看 candidate 的 configured/effective 状态、断路器快照、registry 可达性，并能对 enabled/priority/thinking 相关字段做 runtime override。
13. **错误状态闭环**：流式中断和异步失败场景除 assistant 兜底内容外，还会额外下发 `AI_ERROR` SSE 状态，前端具备单独的 error status 通道，并保留基础 retry/dismiss 错误恢复交互。
14. **健康告警闭环**：`/actuator/health/chatRouting` 可直接反映路由可用性和断路器风险，支持通过配置调整 no-routable、all-open、open-ratio、orphan override 等告警边界。

下面保留的是后续冒烟、重型并发测试或生产运营化事项，不再视作当前项目代码缺口。

已补非冒烟测试清单：

1. **raw SSE parser 单测**：覆盖纯 JSON、`[DONE]`、`data:` SSE payload、comment 行、空行分隔、多事件帧规范化，以及 JSON chunk parse error 的 provider/modelKey/chunkType/payloadLength 安全上下文。
2. **provider 直连回退单测**：覆盖 Spring AI provider 私有 API 不可用时，`ProviderDirectStreamSupport` 返回空结果并允许上层回退 `ChatClient.stream()`。
3. **取消防污单测**：覆盖 raw SSE handle `dispose()` 后会取消上游 `Disposable`，并抑制迟到的 signal/content/thinking/error/complete 继续派发。
4. **前端错误恢复测试**：覆盖 `AI_ERROR` 生成持久错误条、`Retry` 复用上一条用户输入、`Dismiss` 清除错误条。
5. **前端 rollback/upsert 测试**：覆盖 `TURN_ROLLBACK` 清除 provisional assistant 消息，以及同 id 流式 chunk 更新不会被更短内容覆盖。
6. **runtime override 生效测试**：覆盖 `RoutingRuntimeOverridesStore` 覆盖 enabled、priority、supportsThinking、thinkingStrategy、thinkingModel 后，`ModelSelector` 在普通/深度思考选择中立即使用 effective candidate。
7. **chatRouting health indicator 测试**：覆盖 no-routable、all-open、open-ratio、orphan override 和健康候选对应的 `DOWN` / `OUT_OF_SERVICE` / `DEGRADED` / `UP` 分支。
8. **application.yaml 配置绑定测试**：覆盖真实 `application.yaml` 中 `deep-thinking-model`、HTTP/stream timeout、health、observability、candidate `supports-thinking` 和 `thinking-strategy` 能绑定到 `ChatRoutingProperties`。
9. **admin facade 状态与 override 测试**：覆盖 `ChatRoutingAdminFacadeServiceImpl` 的 routing state 组装、registered models、orphan override、断路器快照、override upsert/clear 和非法 override 校验。
10. **Agent loop 三分支测试**：覆盖无工具直流、有工具返回 tool calls 时持久化并继续工具 loop、有工具但纯文本时 live passthrough finalize 且不二次调用模型。
11. **Admin controller HTTP 测试**：覆盖 admin 鉴权、非 admin 拒绝、state JSON 序列化、override request body 绑定和 clear override path variable 绑定。
12. **HALF_OPEN 并发压力测试**：覆盖第二探针放行后，大量陈旧 success/failure 回调与较新 probe 结果并发竞争时，`ModelHealthStore` 仍保持 generation token 隔离，不会被陈旧回调污染。
13. **完整 Spring Boot + JWT 链路集成测试**：覆盖真实 `JwtAuthenticationInterceptor + RequireRoleInterceptor + UserWebMvcConfig + JwtTokenService` 链路，验证缺 token 401、坏 token 401、非 admin 403、admin 200，以及 override body 绑定在真实 JWT 鉴权链后仍正常生效。

Phase 1 暂缓/未做测试清单：

1. **真实 SSE 会话连续性测试**：同一 `chatSessionId` 下连续两轮 `AI_DONE` 后 EventSource 不应被 close，第二轮仍能收到 `AI_GENERATED_CONTENT`。
2. **真实模型首包降级集成测试**：人为让主模型首包超时或报错，验证 fallback 候选接管，并确认首包前缓冲不会把失败模型的 chunk 泄露到前端。
3. **流式中断持久化测试**：流式过程中 `onError` / 总超时触发时，数据库应保存已生成内容加中断提示，前端应收到最后一次完整 `ChatMessageVO` 和 `AI_DONE`。
4. **provider `reasoning_content` 兼容性测试**：用 DeepSeek/Zhipu 实际 streaming 响应确认 ChatAgent raw SSE string parser 能稳定解析 `delta.reasoning_content`、`delta.content` 和 streaming tool calls；不支持 provider 仍需确认 Spring AI fallback 的 provider-specific `AssistantMessage.getReasoningContent()` 是否稳定返回 CoT。

Phase 1-7 之外的后续增强：

1. **生产环境外部观测接入未做**：当前已埋点到 Micrometer，新增 `chatRouting` Actuator health indicator，并提供 Prometheus/Grafana 模板；但还没有在真实监控平台导入 dashboard、挂载 rule file 或配置通知路由。
2. **跨 provider 独立 raw transport 未做**：当前 DeepSeek/Zhipu 已走 ChatAgent 自持 raw SSE string parser，但仍复用 Spring AI provider 已配置好的 WebClient / request 构造 / 鉴权 header；未知 provider 仍回退 `ChatClient.stream()`，没有做完全独立的跨 provider HTTP transport。
3. **provider-level hard cancel 未做**：当前已把已知 provider 的流式路径下探到 WebClient raw SSE 订阅，取消时会 dispose 上游 HTTP 流并抑制迟到事件；但仍没有拿到 OkHttp `Call.cancel()` 等价的底层取消句柄。

仍然不要在当前基线中宣称已经完成的能力：

1. **跨 provider 全独立 HTTP transport**：当前 DeepSeek/Zhipu 已不再依赖 Spring AI provider 的 SSE 解码与 chunk merge，已由 ChatAgent 自己解析 raw SSE string；但仍复用 Spring AI provider 的 WebClient、request 构造和鉴权配置，不要宣称已经完全脱离 Spring AI provider API。
2. **ragent 级底层请求硬取消**：当前通过 HTTP connect/read timeout、raw SSE 上游订阅 dispose 和下游迟到事件抑制做工程级兜底，并没有拿到等价于 ragent `OkHttp Call.cancel()` 的 provider-level cancellation handle。

---

## Phase 1：模型配置与候选元数据

**`chatagent/bootstrap/src/main/resources/application.yaml`**
*（修正：`glm-4` 的 `spring-client-key` 必须与真实注册的 `glm-4.6` bean 一致）*

```yaml
chat:
  routing:
    default-model: deepseek-chat
    deep-thinking-model: glm-4
    first-packet-timeout-seconds: 60
    stream-total-timeout-seconds: 300
    http-connect-timeout-seconds: 10
    http-read-timeout-seconds: 65
    health:
      failure-threshold: 3
      open-duration-ms: 300000
      half-open-flight-timeout-ms: 120000
    observability:
      open-circuit-warning-ratio: 0.5
      down-when-no-routable-candidates: true
      out-of-service-when-all-routable-candidates-open: true
      warn-on-orphan-overrides: true
    candidates:
      - id: deepseek-chat
        spring-client-key: deepseek-chat
        priority: 10
        enabled: true
        supports-thinking: false
      - id: glm-4
        spring-client-key: glm-4.6  # 关键修复：匹配 ChatClientRegistry 中的真实 key
        priority: 20
        enabled: true
        supports-thinking: true
        thinking-strategy: ZHIPU_THINKING_FLAG
```

**`chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/ChatRoutingProperties.java`**
```java
package com.yulong.chatagent.chat.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "chat.routing")
public class ChatRoutingProperties {
    private String defaultModel = "deepseek-chat";
    private String deepThinkingModel;
    private int firstPacketTimeoutSeconds = 60;
    private int streamTotalTimeoutSeconds = 300;
    private int httpConnectTimeoutSeconds = 10;
    private int httpReadTimeoutSeconds = 65;
    private HealthConfig health = new HealthConfig();
    private ObservabilityConfig observability = new ObservabilityConfig();
    private List<CandidateConfig> candidates = new ArrayList<>();

    @Data
    public static class CandidateConfig {
        private String id;
        private String springClientKey;
        private Integer priority = 100;
        private Boolean enabled = true;
        private Boolean supportsThinking;
        private String thinkingStrategy = "NONE";
        private String thinkingModel;
    }

    @Data
    public static class HealthConfig {
        private int failureThreshold = 3;
        private long openDurationMs = 300_000L;
        private long halfOpenFlightTimeoutMs = 120_000L;
    }

    @Data
    public static class ObservabilityConfig {
        private double openCircuitWarningRatio = 0.5D;
        private boolean downWhenNoRoutableCandidates = true;
        private boolean outOfServiceWhenAllRoutableCandidatesOpen = true;
        private boolean warnOnOrphanOverrides = true;
    }
}
```

**`chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/ModelTarget.java`**
```java
package com.yulong.chatagent.chat.routing;
import org.springframework.ai.chat.client.ChatClient;

public record ModelTarget(String id, ChatRoutingProperties.CandidateConfig candidate, ChatClient chatClient) {}
```

---

## Phase 2：防死锁与防污染断路器（ModelHealthStore）

**关键修正**：加入 HALF_OPEN 探针 generation token。只检查当前状态是否 `OPEN` 不够，因为旧探针的迟到 `markFailure` 也可能污染已经被新探针恢复的 `CLOSED` 状态。

**状态：代码完成。**

Phase 2 的完成定义是：断路器不能因为 HALF_OPEN 探针线程挂死而永久阻塞，不能因为陈旧探针迟到而污染新状态，并且要能通过日志、指标和只读快照排查状态。

已完成闭环：

1. **防死锁**：HALF_OPEN 探针有 `half-open-flight-timeout-ms` 配置兜底，超过飞行时间后允许新探针接管。
2. **防污染**：HALF_OPEN 探针放行时递增 `probeGeneration`，`markSuccess(id, generation)` / `markFailure(id, generation)` 只接受当前匹配 generation。
3. **防批量泄露**：`ModelSelector` 不再持有 `ModelHealthStore`，候选选择阶段不调用 `allowCall()` / `tryAcquire()`；断路器状态只在 `RoutingLLMService` 真正调用模型前延迟扣减。
4. **可观测**：断路器 OPEN / HALF_OPEN / CLOSED、飞行探针超时、陈旧成功/失败被忽略均有日志和 Micrometer event；`snapshot()` 提供只读状态视图用于后续运维接口或诊断。
5. **兼容性**：普通 CLOSED 调用仍使用 generation `0`，保留连续失败计数语义；无 `MeterRegistry` 时指标 no-op。

Phase 2 暂缓项：

1. **并发压力测试未做**：已在 Phase 1 暂缓测试清单记录。后续需要用更重的多线程时序覆盖飞行超时、第二探针放行、旧回调迟到等竞态。
2. **运维查询接口未做**：当前只有 `snapshot()` 方法，没有新增 admin/controller 暴露断路器状态；如要接入后台页面再单独加。

Phase 2 验证记录：

1. `mvn -o -pl bootstrap -am -DskipTests compile` 通过。
2. `mvn -o -pl infra -DskipTests test-compile` 通过。
3. `mvn -o -pl infra -Dtest=ModelHealthStoreTest test` 通过，2 tests pass。

**`chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/ModelHealthStore.java`**
```java
ModelHealthStore.CallPermit permit = healthStore.tryAcquire(modelId);
if (!permit.allowed()) {
    return;
}

try {
    // model call...
    healthStore.markSuccess(modelId, permit.generation());
} catch (Exception ex) {
    healthStore.markFailure(modelId, permit.generation());
}
```

实现要求：
1. `tryAcquire` 在 HALF_OPEN 探针放行时递增 `probeGeneration`，并把 generation 返给调用方。
2. `markSuccess(id, generation)` 只允许匹配当前 HALF_OPEN generation 的探针关闭断路器。
3. `markFailure(id, generation)` 只允许匹配当前 HALF_OPEN generation 的探针重新打开断路器。
4. 普通 CLOSED 状态调用仍可使用 generation `0`，保持连续失败计数语义。

---

## Phase 3：模型选择器（ModelSelector）

**`chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/ModelSelector.java`**
**状态：代码完成。**

完成定义：`ModelSelector` 只负责无副作用的候选排序与过滤，不能提前触发断路器状态机。

已完成闭环：

1. **空配置防护**：`properties.getCandidates()` 为空或 null 时返回空候选，不抛 NPE。
2. **候选合法性过滤**：跳过 null、disabled、空 `id`、空 `spring-client-key` 的候选，并输出 warning/debug 日志。
3. **深度思考过滤**：`deepThinking=true` 时只保留 `supportsThinking=true` 的候选。
4. **首选模型置顶**：普通请求优先 `defaultModel`，深度思考请求优先 `deepThinkingModel`；首选模型必须仍通过 enabled/thinking/registry 过滤。
5. **registry 可达性过滤**：只保留 `ChatClientRegistry.supports(springClientKey)` 为 true 的候选，避免 fallback 静默不可达。
6. **无副作用边界**：不注入 `ModelHealthStore`，不调用 `allowCall()` / `tryAcquire()`；断路器延迟到 `RoutingLLMService` 真正调用模型前扣减。

Phase 3 暂缓项：

1. **provider 远端元数据级能力发现未做**：当前 `supportsThinking` 已支持显式配置、runtime override、provider bean registry 和基于 `spring-client-key` / `thinkingStrategy` 的能力推断，但仍不是直接从 provider 远端元数据自动发现。

---

## Phase 4：响应式流原语与数据提取

**`chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/StreamCallback.java`**
**状态：代码完成。**

完成定义：流式原语能区分首包成功/失败/空完成，能把正文与 thinking 分开派发，且不再只依赖 metadata 读取 `reasoning_content`。

已完成闭环：

1. **首包等待器**：`FirstPacketAwaiter` 用一次性 latch 区分 `SUCCESS`、`ERROR`、`TIMEOUT`、`NO_CONTENT`。
2. **响应式订阅**：`ReactiveStreamAdapter.submit(...)` 直接订阅 Spring AI `stream().chatResponse()`，不再用线程池 `toStream()` 阻塞式消费。
3. **正文派发**：非空 content 派发到 `StreamCallback.onContent(...)`。
4. **thinking 派发**：优先读取 `DeepSeekAssistantMessage.getReasoningContent()` / `ZhiPuAiAssistantMessage.getReasoningContent()`，再回退 metadata 的 `reasoning_content` / `reasoningContent` / `reasoning`。
5. **源码级验证**：本地核对 Spring AI 1.1.0 的 `DeepSeekApi.chatCompletionStream(...)` / `ZhiPuAiApi.chatCompletionStream(...)` 可确认 provider 内部也是先消费 `bodyToFlux(String.class)`，因此 ChatAgent 可以安全复用其 WebClient/request 构造后自行接管 raw SSE string 解析。
6. **raw SSE 解析接管**：DeepSeek/Zhipu 已由 `ProviderDirectStreamSupport` 直接消费 raw SSE string，先规范化纯 JSON / `[DONE]` / `data:` SSE payload，再自己处理 JSON chunk、`delta.content`、`delta.reasoning_content` 和 streaming tool-call 增量合并；若 provider 私有 API 因版本差异不可访问，则回退 Spring AI `ChatClient.stream()`；若 JSON chunk 解析失败，则抛出不含 payload 原文的结构化上下文异常。

**`chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/ReactiveStreamAdapter.java`**
*(Fallback 实现仍优先读取 Spring AI DeepSeek/Zhipu provider-specific `AssistantMessage.getReasoningContent()`，再回退 `output.getMetadata().get("reasoning_content")` / `reasoningContent`。已知 DeepSeek/Zhipu 主路径则优先走 `ProviderDirectStreamSupport` 的 raw SSE string parser。)*

Phase 4 暂缓项：

1. **跨 provider raw transport 未做**：DeepSeek/Zhipu 已完成 ChatAgent 自持 raw SSE string parser；如果未来要覆盖更多 provider 或完全脱离 Spring AI provider API 的 WebClient/request 构造，仍需补跨 provider HTTP transport。

---

## Phase 5：路由降级核心（RoutingLLMService）

**关键修正：** 
1. `chatWithRouting` 移除错误的 Future 强行截断。因为工具调用通常非常耗时（轻易超过 60s 首包时间），同步模式下只需依靠底层长连接超时即可。
2. `streamChat` 明确备注 `Disposable` 不是 ragent 级硬取消；已知 provider raw SSE 路径会 dispose 上游 WebClient 订阅并抑制迟到事件，但仍须搭配工程级 HTTP client timeout 兜底；不要再写 `spring.ai.deepseek.client.read-timeout` 这类当前 Spring AI 1.1.0 不绑定的 provider-local 假配置。
3. `ModelHealthStore` 的 HALF_OPEN 探针使用 generation token，避免超时旧探针覆盖新状态。

**状态：代码完成。**

完成定义：同步路径保护工具决策，流式路径完成首包探测与 fallback，失败路径能准确回写断路器并给调用方明确错误。

已完成闭环：

1. **同步路径**：不使用 `firstPacketTimeoutSeconds` 等完整 `.call()`，tools 为 null 时按空列表处理；失败后按候选顺序 fallback。
2. **流式路径**：每个候选先 `tryAcquire`，再启动 `ReactiveStreamAdapter.submit`，首包成功后才 `commit()` 缓冲并标记成功。
3. **submit 失败兜底**：`ReactiveStreamAdapter.submit` 同步抛错时会标记该模型失败、记录日志/指标并继续下一个候选。
4. **首包失败兜底**：TIMEOUT / ERROR / NO_CONTENT 均会 markFailure、best-effort dispose，并继续 fallback。
5. **断路器跳过可解释**：所有候选均被断路器跳过时，同步/流式路径都会返回显式错误，而不是只抛一个 cause 为 null 的全线失败。
6. **可观测**：同步和流式 attempt 都记录候选、attempt、耗时、fallbackAvailable、错误类型和 Micrometer 指标。

Phase 5 暂缓项：

1. **provider-level hard cancel 未做**：已知 provider 的 raw SSE 路径可以 dispose WebClient 上游 HTTP 流，并通过 cancellation-aware callback 抑制迟到事件；底层释放仍依赖 Reactor/WebClient 与 HTTP timeout 兜底，没有 OkHttp `Call.cancel()` 等价句柄。
2. **跨 provider 原始请求句柄未做**：没有统一抽象 OkHttp `Call.cancel()` 等价句柄。
3. **生产级 fallback 告警接入未做**：已埋点，并提供 Prometheus/Grafana 模板，但真实监控平台导入、通知路由和阈值调参仍属运营化事项。

Phase 3-5 验证记录：

1. `mvn -o -pl bootstrap -am -DskipTests compile` 通过。
2. `mvn -o -pl infra -Dtest=ModelSelectorTest,ModelHealthStoreTest,RoutingLLMServiceTest test` 通过，5 tests pass。
3. `mvn -o -pl infra -am -Dtest=ReactiveStreamAdapterTest,ModelSelectorTest,ModelHealthStoreTest,RoutingLLMServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过，8 tests pass。
4. `mvn -o -pl infra '-Dtest=ProviderDirectStreamSupportTest,ReactiveStreamAdapterTest,ModelSelectorTest,ModelHealthStoreTest,RoutingLLMServiceTest' test` 通过，12 tests pass。

**`chatagent/infra/src/main/java/com/yulong/chatagent/chat/routing/RoutingLLMService.java`**
```java
package com.yulong.chatagent.chat.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RoutingLLMService {

    private final ModelSelector modelSelector;
    private final ModelHealthStore healthStore;
    private final ChatRoutingProperties properties;

    public RoutingLLMService(ModelSelector modelSelector, ModelHealthStore healthStore, ChatRoutingProperties properties) {
        this.modelSelector = modelSelector;
        this.healthStore = healthStore;
        this.properties = properties;
    }

    // ================== 同步路径（Agent Loop think 用）==================

    public ChatResponse chatWithRouting(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
        List<ModelTarget> targets = modelSelector.selectChatCandidates(false);
        if (targets.isEmpty()) throw new RuntimeException("无可用大模型候选");

        Throwable lastError = null;
        for (ModelTarget target : targets) {
            ModelHealthStore.CallPermit permit = healthStore.tryAcquire(target.id());
            if (!permit.allowed()) continue;

            try {
                // 🚨 修复：移除 CompletableFuture 首包超时。
                // 工具调用决策属于完整执行阶段，耗时较长，由工程级 HTTP read timeout 保底，不作强行截断。
                ChatResponse response = target.chatClient()
                        .prompt(prompt).system(systemPrompt)
                        .toolCallbacks(tools.toArray(new ToolCallback[0]))
                        .call().chatClientResponse().chatResponse();
                        
                healthStore.markSuccess(target.id(), permit.generation());
                return response;
            } catch (Exception e) {
                healthStore.markFailure(target.id(), permit.generation());
                lastError = e;
                log.warn("模型 [{}] 同步调用失败: {}", target.id(), e.getMessage());
            }
        }
        throw new RuntimeException("所有候选模型同步调用均失败", lastError);
    }

    // ================== 流式路径（真实回复探测用）==================

    public Disposable streamChat(Prompt prompt, boolean deepThinking, StreamCallback callback) {
        List<ModelTarget> targets = modelSelector.selectChatCandidates(deepThinking);
        if (targets.isEmpty()) {
            callback.onError(new RuntimeException("无可用大模型候选"));
            return () -> {};
        }

        Throwable lastError = null;
        for (ModelTarget target : targets) {
            ModelHealthStore.CallPermit permit = healthStore.tryAcquire(target.id());
            if (!permit.allowed()) continue;

            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
            ProbeBufferingCallback wrapper = new ProbeBufferingCallback(callback, awaiter, healthStore, target.id());

            // 发起流式探测
            Disposable disposable = ReactiveStreamAdapter.submit(target.chatClient(), prompt, wrapper);

            FirstPacketAwaiter.Result result;
            try {
                result = awaiter.await(properties.getFirstPacketTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                disposable.dispose();
                callback.onError(new RuntimeException("等待首包被中断", e));
                return () -> {};
            }

            if (result.isSuccess()) {
                wrapper.commit();
                healthStore.markSuccess(target.id(), permit.generation());
                return disposable;
            }

            // 🚨 注意软取消局限：disposable.dispose() 仅切断 Reactor 流，不能保证立刻关闭底层 Socket。
            // 这里依赖 ChatModelHttpClientTimeoutConfig 注入的 HTTP read timeout 来兜底释放底层请求。
            healthStore.markFailure(target.id(), permit.generation());
            disposable.dispose();
            lastError = result.getError();
        }

        callback.onError(new RuntimeException("大模型服务全线降级失败", lastError));
        return () -> {};
    }

    // ... (ProbeBufferingCallback 代码省略，与上一版一致，包含中途断开时的 onError 熔断上报)
}
```

---

## Phase 6：Agent Loop 兼容性边界与 SSE 适配

**状态：代码完成。**

完成定义：最终答案流式分流必须落在真实 `ChatAgent.step() -> AgentThinkingEngine.think()` 契约内，不能依赖 Orchestrator 外部不存在的 final stream hook；SSE 必须使用当前前后端真实 `SseMessage` / `ChatMessageVO` 协议，支持同 id upsert、完成/错误收尾和会话级连接连续性。

Phase 6 不声称这里已经 1:1 复刻 ragent。当前真实实现已经消除了“双调用 pure-text”缺陷，但仍属于 ChatAgent 架构内的兼容式落地：

1. **无工具可用时**：`AgentThinkingEngine` 直接走 `streamFinalResponse`，避免先同步完整生成再二次流式生成。
2. **有工具可用时**：改为 `streamDecisionResponse(...)` 在单次 routed stream 中一边组装文本/thinking/tool calls，一边把文本/thinking 作为 provisional assistant 消息实时透传；若最终有 tool calls，则 rollback provisional assistant 消息并继续 loop；若最终无 tool calls，则直接 finalize 同一条 assistant 消息，不再第二次调用模型。
3. **当前仍非 ragent 级完全等价流**：工具可用纯文本分支已经做到首 token 直达 UI 的 speculative live passthrough；DeepSeek/Zhipu 已接入 raw SSE string parser 和取消后迟到事件抑制，但底层仍复用 Spring AI provider 的 WebClient/request 构造且没有 provider-level hard cancel。

**关键 seam 决策：**当前不存在一个可以在 `ConversationOrchestrator` 外部追加的“final stream hook”。`ChatAgent.step()` 的契约只有 `thinkingEngine.think(...) -> ChatResponse`，随后根据 `ChatResponse.hasToolCalls()` 决定继续执行工具还是结束。因此 Phase 6 的最终答案流式分流必须留在 `AgentThinkingEngine.think()` 内部：无工具分支直接 `streamFinalAnswer(...)`，有工具分支则在同一次 routed stream 内组装决策结果，并由 `AgentMessageBridgeImpl` 决定是 finalize provisional assistant 还是 rollback 后继续工具 loop。

分流表：
1. **`availableTools.isEmpty()`**：跳过同步工具决策，直接 `streamFinalAnswer`。
2. **`availableTools` 非空且模型返回 tool calls**：只 `persistAndPublish` 工具调用决策，交给 `AgentToolExecutionEngine` 继续 loop。
3. **`availableTools` 非空且模型返回纯文本**：抑制同步纯文本落库/推送，改为在同一次 routed stream 中实时透传 provisional assistant 消息，并在流结束后直接 finalize 同一条消息，避免重复消息、重复模型调用和工具决策泄露。

已完成闭环：

1. **真实 seam 落点**：`streamFinalAnswer(...)` 保留在 `AgentThinkingEngine.think()` 内部，外层 `ChatAgent.step()` 仍只根据返回的 `ChatResponse.hasToolCalls()` 判断是否继续工具 loop。
2. **最终回答 prompt 上下文**：无工具直流场景下仍使用 final-answer 专用 system prompt，补入 `sessionFileSummary` / `userProfileSummary`，避免直接流式回答丢掉附加上下文。
3. **工具决策隔离**：有 tool calls 的单次流式决策结果只持久化工具调用消息并进入工具执行，不向前端重放工具决策正文，避免 tool-call JSON 或内部决策文本泄露。
4. **纯文本单次流 finalize**：有工具但模型返回纯文本时，直接保留同一次 routed stream 中实时透传的 provisional assistant 消息，并在流结束后 finalize 内容与 `AI_DONE`，避免重复 assistant 消息与重复模型调用。
5. **tool-call 回滚**：有工具且最终出现 tool calls 时，删除 provisional assistant 消息并发送 `TURN_ROLLBACK`，确保前端不会残留决策文本或 speculative 内容。
6. **SSE DTO 对齐**：后端不发送裸字符串 delta，每次 content 推送都是完整 `SseMessage(Type.AI_GENERATED_CONTENT, Payload.message=ChatMessageVO)`。
7. **不可变快照推送**：`AgentMessageBridgeImpl` 每次 content/error 推送都构造新的 `ChatMessageVO` 快照，不复用正在累积的可变对象，避免异步序列化观察到后续突变。
8. **完成语义统一**：`streamFinalResponse` 的 `onComplete`、`onError`、总超时/中断都会回写数据库并发布 `AI_DONE`；异常/无模型兜底 assistant 消息也会发布 `AI_DONE`；`streamDecisionResponse(...)` 只在纯文本 finalize 分支发布 `AI_DONE`，tool-call 分支只 rollback 不提前结束 turn。
9. **前端连续性**：`AgentChatView` 按 `id` upsert 合并同一 assistant 消息，`AI_DONE` 只清理 pending 状态，不关闭会话级 EventSource。

### 6.1 后端流式消息桥接、speculative passthrough 与 rollback

**`chatagent/bootstrap/.../agent/AgentMessageBridgeImpl.java`**

实现要求：
1. 推流前先创建一条空的 `ASSISTANT` 消息，拿到真实 `chatMessageId`。
2. 每次 `onContent` 都发送完整 `ChatMessageVO`，包含 `id/sessionId/turnId/role/content`，不能发送裸 delta 字符串。
3. 每次 `onContent` / `onError` 都应发送新的 `ChatMessageVO` 快照，不能把同一个可变 VO 对象反复交给 SSE。
4. `onComplete` / `onError` 必须更新数据库并发送 `AI_DONE`，但绝不能 close 会话级 SSE 连接。
5. `streamFinalResponse` 等待流完成时必须有 `stream-total-timeout-seconds` 上限，超时后 dispose 并发布中断提示。
6. `streamDecisionResponse(...)` 必须能在 `streamDecisionWithRouting(...)` 执行期间实时透传 provisional assistant 消息，并在纯文本分支 finalize、在 tool-call 分支删除消息并发送 `TURN_ROLLBACK`。
7. 前端必须按 `id` upsert，而不是同 id 直接丢弃。

### 6.2 HTTP 超时兜底

Spring AI 1.1.0 的 DeepSeek/ZhiPuAI `ConnectionProperties` / `ChatProperties` 只绑定 `api-key`、`base-url` 和 chat options，当前没有 provider-local 的 `client.read-timeout` 字段。因此当前兜底方式是：

```yaml
chat:
  routing:
    http-connect-timeout-seconds: 10
    http-read-timeout-seconds: 65
```

并由 `ChatModelHttpClientTimeoutConfig` 通过 `RestClientCustomizer` / `WebClientCustomizer` 注入底层 HTTP client timeout。这样比在 `spring.ai.deepseek.client.read-timeout` 下写一个未绑定字段更安全。

Phase 6 暂缓项：

1. **真实浏览器 EventSource 连续性测试未做**：代码语义上不 close SSE 连接，但仍需后续用浏览器或 e2e 覆盖连续两轮对话、错误中断、重连补偿等时序。
2. **最终回答流式 prompt 质量评估未做**：已补 session file / user profile 上下文，但没有做真实模型 A/B，确认 final-answer system prompt 是否需要进一步调优。
3. **更丰富错误恢复 UX 未做**：当前已经有基础的 `AI_ERROR` 错误条和 `Retry` / `Dismiss` 交互，但还没有分级错误卡片、自动恢复建议或按错误类型定制的重试策略。

Phase 6 验证记录：

1. `mvn -o -pl bootstrap -am -DskipTests compile` 通过。
2. `mvn -o -pl bootstrap -am -Dtest=AgentMessageBridgeImplTest,ChatAgentFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过，3 tests pass。
3. `npm.cmd run test -- AgentChatView.test.tsx` 通过，2 files / 5 tests pass，覆盖同 id upsert、`AI_ERROR` retry/dismiss 和 `TURN_ROLLBACK`。
4. `mvn -o -pl bootstrap -am '-Dtest=AgentThinkingEngineTest,ChatRoutingAdminControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过，7 tests pass，其中 `AgentThinkingEngineTest` 覆盖无工具直流、有工具 tool-call 分支和有工具纯文本 live passthrough finalize 分支。
5. 未运行真实浏览器 EventSource 冒烟测试；该项仍保留在 Phase 6 暂缓项中。

---

## Phase 7：运行时路由管理、provider 直连流与深度思考策略

**状态：代码完成。**

完成定义：管理员可以在线查看 routing 的 configured/effective 候选状态、registry 可达性和断路器快照，并能在不重启服务的前提下对 candidate 做 runtime override；同时 deep-thinking 候选与 provider-specific thinking 策略已在真实配置和 prompt 装配中生效，已知 provider 的流式路径也能下探到 raw SSE string parser，并通过 Actuator health 暴露路由可用性与断路器风险。

已完成闭环：

1. **运行时状态查询**：新增 `GET /api/admin/chat-routing/state`，返回 `defaultModel`、`deepThinkingModel`、HTTP/stream timeout、已注册模型列表、candidate effective 视图和 orphan override。
2. **运行时 override**：新增 `PUT /api/admin/chat-routing/candidates/override` 与 `DELETE /api/admin/chat-routing/candidates/{candidateId}/override`，支持在线调整 `enabled`、`priority`、`supportsThinking`、`thinkingStrategy`、`thinkingModel`。
3. **configured/effective 对照**：`ChatRoutingCandidateVO` 会同时返回 configured/effective 的 enabled、priority、supportsThinking、thinkingStrategy、thinkingModel，便于定位“配置值”和“运行时覆盖值”的差异。
4. **断路器快照暴露**：admin state 会携带 candidate 当前 `circuitState`、`consecutiveFailures`、`reopenInMs`、`halfOpenStartMs`、`probeGeneration`。
5. **深度思考默认策略**：`application.yaml` 默认将 `glm-4` 标记为 `deepThinkingModel`，并为其配置 `supports-thinking: true` + `thinking-strategy: ZHIPU_THINKING_FLAG`。
6. **策略装配生效**：`RoutingPromptFactory` 会根据 effective candidate 配置，为 Zhipu 注入 `thinking` flag，或为支持 `MODEL_OVERRIDE` 的候选注入 provider model override。
7. **provider raw SSE parser 生效**：已知 provider 会优先绕过 `ChatClient.stream()` 和 Spring AI provider chunk merger，直接消费 `DeepSeekApi` / `ZhiPuAiApi` 内部 WebClient 的 raw SSE string 流，并由我们自己规范化 SSE payload、解析 content / thinking / tool calls。
8. **基础错误状态生效**：SSE 新增 `AI_ERROR` 事件，流式中断和异步失败路径会显式下发错误状态供前端展示。
9. **Actuator 路由健康状态生效**：新增 `chatRouting` health indicator，并由 `chat.routing.observability` 控制无可路由候选、全部 OPEN、OPEN 比例和 orphan override 的健康状态分级。

Phase 7 暂缓/未做测试清单：

1. **真实浏览器 + JWT/SSE token 端到端测试未做**：当前已补完整 Spring Boot + JWT interceptor chain 集成测试，但尚未用真实浏览器验证 bearer header、SSE `access_token` query param、会话续连和前端 token 传递的端到端行为。

Phase 7 验证记录：

1. `mvn -o -pl bootstrap -am -DskipTests compile` 通过。
2. `mvn -o -pl infra '-Dtest=ModelSelectorTest,ProviderDirectStreamSupportTest,ReactiveStreamAdapterTest,ModelHealthStoreTest,RoutingLLMServiceTest' test` 通过，13 tests pass。
3. `mvn -o -pl bootstrap -am '-Dtest=ChatRoutingHealthIndicatorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过，5 tests pass。
4. `mvn -o -pl bootstrap -am '-Dtest=ChatRoutingPropertiesBindingTest,ChatRoutingAdminFacadeServiceImplTest,ChatRoutingHealthIndicatorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过，8 tests pass。
5. `mvn -o -pl bootstrap -am '-Dtest=AgentThinkingEngineTest,ChatRoutingAdminControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过，7 tests pass，其中 `ChatRoutingAdminControllerTest` 覆盖 state / override / clear override 的 admin HTTP 行为。
6. `mvn -o -pl bootstrap -am '-Dtest=ModelHealthStoreTest,ChatRoutingAdminControllerJwtIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过；`ModelHealthStoreTest` 共 4 tests pass，新增 HALF_OPEN 并发压力场景；`ChatRoutingAdminControllerJwtIntegrationTest` 共 5 tests pass，新增完整 Spring Boot + JWT interceptor chain HTTP 链路验证。

---

## Phase 8

已取消，相关文件与配置已从仓库移除。
