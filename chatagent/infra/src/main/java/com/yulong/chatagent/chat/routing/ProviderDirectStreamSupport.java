package com.yulong.chatagent.chat.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class ProviderDirectStreamSupport {

    private static final String SSE_DONE = "[DONE]";

    private final ChatModelProviderRegistry providerRegistry;

    public ProviderDirectStreamSupport(ChatModelProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public Optional<Disposable> submit(ModelTarget target, Prompt prompt, StreamCallback callback) {
        if (target == null || target.candidate() == null) {
            return Optional.empty();
        }
        Optional<ChatModelProviderRegistry.ProviderBinding> binding =
                providerRegistry.find(target.candidate().getSpringClientKey());
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(submit(binding.get(), prompt, callback));
        } catch (ProviderDirectStreamUnavailableException e) {
            log.warn("Raw provider stream unavailable; falling back to ChatClient stream: modelKey={}, provider={}, reason={}",
                    binding.get().springClientKey(), binding.get().providerType(), e.getMessage());
            return Optional.empty();
        }
    }

    private Disposable submit(ChatModelProviderRegistry.ProviderBinding binding,
                              Prompt prompt,
                              StreamCallback callback) {
        if (binding instanceof ChatModelProviderRegistry.DeepSeekBinding deepSeekBinding) {
            return submitDeepSeek(deepSeekBinding, prompt, callback);
        }
        if (binding instanceof ChatModelProviderRegistry.ZhiPuAiBinding zhiPuAiBinding) {
            return submitZhiPu(zhiPuAiBinding, prompt, callback);
        }
        throw new IllegalStateException("Unsupported provider binding: " + binding.providerType());
    }

    private Disposable submitDeepSeek(ChatModelProviderRegistry.DeepSeekBinding binding,
                                      Prompt prompt,
                                      StreamCallback callback) {
        Prompt requestPrompt = buildRequestPrompt(binding.chatModel(), prompt);
        DeepSeekApi.ChatCompletionRequest request = createRequest(binding.chatModel(), requestPrompt,
                DeepSeekApi.ChatCompletionRequest.class);
        WebClient webClient = readField(binding.api(), "webClient", WebClient.class);
        String endpoint = invoke(binding.api(), "getEndpoint",
                new Class<?>[]{DeepSeekApi.ChatCompletionRequest.class}, String.class, request);
        RawToolCallAccumulator toolCalls = new RawToolCallAccumulator();
        RawSseStreamHandle handle = new RawSseStreamHandle(binding.springClientKey(), binding.providerType());
        StreamCallback guardedCallback = new CancellationAwareStreamCallback(callback, handle);
        log.debug("Using raw DeepSeek SSE parser for modelKey={}, endpoint={}", binding.springClientKey(), endpoint);
        Disposable upstream = webClient.post()
                .uri(endpoint)
                .body(Mono.just(request), DeepSeekApi.ChatCompletionRequest.class)
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil(this::containsDoneSignal)
                .subscribe(
                        raw -> dispatchDeepSeekRawSse(raw, toolCalls, guardedCallback, binding.springClientKey()),
                        guardedCallback::onError,
                        () -> {
                            toolCalls.flush(guardedCallback);
                            guardedCallback.onComplete();
                        }
                );
        handle.setUpstream(upstream);
        return handle;
    }

    private Disposable submitZhiPu(ChatModelProviderRegistry.ZhiPuAiBinding binding,
                                   Prompt prompt,
                                   StreamCallback callback) {
        Prompt requestPrompt = buildRequestPrompt(binding.chatModel(), prompt);
        ZhiPuAiApi.ChatCompletionRequest request = createRequest(binding.chatModel(), requestPrompt,
                ZhiPuAiApi.ChatCompletionRequest.class);
        WebClient webClient = readField(binding.api(), "webClient", WebClient.class);
        String completionsPath = readField(binding.api(), "completionsPath", String.class);
        RawToolCallAccumulator toolCalls = new RawToolCallAccumulator();
        RawSseStreamHandle handle = new RawSseStreamHandle(binding.springClientKey(), binding.providerType());
        StreamCallback guardedCallback = new CancellationAwareStreamCallback(callback, handle);
        log.debug("Using raw ZhiPu SSE parser for modelKey={}, endpoint={}", binding.springClientKey(), completionsPath);
        Disposable upstream = webClient.post()
                .uri(completionsPath)
                .headers(headers -> invokeVoid(binding.api(), "addDefaultHeadersIfMissing",
                        new Class<?>[]{HttpHeaders.class}, headers))
                .body(Mono.just(request), ZhiPuAiApi.ChatCompletionRequest.class)
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil(this::containsDoneSignal)
                .subscribe(
                        raw -> dispatchZhiPuRawSse(raw, toolCalls, guardedCallback, binding.springClientKey()),
                        guardedCallback::onError,
                        () -> {
                            toolCalls.flush(guardedCallback);
                            guardedCallback.onComplete();
                        }
                );
        handle.setUpstream(upstream);
        return handle;
    }

    private void dispatchDeepSeekRawSse(String raw,
                                        RawToolCallAccumulator toolCalls,
                                        StreamCallback callback,
                                        String modelKey) {
        for (String payload : extractSsePayloads(raw)) {
            if (SSE_DONE.equals(payload)) {
                break;
            }
            DeepSeekApi.ChatCompletionChunk chunk = parseRawChunk(
                    payload, DeepSeekApi.ChatCompletionChunk.class,
                    ChatModelProviderRegistry.ProviderType.DEEPSEEK, modelKey);
            callback.onSignal();
            dispatchDeepSeekChunk(chunk, toolCalls, callback);
        }
    }

    private void dispatchZhiPuRawSse(String raw,
                                     RawToolCallAccumulator toolCalls,
                                     StreamCallback callback,
                                     String modelKey) {
        for (String payload : extractSsePayloads(raw)) {
            if (SSE_DONE.equals(payload)) {
                break;
            }
            ZhiPuAiApi.ChatCompletionChunk chunk = parseRawChunk(
                    payload, ZhiPuAiApi.ChatCompletionChunk.class,
                    ChatModelProviderRegistry.ProviderType.ZHIPU_AI, modelKey);
            callback.onSignal();
            dispatchZhiPuChunk(chunk, toolCalls, callback);
        }
    }

    private void dispatchDeepSeekChunk(DeepSeekApi.ChatCompletionChunk chunk,
                                       RawToolCallAccumulator toolCalls,
                                       StreamCallback callback) {
        if (chunk == null || CollectionUtils.isEmpty(chunk.choices())) {
            return;
        }
        for (DeepSeekApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
            DeepSeekApi.ChatCompletionMessage delta = choice.delta();
            if (delta == null) {
                continue;
            }
            if (StringUtils.hasText(delta.content())) {
                callback.onContent(delta.content());
            }
            if (StringUtils.hasText(delta.reasoningContent())) {
                callback.onThinking(delta.reasoningContent());
            }
            if (!CollectionUtils.isEmpty(delta.toolCalls())) {
                delta.toolCalls().forEach(toolCall ->
                        toolCalls.merge(toolCall.index(),
                                toolCall.id(),
                                toolCall.type(),
                                toolCall.function() == null ? null : toolCall.function().name(),
                                toolCall.function() == null ? null : toolCall.function().arguments()));
            }
            if (isToolCallFinish(choice.finishReason())) {
                toolCalls.flush(callback);
            }
        }
    }

    private void dispatchZhiPuChunk(ZhiPuAiApi.ChatCompletionChunk chunk,
                                    RawToolCallAccumulator toolCalls,
                                    StreamCallback callback) {
        if (chunk == null || CollectionUtils.isEmpty(chunk.choices())) {
            return;
        }
        for (ZhiPuAiApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
            ZhiPuAiApi.ChatCompletionMessage delta = choice.delta();
            if (delta == null) {
                continue;
            }
            if (StringUtils.hasText(delta.content())) {
                callback.onContent(delta.content());
            }
            if (StringUtils.hasText(delta.reasoningContent())) {
                callback.onThinking(delta.reasoningContent());
            }
            if (!CollectionUtils.isEmpty(delta.toolCalls())) {
                delta.toolCalls().forEach(toolCall ->
                        toolCalls.merge(null,
                                toolCall.id(),
                                toolCall.type(),
                                toolCall.function() == null ? null : toolCall.function().name(),
                                toolCall.function() == null ? null : toolCall.function().arguments()));
            }
            if (isToolCallFinish(choice.finishReason())) {
                toolCalls.flush(callback);
            }
        }
    }

    private AssistantMessage.ToolCall toAssistantToolCall(DeepSeekApi.ChatCompletionMessage.ToolCall toolCall) {
        String name = toolCall.function() == null ? null : toolCall.function().name();
        String arguments = toolCall.function() == null ? null : toolCall.function().arguments();
        return new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(), name, arguments);
    }

    private AssistantMessage.ToolCall toAssistantToolCall(ZhiPuAiApi.ChatCompletionMessage.ToolCall toolCall) {
        String name = toolCall.function() == null ? null : toolCall.function().name();
        String arguments = toolCall.function() == null ? null : toolCall.function().arguments();
        return new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(), name, arguments);
    }

    private Prompt buildRequestPrompt(Object chatModel, Prompt prompt) {
        return invoke(chatModel, "buildRequestPrompt", new Class<?>[]{Prompt.class}, Prompt.class, prompt);
    }

    private <T> T createRequest(Object chatModel, Prompt prompt, Class<T> requestType) {
        return invoke(chatModel, "createRequest",
                new Class<?>[]{Prompt.class, boolean.class}, requestType, prompt, true);
    }

    private <T> T invoke(Object target,
                         String methodName,
                         Class<?>[] parameterTypes,
                         Class<T> expectedType,
                         Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            Object value = method.invoke(target, args);
            return expectedType.cast(value);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ProviderDirectStreamUnavailableException("Failed to access provider stream method: " + methodName, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ProviderDirectStreamUnavailableException("Provider stream method failed: " + methodName, cause);
        }
    }

    private void invokeVoid(Object target,
                            String methodName,
                            Class<?>[] parameterTypes,
                            Object... args) {
        invoke(target, methodName, parameterTypes, Void.class, args);
    }

    private <T> T readField(Object target, String fieldName, Class<T> expectedType) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return expectedType.cast(field.get(target));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ProviderDirectStreamUnavailableException("Failed to access provider field: " + fieldName, e);
        }
    }

    private boolean isToolCallFinish(Object finishReason) {
        return finishReason != null
                && ("TOOL_CALLS".equals(finishReason.toString()) || "TOOL_CALL".equals(finishReason.toString()));
    }

    private boolean containsDoneSignal(String raw) {
        return extractSsePayloads(raw).stream().anyMatch(SSE_DONE::equals);
    }

    private List<String> extractSsePayloads(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }

        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String trimmedRaw = normalized.trim();
        if (!trimmedRaw.contains("\n") && (SSE_DONE.equals(trimmedRaw) || trimmedRaw.startsWith("{"))) {
            return List.of(trimmedRaw);
        }

        List<String> payloads = new ArrayList<>();
        StringBuilder eventData = new StringBuilder();

        for (String line : normalized.split("\n", -1)) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                flushEventData(payloads, eventData);
                continue;
            }
            if (trimmedLine.startsWith(":")) {
                continue;
            }

            int colon = line.indexOf(':');
            if (colon >= 0) {
                String field = line.substring(0, colon).trim();
                String value = line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if ("data".equals(field)) {
                    if (!eventData.isEmpty()) {
                        eventData.append('\n');
                    }
                    eventData.append(value);
                    continue;
                }
                if ("event".equals(field) || "id".equals(field) || "retry".equals(field)) {
                    continue;
                }
            }

            if (SSE_DONE.equals(trimmedLine) || trimmedLine.startsWith("{")) {
                payloads.add(trimmedLine);
            }
        }

        flushEventData(payloads, eventData);
        return payloads;
    }

    private void flushEventData(List<String> payloads, StringBuilder eventData) {
        if (eventData.isEmpty()) {
            return;
        }
        String payload = eventData.toString().trim();
        eventData.setLength(0);
        if (StringUtils.hasText(payload)) {
            payloads.add(payload);
        }
    }

    private <T> T parseRawChunk(String payload,
                                Class<T> chunkType,
                                ChatModelProviderRegistry.ProviderType providerType,
                                String modelKey) {
        try {
            return ModelOptionsUtils.jsonToObject(payload, chunkType);
        } catch (RuntimeException e) {
            throw new RawSseParseException(providerType, modelKey, chunkType, payload, e);
        }
    }

    private static final class ProviderDirectStreamUnavailableException extends RuntimeException {
        private ProviderDirectStreamUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class RawSseParseException extends RuntimeException {
        private RawSseParseException(ChatModelProviderRegistry.ProviderType providerType,
                                     String modelKey,
                                     Class<?> chunkType,
                                     String payload,
                                     Throwable cause) {
            super("Failed to parse raw SSE payload: provider=" + providerType
                    + ", modelKey=" + modelKey
                    + ", chunkType=" + chunkType.getSimpleName()
                    + ", payloadLength=" + (payload == null ? 0 : payload.length()), cause);
        }
    }

    private static final class RawSseStreamHandle implements Disposable {
        private final String modelKey;
        private final String providerType;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Disposable> upstream = new AtomicReference<>();

        private RawSseStreamHandle(String modelKey, Object providerType) {
            this.modelKey = modelKey;
            this.providerType = String.valueOf(providerType);
        }

        void setUpstream(Disposable disposable) {
            if (disposable == null) {
                return;
            }
            if (!this.upstream.compareAndSet(null, disposable)) {
                disposable.dispose();
                return;
            }
            if (this.cancelled.get()) {
                disposable.dispose();
            }
        }

        boolean isCancelled() {
            return this.cancelled.get();
        }

        @Override
        public void dispose() {
            if (!this.cancelled.compareAndSet(false, true)) {
                return;
            }
            Disposable disposable = this.upstream.get();
            if (disposable != null) {
                disposable.dispose();
            }
            log.debug("Disposed raw SSE stream: modelKey={}, provider={}", this.modelKey, this.providerType);
        }

        @Override
        public boolean isDisposed() {
            Disposable disposable = this.upstream.get();
            return this.cancelled.get() || (disposable != null && disposable.isDisposed());
        }
    }

    private static final class CancellationAwareStreamCallback implements StreamCallback {
        private final StreamCallback delegate;
        private final RawSseStreamHandle handle;

        private CancellationAwareStreamCallback(StreamCallback delegate, RawSseStreamHandle handle) {
            this.delegate = delegate;
            this.handle = handle;
        }

        @Override
        public void onSignal() {
            if (!this.handle.isCancelled()) {
                this.delegate.onSignal();
            }
        }

        @Override
        public void onContent(String content) {
            if (!this.handle.isCancelled()) {
                this.delegate.onContent(content);
            }
        }

        @Override
        public void onThinking(String content) {
            if (!this.handle.isCancelled()) {
                this.delegate.onThinking(content);
            }
        }

        @Override
        public void onToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
            if (!this.handle.isCancelled()) {
                this.delegate.onToolCalls(toolCalls);
            }
        }

        @Override
        public void onComplete() {
            if (!this.handle.isCancelled()) {
                this.delegate.onComplete();
            }
        }

        @Override
        public void onError(Throwable error) {
            if (!this.handle.isCancelled()) {
                this.delegate.onError(error);
            }
        }
    }

    private static final class RawToolCallAccumulator {
        private final Map<String, ToolCallDelta> toolCalls = new LinkedHashMap<>();
        private String activeKey = "0";

        void merge(Integer index, String id, String type, String functionName, String arguments) {
            String key = key(index, id);
            ToolCallDelta delta = toolCalls.computeIfAbsent(key, ignored -> new ToolCallDelta());
            delta.merge(id, type, functionName, arguments);
        }

        void flush(StreamCallback callback) {
            if (toolCalls.isEmpty()) {
                return;
            }
            List<AssistantMessage.ToolCall> snapshot = new ArrayList<>();
            toolCalls.values().forEach(delta -> snapshot.add(delta.toToolCall()));
            toolCalls.clear();
            callback.onToolCalls(snapshot);
        }

        private String key(Integer index, String id) {
            if (index != null) {
                activeKey = "index:" + index;
                return activeKey;
            }
            if (StringUtils.hasText(id)) {
                activeKey = "id:" + id;
                return activeKey;
            }
            return activeKey;
        }
    }

    private static final class ToolCallDelta {
        private String id;
        private String type;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(String id, String type, String name, String arguments) {
            if (StringUtils.hasText(id)) {
                this.id = id;
            }
            if (StringUtils.hasText(type)) {
                this.type = type;
            }
            if (StringUtils.hasText(name)) {
                this.name = name;
            }
            if (arguments != null) {
                this.arguments.append(arguments);
            }
        }

        AssistantMessage.ToolCall toToolCall() {
            return new AssistantMessage.ToolCall(this.id, this.type, this.name, this.arguments.toString());
        }
    }
}
