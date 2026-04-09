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

@Slf4j
@Service
public class RoutingLLMService implements LLMService {

    private final ModelSelector modelSelector;
    private final ModelHealthStore healthStore;
    private final ChatRoutingProperties properties;
    private final RoutingPromptFactory promptFactory;
    private final ProviderDirectStreamSupport providerDirectStreamSupport;
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
        if (routingMetrics != null) {
            this.routingMetrics = routingMetrics;
        }
    }

    // ========== 同步路径（Agent Loop think 用）==========

    @Override
    public ChatResponse chatWithRouting(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
        List<ModelTarget> targets = modelSelector.selectChatCandidates(false);
        if (targets.isEmpty()) {
            throw new RuntimeException("无可用大模型候选");
        }
        List<ToolCallback> safeTools = tools == null ? List.of() : tools;

        log.info("LLM_SYNC candidates selected: models={}, toolCount={}",
                targets.stream().map(ModelTarget::id).toList(),
                safeTools.size());
        Throwable lastError = null;
        boolean attempted = false;
        for (int i = 0; i < targets.size(); i++) {
            ModelTarget target = targets.get(i);
            // 延迟求值：在真正发起调用前才扣减断路器状态
            ModelHealthStore.CallPermit permit = healthStore.tryAcquire(target.id());
            if (!permit.allowed()) {
                log.info("LLM_SYNC candidate skipped by circuit: model={}, attempt={}/{}",
                        target.id(), i + 1, targets.size());
                routingMetrics.recordCircuitDecision(target.id(), "skipped_sync");
                continue;
            }

            long attemptStartNs = System.nanoTime();
            try {
                attempted = true;
                Prompt runtimePrompt = promptFactory.create(prompt, systemPrompt, safeTools, target, false);
                log.info("LLM_SYNC attempt started: model={}, attempt={}/{}, probeGeneration={}",
                        target.id(), i + 1, targets.size(), permit.generation());
                // 工具调用可能会耗时较长，依赖底层 read-timeout，移除人为短超时。
                ChatResponse response = target.chatClient()
                        .prompt(runtimePrompt)
                        .call()
                        .chatClientResponse()
                        .chatResponse();

                healthStore.markSuccess(target.id(), permit.generation());
                long durationMs = elapsedMs(attemptStartNs);
                log.info("LLM_SYNC attempt succeeded: model={}, attempt={}/{}, durationMs={}, hasToolCalls={}",
                        target.id(), i + 1, targets.size(), durationMs,
                        response != null && response.hasToolCalls());
                routingMetrics.recordAttempt("sync", target.id(), "success", durationMs, i + 1 < targets.size());
                return response;
            } catch (Exception e) {
                healthStore.markFailure(target.id(), permit.generation());
                lastError = e;
                long durationMs = elapsedMs(attemptStartNs);
                log.warn("LLM_SYNC attempt failed: model={}, attempt={}/{}, durationMs={}, fallbackAvailable={}, errorType={}, error={}",
                        target.id(), i + 1, targets.size(), durationMs, i + 1 < targets.size(),
                        e.getClass().getSimpleName(), e.getMessage());
                routingMetrics.recordAttempt("sync", target.id(), "failure", durationMs, i + 1 < targets.size());
            }
        }
        if (!attempted) {
            throw new RuntimeException("所有候选模型同步调用均被断路器跳过");
        }
        throw new RuntimeException("所有候选模型同步调用均失败", lastError);
    }

    @Override
    public BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
        return streamDecisionWithRouting(prompt, systemPrompt, tools, NoopStreamCallback.INSTANCE);
    }

    @Override
    public BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt,
                                                               String systemPrompt,
                                                               List<ToolCallback> tools,
                                                               StreamCallback callback) {
        StreamingDecisionCollector collector = new StreamingDecisionCollector();
        Disposable disposable = routeAndStream(
                prompt,
                systemPrompt,
                false,
                tools,
                new TeeStreamCallback(collector, callback == null ? NoopStreamCallback.INSTANCE : callback));

        try {
            long timeoutSeconds = Math.max(
                    properties.getStreamTotalTimeoutSeconds(),
                    properties.getFirstPacketTimeoutSeconds() + 5L);
            if (!collector.await(timeoutSeconds, TimeUnit.SECONDS)) {
                disposeQuietly(disposable);
                throw new RuntimeException("流式决策超时: " + timeoutSeconds + "s");
            }
            return collector.toResponse();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposeQuietly(disposable);
            throw new RuntimeException("等待流式决策被中断", e);
        }
    }

    // ========== 流式路径（真实回复探测用）==========

    @Override
    public Disposable streamChat(Prompt prompt, boolean deepThinking, StreamCallback callback) {
        return routeAndStream(prompt, null, deepThinking, List.of(), callback);
    }

    private Disposable routeAndStream(Prompt prompt,
                                      String systemPrompt,
                                      boolean deepThinking,
                                      List<ToolCallback> tools,
                                      StreamCallback callback) {
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
            ModelHealthStore.CallPermit permit = healthStore.tryAcquire(target.id());
            if (!permit.allowed()) {
                log.info("{} candidate skipped by circuit: model={}, attempt={}/{}",
                        label, target.id(), i + 1, targets.size());
                routingMetrics.recordCircuitDecision(target.id(), "skipped_stream");
                continue;
            }

            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
            ProbeBufferingCallback wrapper = new ProbeBufferingCallback(callback, awaiter, healthStore, target.id());

            long attemptStartNs = System.nanoTime();
            log.info("{} first-packet probe started: model={}, attempt={}/{}, probeGeneration={}",
                    label, target.id(), i + 1, targets.size(), permit.generation());
            Disposable disposable;
            try {
                attempted = true;
                Prompt runtimePrompt = promptFactory.create(prompt, systemPrompt, safeTools, target, deepThinking);
                disposable = providerDirectStreamSupport.submit(target, runtimePrompt, wrapper)
                        .orElseGet(() -> ReactiveStreamAdapter.submit(target.chatClient(), runtimePrompt, wrapper));
            } catch (Exception e) {
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
                wrapper.commit();
                healthStore.markSuccess(target.id(), permit.generation());
                routingMetrics.recordAttempt("stream_first_packet", target.id(), "success", latencyMs, i + 1 < targets.size());
                return disposable;
            }

            healthStore.markFailure(target.id(), permit.generation());
            disposeQuietly(disposable);
            lastError = result.getError();
            long latencyMs = elapsedMs(attemptStartNs);
            log.warn("{} first-packet probe failed: model={}, attempt={}/{}, result={}, latencyMs={}, fallbackAvailable={}, error={}",
                    label, target.id(), i + 1, targets.size(), result.getType(), latencyMs, i + 1 < targets.size(),
                    lastError == null ? "none" : lastError.getMessage());
            routingMetrics.recordAttempt("stream_first_packet", target.id(), "failure", latencyMs, i + 1 < targets.size());
        }

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
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
    }

    private static void disposeQuietly(Disposable disposable) {
        try {
            disposable.dispose();
        } catch (Exception ignored) {
            // Best-effort cleanup only; routing fallback should continue.
        }
    }

    // ========== 探针缓冲拦截器 ==========

    static final class ProbeBufferingCallback implements StreamCallback {
        private final StreamCallback downstream;
        private final FirstPacketAwaiter awaiter;
        private final ModelHealthStore healthStore;
        private final String modelId;
        
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
            awaiter.markContent();
            bufferOrDispatch(() -> downstream.onContent(content));
        }
        @Override public void onSignal() {
            awaiter.markContent();
            bufferOrDispatch(downstream::onSignal);
        }
        @Override public void onThinking(String content) {
            awaiter.markContent();
            bufferOrDispatch(() -> downstream.onThinking(content));
        }
        @Override public void onToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
            awaiter.markContent();
            bufferOrDispatch(() -> downstream.onToolCalls(toolCalls));
        }
        @Override public void onComplete() {
            awaiter.markComplete();
            bufferOrDispatch(downstream::onComplete);
        }
        @Override public void onError(Throwable t) {
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
                List<Runnable> snapshot = new ArrayList<>(buffers);
                buffers.clear();
                snapshot.forEach(Runnable::run);
            }
        }

        private void bufferOrDispatch(Runnable action) {
            synchronized (lock) {
                if (!committed) buffers.add(action);
                else action.run();
            }
        }
    }

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
                RoutingLLMService.log.warn("Stream callback dispatch failed: {}", ex.getMessage(), ex);
            }
        }
    }

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
                String key = toolCall.id() != null ? toolCall.id() : toolCall.name() + ":" + toolCall.arguments();
                toolCalls.put(key, toolCall);
            }
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            done.countDown();
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return done.await(timeout, unit);
        }

        BufferedStreamingResponse toResponse() {
            if (error != null) {
                throw new RuntimeException("流式决策失败", error);
            }
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content(content.toString())
                    .toolCalls(new ArrayList<>(toolCalls.values()))
                    .build();
            ChatResponse response = new ChatResponse(List.of(new Generation(assistantMessage)));
            return new BufferedStreamingResponse(response, List.copyOf(events));
        }
    }
}
