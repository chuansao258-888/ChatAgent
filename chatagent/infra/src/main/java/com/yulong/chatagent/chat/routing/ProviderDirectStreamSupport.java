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

    // SSE 规范里，模型服务端通常用 data: [DONE] 表示本次流式响应结束。
    // 后续解析 raw SSE 时会先识别这个标记，避免把 [DONE] 当成 JSON chunk 去反序列化。
    private static final String SSE_DONE = "[DONE]";

    // 这个 registry 保存 spring-client-key 到厂商原始 API 绑定的关系。
    // ProviderDirectStreamSupport 只在 registry 找得到 binding 时才走原始 SSE；
    // 找不到时会让外层回退到普通 ChatClient.stream()。
    private final ChatModelProviderRegistry providerRegistry;

    public ProviderDirectStreamSupport(ChatModelProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public Optional<Disposable> submit(ModelTarget target, Prompt prompt, StreamCallback callback) {
        // target/candidate 不完整时，当前模型无法定位到 spring-client-key，
        // 因此不能走厂商原始流，返回 empty 让外层走兜底流式路径。
        if (target == null || target.candidate() == null) {
            return Optional.empty();
        }
        // spring-client-key 是路由配置和底层 provider binding 的连接点。
        // 例如 deepseek-reasoner -> DeepSeekBinding，glm-5.1 -> ZhiPuAiBinding。
        Optional<ChatModelProviderRegistry.ProviderBinding> binding =
                providerRegistry.find(target.candidate().getSpringClientKey());
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        try {
            // 找到 binding 后，交给内部重载 submit 按具体厂商分发。
            // 返回 Optional.of 表示原始 SSE 已经成功提交。
            return Optional.of(submit(binding.get(), prompt, callback));
        } catch (ProviderDirectStreamUnavailableException e) {
            // 反射读取 Spring AI 内部字段/方法可能因为版本变化失败。
            // 这里不直接打断业务，而是返回 empty，让 RoutingLLMService 回退到 ChatClient.stream()。
            log.warn("Raw provider stream unavailable; falling back to ChatClient stream: modelKey={}, provider={}, reason={}",
                    binding.get().springClientKey(), binding.get().providerType(), e.getMessage());
            return Optional.empty();
        }
    }

    private Disposable submit(ChatModelProviderRegistry.ProviderBinding binding,
                              Prompt prompt,
                              StreamCallback callback) {
        // ProviderBinding 是 sealed interface，目前只允许 DeepSeekBinding 和 ZhiPuAiBinding。
        // 这里根据实际 binding 类型选择对应厂商的原始 SSE 实现。
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
        // 复用 Spring AI ChatModel 的内部请求构造逻辑：
        // 先把外部 Prompt 合并默认 options，再创建 stream=true 的 DeepSeek 请求体。
        Prompt requestPrompt = buildRequestPrompt(binding.chatModel(), prompt);
        DeepSeekApi.ChatCompletionRequest request = createRequest(binding.chatModel(), requestPrompt,
                DeepSeekApi.ChatCompletionRequest.class);
        // 复用 Spring AI DeepSeekApi 内部已经配置好的 WebClient。
        // 这个 WebClient 已包含 base-url、Authorization Bearer、Content-Type 等配置。
        WebClient webClient = readField(binding.api(), "webClient", WebClient.class);
        // DeepSeek 的 endpoint 可能根据 request 内的 prefix/beta 特性变化，
        // 所以这里调用 Spring AI 内部 getEndpoint(request)，而不是手写路径。
        String endpoint = invoke(binding.api(), "getEndpoint",
                new Class<?>[]{DeepSeekApi.ChatCompletionRequest.class}, String.class, request);
        // 原始 SSE 的工具调用参数可能分片返回，需要先累积，等 finish_reason 表示 tool_call 结束后再发给下游。
        RawToolCallAccumulator toolCalls = new RawToolCallAccumulator();
        // handle 是返回给外层的取消句柄；upstream 是后面 subscribe 返回的真实 WebClient 流句柄。
        RawSseStreamHandle handle = new RawSseStreamHandle(binding.springClientKey(), binding.providerType());
        // 取消感知包装器：如果首包超时后 handle 被 dispose，迟到的 token/error/complete 都不会继续转发。
        StreamCallback guardedCallback = new CancellationAwareStreamCallback(callback, handle);
        log.debug("Using raw DeepSeek SSE parser for modelKey={}, endpoint={}", binding.springClientKey(), endpoint);
        Disposable upstream = webClient.post()
                .uri(endpoint)
                .body(Mono.just(request), DeepSeekApi.ChatCompletionRequest.class)
                .retrieve()
                // 按字符串流持续接收原始 SSE 文本。一次 raw 可能包含一条或多条 SSE event。
                .bodyToFlux(String.class)
                // 当 raw 中出现 [DONE] 时，让整个 Flux 正常结束。
                // 具体 dispatch 内部还会再次判断 [DONE]，避免把它当 JSON 解析。
                .takeUntil(this::containsDoneSignal)
                .subscribe(
                        // 每收到一段 raw，就解析其中的 payload，并分发 content/thinking/tool_calls。
                        raw -> dispatchDeepSeekRawSse(raw, toolCalls, guardedCallback, binding.springClientKey()),
                        // HTTP/解析/连接错误走 onError。guardedCallback 会屏蔽已取消流的迟到错误。
                        guardedCallback::onError,
                        () -> {
                            // 流正常结束时，先把尚未 flush 的工具调用兜底发出，再通知完成。
                            toolCalls.flush(guardedCallback);
                            guardedCallback.onComplete();
                        }
                );
        // 把真实上游订阅挂到 handle 里。外层 dispose(handle) 时才能取消这个 upstream。
        handle.setUpstream(upstream);
        return handle;
    }

    private Disposable submitZhiPu(ChatModelProviderRegistry.ZhiPuAiBinding binding,
                                   Prompt prompt,
                                   StreamCallback callback) {
        // 智谱路径和 DeepSeek 一样，也复用 Spring AI 的请求构造逻辑来生成 stream=true 请求体。
        Prompt requestPrompt = buildRequestPrompt(binding.chatModel(), prompt);
        ZhiPuAiApi.ChatCompletionRequest request = createRequest(binding.chatModel(), requestPrompt,
                ZhiPuAiApi.ChatCompletionRequest.class);
        // 复用 Spring AI ZhiPuAiApi 内部 WebClient，保持 base-url、底层配置和普通 ChatClient 一致。
        WebClient webClient = readField(binding.api(), "webClient", WebClient.class);
        // 智谱的 chat completions 路径是固定字段 completionsPath，不需要像 DeepSeek 那样根据 request 计算 endpoint。
        String completionsPath = readField(binding.api(), "completionsPath", String.class);
        RawToolCallAccumulator toolCalls = new RawToolCallAccumulator();
        RawSseStreamHandle handle = new RawSseStreamHandle(binding.springClientKey(), binding.providerType());
        StreamCallback guardedCallback = new CancellationAwareStreamCallback(callback, handle);
        log.debug("Using raw ZhiPu SSE parser for modelKey={}, endpoint={}", binding.springClientKey(), completionsPath);
        Disposable upstream = webClient.post()
                .uri(completionsPath)
                // 智谱的 Authorization header 由 ZhiPuAiApi 内部 addDefaultHeadersIfMissing 补齐。
                // 这里用反射调用它，避免重新实现鉴权细节。
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
        // 一个 raw 字符串里可能包含多条 SSE event，因此先提取成 payload 列表再逐个处理。
        for (String payload : extractSsePayloads(raw)) {
            // [DONE] 是流结束标记，不是 JSON chunk，不能进入 parseRawChunk。
            if (SSE_DONE.equals(payload)) {
                break;
            }
            // 将 JSON payload 映射成 DeepSeek 的 ChatCompletionChunk，后续按对象字段读取 delta。
            DeepSeekApi.ChatCompletionChunk chunk = parseRawChunk(
                    payload, DeepSeekApi.ChatCompletionChunk.class,
                    ChatModelProviderRegistry.ProviderType.DEEPSEEK, modelKey);
            // 只要成功解析到一个有效 chunk，就通知首包等待器：模型已经开始响应。
            callback.onSignal();
            dispatchDeepSeekChunk(chunk, toolCalls, callback);
        }
    }

    private void dispatchZhiPuRawSse(String raw,
                                     RawToolCallAccumulator toolCalls,
                                     StreamCallback callback,
                                     String modelKey) {
        // 智谱 raw SSE 的处理流程和 DeepSeek 一致，只是 JSON 目标类型换成 ZhiPuAi 的 chunk。
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
        // choices 为空说明当前 chunk 没有可分发的增量内容。
        if (chunk == null || CollectionUtils.isEmpty(chunk.choices())) {
            return;
        }
        for (DeepSeekApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
            // delta 表示当前 SSE chunk 新增的内容，可能包含正文、思考内容或工具调用分片。
            DeepSeekApi.ChatCompletionMessage delta = choice.delta();
            if (delta == null) {
                continue;
            }
            // 正文内容可以直接向下游分片输出，由前端或 collector 自己拼接。
            if (StringUtils.hasText(delta.content())) {
                callback.onContent(delta.content());
            }
            // reasoning_content 作为 thinking 流单独分发，避免混入最终正文。
            if (StringUtils.hasText(delta.reasoningContent())) {
                callback.onThinking(delta.reasoningContent());
            }
            // tool_calls 可能被模型拆成多段返回，必须先 merge 到累加器里。
            if (!CollectionUtils.isEmpty(delta.toolCalls())) {
                delta.toolCalls().forEach(toolCall ->
                        toolCalls.merge(toolCall.index(),
                                toolCall.id(),
                                toolCall.type(),
                                toolCall.function() == null ? null : toolCall.function().name(),
                                toolCall.function() == null ? null : toolCall.function().arguments()));
            }
            // finish_reason 表示模型已经把工具调用请求生成完毕，此时再把累积的工具调用发给下游。
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
            // 智谱的 chunk 结构和 DeepSeek 类似，也通过 delta 表达当前增量。
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
                // 智谱这里没有使用 index 归组，传 null 后会由 id 或 activeKey 兜底区分工具调用分片。
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


    private Prompt buildRequestPrompt(Object chatModel, Prompt prompt) {
        // Spring AI 的 ChatModel 内部会把默认 options 和当前 Prompt 合并成真正请求用的 Prompt。
        // 该方法不是 public API，因此通过统一的 invoke 反射调用。
        return invoke(chatModel, "buildRequestPrompt", new Class<?>[]{Prompt.class}, Prompt.class, prompt);
    }

    private <T> T createRequest(Object chatModel, Prompt prompt, Class<T> requestType) {
        // 第二个 boolean 参数表示 stream=true。
        // 这里固定创建流式请求体，让厂商服务端按 SSE 方式返回。
        return invoke(chatModel, "createRequest",
                new Class<?>[]{Prompt.class, boolean.class}, requestType, prompt, true);
    }

    private <T> T invoke(Object target,
                         String methodName,
                         Class<?>[] parameterTypes,
                         Class<T> expectedType,
                         Object... args) {
        try {
            // methodName + parameterTypes 一起定位具体重载方法。
            // getDeclaredMethod 可以拿到当前类声明的非 public 方法。
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            // Spring AI 这些方法并非公开 API，设置 accessible 后才能反射调用。
            method.setAccessible(true);
            // 真实执行目标方法；反射返回 Object，再由 expectedType 做类型转换。
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
        // 专门表达“调用一个没有业务返回值的方法”。
        // 当前用于调用 ZhiPuAiApi.addDefaultHeadersIfMissing(headers) 来补鉴权头。
        invoke(target, methodName, parameterTypes, Void.class, args);
    }

    private <T> T readField(Object target, String fieldName, Class<T> expectedType) {
        try {
            // 从 Spring AI 内部对象读取私有字段，例如 webClient、completionsPath。
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return expectedType.cast(field.get(target));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ProviderDirectStreamUnavailableException("Failed to access provider field: " + fieldName, e);
        }
    }

    private boolean isToolCallFinish(Object finishReason) {
        // 不同厂商/SDK 对工具调用结束原因的枚举命名略有差异，这里兼容两种常见写法。
        return finishReason != null
                && ("TOOL_CALLS".equals(finishReason.toString()) || "TOOL_CALL".equals(finishReason.toString()));
    }

    private boolean containsDoneSignal(String raw) {
        // 给 Flux.takeUntil 使用：只要当前 raw 中包含 [DONE]，就让上游流正常完成。
        return extractSsePayloads(raw).stream().anyMatch(SSE_DONE::equals);
    }

    private List<String> extractSsePayloads(String raw) {
        // raw 是 WebClient 每次吐出的原始字符串块，不保证等于一条 SSE event。
        // 这里负责把 raw 清洗成真正的 payload 列表：JSON 字符串或 [DONE]。
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }

        // 统一换行符，后续只按 \n 拆行处理。
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String trimmedRaw = normalized.trim();
        // 兼容 WebClient 或测试里直接给出裸 JSON / 裸 [DONE] 的情况。
        if (!trimmedRaw.contains("\n") && (SSE_DONE.equals(trimmedRaw) || trimmedRaw.startsWith("{"))) {
            return List.of(trimmedRaw);
        }

        List<String> payloads = new ArrayList<>();
        StringBuilder eventData = new StringBuilder();

        for (String line : normalized.split("\n", -1)) {
            String trimmedLine = line.trim();
            // SSE 中空行表示一个 event 结束，此时把前面累积的 data 内容提交成 payload。
            if (trimmedLine.isEmpty()) {
                flushEventData(payloads, eventData);
                continue;
            }
            // ':' 开头是 SSE 注释/心跳行，不属于模型业务数据。
            if (trimmedLine.startsWith(":")) {
                continue;
            }

            int colon = line.indexOf(':');
            if (colon >= 0) {
                String field = line.substring(0, colon).trim();
                String value = line.substring(colon + 1);
                // SSE 规范中 "field: value" 的冒号后单个空格不属于真实 value。
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if ("data".equals(field)) {
                    // 同一个 SSE event 可以有多行 data，需要用换行拼成一个 payload。
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

            // 兜底兼容非标准行：如果这一行本身就是 JSON 或 [DONE]，也作为 payload 收集。
            if (SSE_DONE.equals(trimmedLine) || trimmedLine.startsWith("{")) {
                payloads.add(trimmedLine);
            }
        }

        // 最后一条 event 后面可能没有空行，所以循环结束后再 flush 一次。
        flushEventData(payloads, eventData);
        return payloads;
    }

    private void flushEventData(List<String> payloads, StringBuilder eventData) {
        // eventData 是当前 SSE event 已累积的 data 内容。
        // flush 后必须清空，避免下一条 event 接在上一条 payload 后面。
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
            // 将 JSON payload 反序列化成厂商的 ChatCompletionChunk 对象。
            // 这样后续可以用 delta.content()/toolCalls() 等类型化方法读取字段。
            return ModelOptionsUtils.jsonToObject(payload, chunkType);
        } catch (RuntimeException e) {
            // 不把完整 payload 打到异常消息里，避免日志泄露模型内容；只保留长度和定位信息。
            throw new RawSseParseException(providerType, modelKey, chunkType, payload, e);
        }
    }

    // 这个异常表示“原始 SSE 通道不可用”，外层会捕获并回退到普通 ChatClient 流。
    private static final class ProviderDirectStreamUnavailableException extends RuntimeException {
        private ProviderDirectStreamUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // 这个异常表示“厂商返回的 raw SSE payload 无法按预期 chunk 类型解析”。
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
        // cancelled 是本包装句柄自己的取消状态，供回调层判断是否要丢弃迟到事件。
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        // upstream 保存 WebClient.subscribe(...) 返回的真实上游订阅。
        // AtomicReference 保证 setUpstream/dispose 并发时只绑定一个上游。
        private final AtomicReference<Disposable> upstream = new AtomicReference<>();

        private RawSseStreamHandle(String modelKey, Object providerType) {
            this.modelKey = modelKey;
            this.providerType = String.valueOf(providerType);
        }

        void setUpstream(Disposable disposable) {
            if (disposable == null) {
                return;
            }
            // 只允许第一个 upstream 绑定成功；如果重复绑定，直接取消多余的上游订阅。
            if (!this.upstream.compareAndSet(null, disposable)) {
                disposable.dispose();
                return;
            }
            // 处理竞态：如果 handle 在 upstream 绑定前已经被取消，
            // 那么刚绑定进来的真实上游也要立刻取消。
            if (this.cancelled.get()) {
                disposable.dispose();
            }
        }

        boolean isCancelled() {
            return this.cancelled.get();
        }

        @Override
        public void dispose() {
            // compareAndSet 保证 dispose 幂等：多次调用只有第一次会真正执行取消。
            if (!this.cancelled.compareAndSet(false, true)) {
                return;
            }
            // 取出真实上游订阅并取消它，从而停止继续接收厂商 SSE。
            Disposable disposable = this.upstream.get();
            if (disposable != null) {
                disposable.dispose();
            }
            log.debug("Disposed raw SSE stream: modelKey={}, provider={}", this.modelKey, this.providerType);
        }

        @Override
        public boolean isDisposed() {
            Disposable disposable = this.upstream.get();
            // 只要本地取消标记为 true，或真实上游已经 disposed，就认为整个 handle 已结束。
            return this.cancelled.get() || (disposable != null && disposable.isDisposed());
        }
    }

    // 包装下游 StreamCallback，给每个回调事件都加一层取消检查。
    // 这样首包超时切换模型后，旧模型迟到的 token/error/complete 不会污染新模型输出。
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
        // key -> ToolCallDelta。LinkedHashMap 保留工具调用第一次出现的顺序。
        private final Map<String, ToolCallDelta> toolCalls = new LinkedHashMap<>();
        // 当后续分片缺少 index/id 时，使用最近一次活跃 key 继续拼接同一个工具调用。
        private String activeKey = "0";

        void merge(Integer index, String id, String type, String functionName, String arguments) {
            // 同一个工具调用的多个 SSE 分片会映射到同一个 key，从而合并到同一个 ToolCallDelta。
            String key = key(index, id);
            ToolCallDelta delta = toolCalls.computeIfAbsent(key, ignored -> new ToolCallDelta());
            delta.merge(id, type, functionName, arguments);
        }

        void flush(StreamCallback callback) {
            if (toolCalls.isEmpty()) {
                return;
            }
            // 将内部累计状态转成 Spring AI 标准 ToolCall，再一次性发给下游。
            List<AssistantMessage.ToolCall> snapshot = new ArrayList<>();
            toolCalls.values().forEach(delta -> snapshot.add(delta.toToolCall()));
            // flush 后清空，避免同一批工具调用被重复下发。
            toolCalls.clear();
            callback.onToolCalls(snapshot);
        }

        private String key(Integer index, String id) {
            // DeepSeek 优先提供 index，用 index 能稳定区分并行工具调用。
            if (index != null) {
                activeKey = "index:" + index;
                return activeKey;
            }
            // 没有 index 时退而用工具调用 id。
            if (StringUtils.hasText(id)) {
                activeKey = "id:" + id;
                return activeKey;
            }
            // 如果本片段连 index/id 都没有，就接到最近一次活跃工具调用后面。
            return activeKey;
        }
    }

    private static final class ToolCallDelta {
        // id/type/name 通常只在第一段 tool_call delta 中出现，后续分片可能只剩 arguments。
        private String id;
        private String type;
        private String name;
        // arguments 是流式分片，必须 append，不能简单覆盖。
        private final StringBuilder arguments = new StringBuilder();

        void merge(String id, String type, String name, String arguments) {
            // 有值才覆盖，避免后续 null/空字符串分片把第一段保存的元信息冲掉。
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
            // 转成 Spring AI 标准工具调用对象，供下游 Agent/collector 使用。
            return new AssistantMessage.ToolCall(this.id, this.type, this.name, this.arguments.toString());
        }
    }
}
