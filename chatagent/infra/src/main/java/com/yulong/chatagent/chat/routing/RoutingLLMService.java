package com.yulong.chatagent.chat.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link LLMService} 的核心实现：带首包探测与熔断的多模型路由引擎。
 *
 * <p>对 ModelSelector 选出的候选依次尝试：先 tryAcquire 断路器许可，再用厂商原始 SSE 或
 * ChatClient.stream() 发起流式请求，靠 FirstPacketAwaiter 做首包探测；首包失败/超时则取消并切换下一个候选。
 * streamDecisionWithRouting 把流收集成 BufferedStreamingResponse，streamChat 则直接把事件推给回调。</p>
 */
@Slf4j
@Service
public class RoutingLLMService implements LLMService {

    // 负责把 YAML/override 里的候选模型筛选、排序并绑定成 ModelTarget。
    private final ModelSelector modelSelector;
    // 负责模型级熔断、HALF_OPEN 探针许可和健康状态更新。
    private final ModelHealthStore healthStore;
    // 路由超时、首包探测和健康阈值等配置。
    private final ChatRoutingProperties properties;
    // 根据目标模型重建 Prompt，注入工具和 thinking 参数。
    private final RoutingPromptFactory promptFactory;
    // 优先使用厂商原始 SSE 通道；不可用时回退到 ReactiveStreamAdapter。
    private final ProviderDirectStreamSupport providerDirectStreamSupport;
    // 指标组件可选注入；默认 no-op，避免指标系统缺失影响业务。
    private RoutingMetrics routingMetrics = RoutingMetrics.noop();

    public RoutingLLMService(ModelSelector modelSelector,
                             ModelHealthStore healthStore,
                             ChatRoutingProperties properties,
                             RoutingPromptFactory promptFactory,
                             ProviderDirectStreamSupport providerDirectStreamSupport) {
        this.modelSelector = modelSelector;
        this.healthStore = healthStore;
        this.properties = properties;
        this.promptFactory = promptFactory;
        this.providerDirectStreamSupport = providerDirectStreamSupport;
    }

    @Autowired(required = false)
    void setRoutingMetrics(RoutingMetrics routingMetrics) {
        // 允许没有 MeterRegistry 的环境正常启动。
        if (routingMetrics != null) {
            this.routingMetrics = routingMetrics;
        }
    }

    // ========== 同步路径（已停用）==========

    // chatWithRouting 是旧的同步路由入口：它会直接调用 ChatClient.call() 并返回 ChatResponse。
    // 当前 Agent runtime 已统一改成流式路径：
    // - 决策阶段走 streamDecisionWithRouting，边推流边由 StreamingDecisionCollector 收集 ChatResponse；
    // - 最终回答阶段走 streamChat，由调用方的 StreamCallback 落库和推送 SSE。
    // 因此同步入口先从接口和实现中注释掉，避免阅读 02-agent-runtime 主线时产生干扰。

