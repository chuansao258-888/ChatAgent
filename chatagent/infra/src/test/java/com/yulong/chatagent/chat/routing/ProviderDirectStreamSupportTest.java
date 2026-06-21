package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderDirectStreamSupportTest {

    @Test
    void shouldControlDeepSeekThinkingFromEffectiveModel() {
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setThinkingStrategy("MODEL_OVERRIDE");
        candidate.setThinkingModel("deepseek-v4-pro");
        DeepSeekApi.ChatCompletionRequest requestWithProModel =
                new DeepSeekApi.ChatCompletionRequest(List.of(), "deepseek-v4-pro", null, true);

        Map<String, Object> normalBody = ProviderDirectStreamSupport.buildDeepSeekRequestBody(
                requestWithProModel, candidate, false);
        Map<String, Object> deepThinkBody = ProviderDirectStreamSupport.buildDeepSeekRequestBody(
                requestWithProModel, candidate, true);

        assertThat(normalBody).containsEntry("thinking", Map.of("type", "disabled"));
        assertThat(deepThinkBody).containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    void shouldNormalizePlainJsonDoneAndDataPrefixedSsePayloads() throws Exception {
        ProviderDirectStreamSupport support = new ProviderDirectStreamSupport(mock(ChatModelProviderRegistry.class));

        assertThat(extractSsePayloads(support, "{\"id\":\"plain\"}"))
                .containsExactly("{\"id\":\"plain\"}");
        assertThat(extractSsePayloads(support, "[DONE]"))
                .containsExactly("[DONE]");
        assertThat(extractSsePayloads(support, """
                : keep-alive
                event: message
                id: chunk-1
                data: {"id":"from-data","choices":[]}

                data: [DONE]

                """))
                .containsExactly("{\"id\":\"from-data\",\"choices\":[]}", "[DONE]");
    }

    @Test
    void shouldSuppressLateCallbackEventsAfterRawSseDispose() throws Exception {
        RecordingCallback delegate = new RecordingCallback();
        Object handle = newRawSseStreamHandle();
        StreamCallback guardedCallback = newCancellationAwareCallback(delegate, handle);

        AtomicBoolean upstreamDisposed = new AtomicBoolean(false);
        setUpstream(handle, new Disposable() {
            @Override
            public void dispose() {
                upstreamDisposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return upstreamDisposed.get();
            }
        });

        guardedCallback.onSignal();
        guardedCallback.onContent("first");
        ((Disposable) handle).dispose();
        guardedCallback.onThinking("late-thinking");
        guardedCallback.onContent("late-content");
        guardedCallback.onComplete();
        guardedCallback.onError(new RuntimeException("late-error"));

        assertThat(upstreamDisposed).isTrue();
        assertThat(delegate.signals).isEqualTo(1);
        assertThat(delegate.contents).containsExactly("first");
        assertThat(delegate.thinkings).isEmpty();
        assertThat(delegate.completed).isFalse();
        assertThat(delegate.errors).isEmpty();
    }

    @Test
    void shouldFallbackWhenProviderPrivateApiIsUnavailable() {
        ChatModelProviderRegistry registry = mock(ChatModelProviderRegistry.class);
        DeepSeekChatModel chatModel = mock(DeepSeekChatModel.class);
        ChatModelProviderRegistry.DeepSeekBinding binding =
                new ChatModelProviderRegistry.DeepSeekBinding(
                        "deepseek-v4-flash",
                        chatModel,
                        mock(DeepSeekApi.class));
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setSpringClientKey("deepseek-v4-flash");

        when(registry.find("deepseek-v4-flash")).thenReturn(Optional.of(binding));

        ProviderDirectStreamSupport support = new ProviderDirectStreamSupport(registry);

        Optional<Disposable> result = support.submit(
                new ModelTarget("deepseek-v4-flash", candidate, null),
                new Prompt("hello"),
                new RecordingCallback(),
                false);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldNotLoseDeepSeekThinkingControlWhenProviderAdapterIsUnavailable() {
        ChatModelProviderRegistry registry = mock(ChatModelProviderRegistry.class);
        ChatModelProviderRegistry.DeepSeekBinding binding =
                new ChatModelProviderRegistry.DeepSeekBinding(
                        "deepseek-v4-flash",
                        mock(DeepSeekChatModel.class),
                        mock(DeepSeekApi.class));
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setSpringClientKey("deepseek-v4-flash");
        candidate.setThinkingStrategy("MODEL_OVERRIDE");
        candidate.setThinkingModel("deepseek-v4-pro");
        when(registry.find("deepseek-v4-flash")).thenReturn(Optional.of(binding));

        ProviderDirectStreamSupport support = new ProviderDirectStreamSupport(registry);

        assertThatThrownBy(() -> support.submit(
                new ModelTarget("deepseek-v4-flash", candidate, null),
                new Prompt("hello"),
                new RecordingCallback(),
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thinking control");
    }

    @Test
    void shouldNotLoseDeepSeekThinkingControlWhenProviderBindingIsMissing() {
        ChatModelProviderRegistry registry = mock(ChatModelProviderRegistry.class);
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setSpringClientKey("deepseek-v4-flash");
        candidate.setThinkingStrategy("MODEL_OVERRIDE");
        candidate.setThinkingModel("deepseek-v4-pro");
        when(registry.find("deepseek-v4-flash")).thenReturn(Optional.empty());

        ProviderDirectStreamSupport support = new ProviderDirectStreamSupport(registry);

        assertThatThrownBy(() -> support.submit(
                new ModelTarget("deepseek-v4-flash", candidate, null),
                new Prompt("hello"),
                new RecordingCallback(),
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider-direct stream binding");
    }

    @Test
    void shouldAddSafeContextToRawSseParseErrorsWithoutPayloadText() throws Exception {
        ProviderDirectStreamSupport support = new ProviderDirectStreamSupport(mock(ChatModelProviderRegistry.class));
        String sensitivePayload = "this-is-not-json-with-user-context";

        assertThatThrownBy(() -> invokeParseRawChunk(support, sensitivePayload))
                .hasMessageContaining("provider=DEEPSEEK")
                .hasMessageContaining("modelKey=deepseek-v4-flash")
                .hasMessageContaining("chunkType=ChatCompletionChunk")
                .hasMessageContaining("payloadLength=" + sensitivePayload.length())
                .hasMessageNotContaining(sensitivePayload);
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractSsePayloads(ProviderDirectStreamSupport support, String raw) throws Exception {
        Method method = ProviderDirectStreamSupport.class.getDeclaredMethod("extractSsePayloads", String.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(support, raw);
    }

    private static Object newRawSseStreamHandle() throws Exception {
        Class<?> handleType = Class.forName(
                "com.yulong.chatagent.chat.routing.ProviderDirectStreamSupport$RawSseStreamHandle");
        Constructor<?> constructor = handleType.getDeclaredConstructor(String.class, Object.class);
        constructor.setAccessible(true);
        return constructor.newInstance("deepseek-v4-flash", ChatModelProviderRegistry.ProviderType.DEEPSEEK);
    }

    private static StreamCallback newCancellationAwareCallback(StreamCallback delegate, Object handle) throws Exception {
        Class<?> callbackType = Class.forName(
                "com.yulong.chatagent.chat.routing.ProviderDirectStreamSupport$CancellationAwareStreamCallback");
        Class<?> handleType = Class.forName(
                "com.yulong.chatagent.chat.routing.ProviderDirectStreamSupport$RawSseStreamHandle");
        Constructor<?> constructor = callbackType.getDeclaredConstructor(StreamCallback.class, handleType);
        constructor.setAccessible(true);
        return (StreamCallback) constructor.newInstance(delegate, handle);
    }

    private static void setUpstream(Object handle, Disposable upstream) throws Exception {
        Method method = handle.getClass().getDeclaredMethod("setUpstream", Disposable.class);
        method.setAccessible(true);
        method.invoke(handle, upstream);
    }

    private static void invokeParseRawChunk(ProviderDirectStreamSupport support, String payload) throws Throwable {
        Method method = ProviderDirectStreamSupport.class.getDeclaredMethod(
                "parseRawChunk",
                String.class,
                Class.class,
                ChatModelProviderRegistry.ProviderType.class,
                String.class);
        method.setAccessible(true);
        try {
            method.invoke(
                    support,
                    payload,
                    DeepSeekApi.ChatCompletionChunk.class,
                    ChatModelProviderRegistry.ProviderType.DEEPSEEK,
                    "deepseek-v4-flash");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class RecordingCallback implements StreamCallback {
        private final List<String> contents = new ArrayList<>();
        private final List<String> thinkings = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private int signals;
        private boolean completed;

        @Override
        public void onSignal() {
            signals++;
        }

        @Override
        public void onContent(String content) {
            contents.add(content);
        }

        @Override
        public void onThinking(String content) {
            thinkings.add(content);
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }
}