    @Override
    public BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt, String systemPrompt,
                                                                List<ToolCallback> tools, boolean deepThinking) {
        // 没有外部 callback 时，只使用内部 collector 收集流式结果。
        return streamDecisionWithRouting(prompt, systemPrompt, tools, NoopStreamCallback.INSTANCE, deepThinking);
    }

    @Override
    public BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt,
                                                               String systemPrompt,
                                                               List<ToolCallback> tools,
                                                               StreamCallback callback,
                                                               boolean deepThinking) {
        // collector 把流式事件重新拼成 BufferedStreamingResponse。
        StreamingDecisionCollector collector = new StreamingDecisionCollector();
        // TeeStreamCallback 会把同一份流式事件同时发给 collector 和外部 callback。
        Disposable disposable = routeAndStream(
                prompt,
                systemPrompt,
                deepThinking,
                tools,
                new TeeStreamCallback(collector, callback == null ? NoopStreamCallback.INSTANCE : callback));

        try {
            long timeoutSeconds = Math.max(properties.getDecisionTotalTimeoutSeconds(), 1L);
            if (!collector.await(timeoutSeconds, TimeUnit.SECONDS)) {
                // collector 超时说明整条流没有正常 complete/error，取消上游避免泄露连接。
                disposeQuietly(disposable);
                throw new RuntimeException("流式决策超时: " + timeoutSeconds + "s");
            }
            return collector.toResponse();
        } catch (InterruptedException e) {
            // 恢复中断标记，并取消上游流。
            Thread.currentThread().interrupt();
            disposeQuietly(disposable);
            throw new RuntimeException("等待流式决策被中断", e);
        }
    }

    // ========== 流式路径（真实回复探测用）==========

    @Override
    public Disposable streamChat(Prompt prompt, boolean deepThinking, StreamCallback callback) {
        // 真实聊天流不额外传 systemPrompt/tools，直接走首包探测总控。
        return routeAndStream(prompt, null, deepThinking, List.of(), callback);
    }

    /**
     * 首包探测的总控方法。
     *
     * <p>它按候选顺序尝试模型：发起流式请求后等待首包；
     * 首包成功就 commit 缓冲事件并返回当前流的 Disposable；
     * 首包失败/超时就取消当前流、标记失败并尝试下一个候选。</p>
     */
    private Disposable routeAndStream(Prompt prompt,
                                      String systemPrompt,
                                      boolean deepThinking,
                                      List<ToolCallback> tools,
                                      StreamCallback callback) {
        // 先只做静态选择：enabled、thinking 能力、ChatClient 是否存在。
        // 健康状态会在真正准备发起调用前通过 tryAcquire 延迟判断。
        List<ModelTarget> targets = modelSelector.selectChatCandidates(deepThinking);
        if (targets.isEmpty()) {
            callback.onError(new RuntimeException("无可用大模型候选"));
            return () -> {};
        }
        List<ToolCallback> safeTools = tools == null ? List.of() : List.copyOf(tools);

        String label = "LLM_STREAM";
        log.info("{} candidates selected: models={}, deepThinking={}, firstPacketTimeoutSeconds={}, toolCount={}",
                label,
                targets.stream().map(ModelTarget::id).toList(),
                deepThinking,
                properties.getFirstPacketTimeoutSeconds(),
                safeTools.size());
        Throwable lastError = null;
        boolean attempted = false;

        for (int i = 0; i < targets.size(); i++) {
            ModelTarget target = targets.get(i);
            // 在真正调用模型之前才申请断路器许可，避免批量消耗 HALF_OPEN 探针名额。
            ModelHealthStore.CallPermit permit = healthStore.tryAcquire(target.id());
            if (!permit.allowed()) {
                log.info("{} candidate skipped by circuit: model={}, attempt={}/{}",
                        label, target.id(), i + 1, targets.size());
                routingMetrics.recordCircuitDecision(target.id(), "skipped_stream");
                continue;
            }

            // awaiter 只负责等待首个有效事件；wrapper 负责拦截回调、缓存首包前事件并通知 awaiter。
            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
            ProbeBufferingCallback wrapper = new ProbeBufferingCallback(callback, awaiter, healthStore, target.id());

            long attemptStartNs = System.nanoTime();
            log.info("{} first-packet probe started: model={}, attempt={}/{}, probeGeneration={}",
                    label, target.id(), i + 1, targets.size(), permit.generation());
            Disposable disposable;
            try {
                attempted = true;
                // 每个候选模型都要重新生成 Prompt，避免厂商 options/tools/thinking 混用。
                Prompt runtimePrompt = promptFactory.create(prompt, systemPrompt, safeTools, target, deepThinking);
                // 优先尝试原始厂商 SSE；如果 provider binding 不存在或不可用，就回退到 ChatClient.stream()。
                disposable = providerDirectStreamSupport.submit(target, runtimePrompt, wrapper, deepThinking)
                        .orElseGet(() -> ReactiveStreamAdapter.submit(target.chatClient(), runtimePrompt, wrapper));
            } catch (Exception e) {
                // 提交流式请求本身失败，还没进入首包等待，也要记一次失败并切下一个候选。
                healthStore.markFailure(target.id(), permit.generation());
                lastError = e;
                long latencyMs = elapsedMs(attemptStartNs);
                log.warn("{} stream submission failed: model={}, attempt={}/{}, latencyMs={}, fallbackAvailable={}, errorType={}, error={}",
                        label, target.id(), i + 1, targets.size(), latencyMs, i + 1 < targets.size(),
                        e.getClass().getSimpleName(), e.getMessage());
                routingMetrics.recordAttempt("stream_first_packet", target.id(), "submit_failure", latencyMs, i + 1 < targets.size());
                continue;
            }

            FirstPacketAwaiter.Result result;
            try {
                // 等待首包：wrapper 收到 onSignal/onContent/onThinking/onToolCalls 会唤醒 awaiter。
                result = awaiter.await(properties.getFirstPacketTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                disposeQuietly(disposable);
                callback.onError(new RuntimeException("等待首包被中断", e));
                return () -> {};
            }

            if (result.isSuccess()) {
                long latencyMs = elapsedMs(attemptStartNs);
                log.info("{} first-packet probe succeeded: model={}, attempt={}/{}, latencyMs={}",
                        label, target.id(), i + 1, targets.size(), latencyMs);
                // 首包成功后才把 wrapper 缓存的事件真正转发给下游。
                // 这样失败候选在探测期间吐出的内容不会提前污染前端/collector。
                wrapper.commit();
                healthStore.markSuccess(target.id(), permit.generation());
                routingMetrics.recordAttempt("stream_first_packet", target.id(), "success", latencyMs, i + 1 < targets.size());
                // 返回当前流的 Disposable，后续由调用方控制取消。
                return disposable;
            }

            // 首包失败/超时/无内容：标记失败、取消当前流，再尝试下一个候选。
            healthStore.markFailure(target.id(), permit.generation());
            disposeQuietly(disposable);
            lastError = result.getError();
            long latencyMs = elapsedMs(attemptStartNs);
            log.warn("{} first-packet probe failed: model={}, attempt={}/{}, result={}, latencyMs={}, fallbackAvailable={}, error={}",
                    label, target.id(), i + 1, targets.size(), result.getType(), latencyMs, i + 1 < targets.size(),
                lastError == null ? "none" : lastError.getMessage());
            routingMetrics.recordAttempt("stream_first_packet", target.id(), "failure", latencyMs, i + 1 < targets.size());
        }

        // 能走到这里，说明所有候选都没通过首包探测或都被断路器跳过。
        log.error("{} all candidates failed: models={}",
                label, targets.stream().map(ModelTarget::id).toList());
        if (!attempted) {
            callback.onError(new RuntimeException("大模型服务全线降级失败：候选均被断路器跳过"));
        } else {
            callback.onError(new RuntimeException("大模型服务全线降级失败", lastError));
        }
        return () -> {};
    }

    private static long elapsedMs(long startNs) {
        // 统一把纳秒起点转换成毫秒耗时，用于日志和 metrics。
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
    }

    private static void disposeQuietly(Disposable disposable) {
        try {
            // Disposable 代表当前上游流；失败候选必须尽量取消，避免继续输出迟到内容。
            disposable.dispose();
        } catch (Exception ignored) {
            // Best-effort cleanup only; routing fallback should continue.
        }
    }

    // ========== 探针缓冲拦截器 ==========

    /**
     * 首包探测期间的缓冲回调。
     *
     * <p>在某个候选模型尚未被确认成功前，它收到的所有流式事件先进入 buffers。
     * 等首包探测成功后 commit() 才统一转发给下游；如果探测失败，该 wrapper 会随上游取消而被丢弃。</p>
     */
    static final class ProbeBufferingCallback implements StreamCallback {
        private final StreamCallback downstream;
        private final FirstPacketAwaiter awaiter;
        private final ModelHealthStore healthStore;
        private final String modelId;

        // buffers 和 committed 由不同回调线程/routeAndStream 等待线程共同访问，需要同步保护。
        private final Object lock = new Object();
        private final List<Runnable> buffers = new ArrayList<>();
        private boolean committed = false;

        ProbeBufferingCallback(StreamCallback downstream, FirstPacketAwaiter awaiter, ModelHealthStore healthStore, String modelId) {
            this.downstream = downstream;
            this.awaiter = awaiter;
            this.healthStore = healthStore;
            this.modelId = modelId;
        }

        @Override public void onContent(String content) {
            // 正文内容本身就是有效首包。
            awaiter.markContent();
            bufferOrDispatch(() -> downstream.onContent(content));
        }
        @Override public void onSignal() {
            // 原始 SSE chunk 可能没有正文，但只要是有效 chunk，也能证明模型已响应。
            awaiter.markContent();
            bufferOrDispatch(downstream::onSignal);
        }
        @Override public void onThinking(String content) {
            // 推理内容同样算有效首包。
            awaiter.markContent();
            bufferOrDispatch(() -> downstream.onThinking(content));
        }
        @Override public void onToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
            // 工具调用出现说明模型已经开始返回有效语义事件。
            awaiter.markContent();
            bufferOrDispatch(() -> downstream.onToolCalls(toolCalls));
        }
        @Override public void onComplete() {
            // 首包前直接 complete 会唤醒 awaiter，但因为没有 markContent，会被识别为 NO_CONTENT。
            awaiter.markComplete();
            bufferOrDispatch(downstream::onComplete);
        }
        @Override public void onError(Throwable t) {
            // 首包阶段错误会唤醒 awaiter；若该模型已经 commit，错误也会继续传给下游。
            awaiter.markError(t);
            bufferOrDispatch(() -> {
                healthStore.markFailure(modelId);
                downstream.onError(t);
            });
        }

        void commit() {
            synchronized (lock) {
                if (committed) return;
                committed = true;
                // commit 时把首包探测期间缓存的事件按原顺序刷给下游。
                List<Runnable> snapshot = new ArrayList<>(buffers);
                buffers.clear();
                snapshot.forEach(Runnable::run);
            }
        }

        private void bufferOrDispatch(Runnable action) {
            synchronized (lock) {
                // 未 commit 前只缓存；commit 后的新事件直接向下游转发。
                if (!committed) buffers.add(action);
                else action.run();
            }
        }
    }

    /** 空回调：调用方不关心外部流式事件时使用。 */
    enum NoopStreamCallback implements StreamCallback {
        INSTANCE;

        @Override
        public void onContent(String content) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable error) {
        }
    }

    /**
     * 双写回调。
     *
     * <p>streamDecisionWithRouting 需要一边收集成 BufferedStreamingResponse，
     * 一边把事件转发给外部 callback，因此用 Tee 同时分发给 primary/secondary。</p>
     */
    static final class TeeStreamCallback implements StreamCallback {
        private final StreamCallback primary;
        private final StreamCallback secondary;

        TeeStreamCallback(StreamCallback primary, StreamCallback secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public void onSignal() {
            invoke(primary::onSignal);
            invoke(secondary::onSignal);
        }

        @Override
        public void onContent(String content) {
            invoke(() -> primary.onContent(content));
            invoke(() -> secondary.onContent(content));
        }

        @Override
        public void onThinking(String content) {
            invoke(() -> primary.onThinking(content));
            invoke(() -> secondary.onThinking(content));
        }

        @Override
        public void onToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
            invoke(() -> primary.onToolCalls(toolCalls));
            invoke(() -> secondary.onToolCalls(toolCalls));
        }

        @Override
        public void onComplete() {
            invoke(primary::onComplete);
            invoke(secondary::onComplete);
        }

        @Override
        public void onError(Throwable error) {
            invoke(() -> primary.onError(error));
            invoke(() -> secondary.onError(error));
        }

        private void invoke(Runnable action) {
            try {
                action.run();
            } catch (Exception ex) {
                // 下游 callback 异常不能打断另一个 callback，也不能影响上游路由清理。
                RoutingLLMService.log.warn("Stream callback dispatch failed: {}", ex.getMessage(), ex);
            }
        }
    }

    /**
     * 把流式事件收集成同步响应的 collector。
     *
     * <p>content 会拼成 AssistantMessage.content；thinking 作为事件保留；
     * toolCalls 用 id 或内容指纹去重后放进最终 AssistantMessage。</p>
     */
    static final class StreamingDecisionCollector implements StreamCallback {
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<BufferedStreamingResponse.BufferedStreamEvent> events = new ArrayList<>();
        private final StringBuilder content = new StringBuilder();
        private final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
        private Throwable error;

        @Override
        public void onContent(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            // 正文需要拼成最终 ChatResponse 的 assistant content。
            content.append(chunk);
            events.add(new BufferedStreamingResponse.BufferedStreamEvent(
                    BufferedStreamingResponse.EventType.CONTENT,
                    chunk));
        }

        @Override
        public void onThinking(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            // thinking 不进入最终正文，只作为可回放事件保存。
            events.add(new BufferedStreamingResponse.BufferedStreamEvent(
                    BufferedStreamingResponse.EventType.THINKING,
                    chunk));
        }

        @Override
        public void onToolCalls(List<AssistantMessage.ToolCall> incomingToolCalls) {
            if (incomingToolCalls == null || incomingToolCalls.isEmpty()) {
                return;
            }
            for (AssistantMessage.ToolCall toolCall : incomingToolCalls) {
                // 优先按 id 去重；没有 id 时用 name+arguments 构造稳定 key。
                String key = toolCall.id() != null ? toolCall.id() : toolCall.name() + ":" + toolCall.arguments();
                toolCalls.put(key, toolCall);
            }
        }

        @Override
        public void onComplete() {
            // complete/error 都释放等待线程；区别在 toResponse 时是否抛异常。
            done.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            done.countDown();
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            // 等待整个流结束，而不是等待首包。
            return done.await(timeout, unit);
        }

        BufferedStreamingResponse toResponse() {
            if (error != null) {
                throw new RuntimeException("流式决策失败", error);
            }
            // 将收集到的正文和工具调用包装成 Spring AI ChatResponse。
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content(content.toString())
                    .toolCalls(new ArrayList<>(toolCalls.values()))
                    .build();
            ChatResponse response = new ChatResponse(List.of(new Generation(assistantMessage)));
            return new BufferedStreamingResponse(response, List.copyOf(events));
        }
    }
}
