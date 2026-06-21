package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingLLMServiceTest {

    @Test
    void streamChatShouldUsePrimaryWhenFirstPacketProbeSucceeds() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(1);
        ModelSelector modelSelector = mock(ModelSelector.class);
        ProviderDirectStreamSupport providerDirectStreamSupport = mock(ProviderDirectStreamSupport.class);
        RoutingLLMService service = service(properties, modelSelector, providerDirectStreamSupport);

        ModelTarget primary = target("glm-5.2");
        ModelTarget fallback = target("deepseek-v4-flash");
        when(modelSelector.selectChatCandidates(false)).thenReturn(List.of(primary, fallback));

        RecordingDisposable primaryDisposable = new RecordingDisposable();
        when(providerDirectStreamSupport.submit(eq(primary), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("primary content");
                    return Optional.of(primaryDisposable);
                });

        RecordingStreamCallback callback = new RecordingStreamCallback();
        Disposable returned = service.streamChat(new Prompt(List.of(new UserMessage("hello"))), false, callback);

        assertThat(returned).isSameAs(primaryDisposable);
        assertThat(primaryDisposable.disposed).isFalse();
        assertThat(callback.contents).containsExactly("primary content");
        assertThat(callback.errors).isEmpty();
        verify(providerDirectStreamSupport, never())
                .submit(eq(fallback), any(Prompt.class), any(StreamCallback.class), eq(false));
    }

    @Test
    void streamChatShouldFallbackWhenPrimaryFailsFirstPacketProbe() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(1);
        ModelSelector modelSelector = mock(ModelSelector.class);
        ProviderDirectStreamSupport providerDirectStreamSupport = mock(ProviderDirectStreamSupport.class);
        RoutingLLMService service = service(properties, modelSelector, providerDirectStreamSupport);

        ModelTarget primary = target("glm-5.2");
        ModelTarget fallback = target("deepseek-v4-flash");
        when(modelSelector.selectChatCandidates(false)).thenReturn(List.of(primary, fallback));

        RecordingDisposable primaryDisposable = new RecordingDisposable();
        RecordingDisposable fallbackDisposable = new RecordingDisposable();
        when(providerDirectStreamSupport.submit(eq(primary), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onError(new RuntimeException("primary first packet failed"));
                    return Optional.of(primaryDisposable);
                });
        when(providerDirectStreamSupport.submit(eq(fallback), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("fallback content");
                    return Optional.of(fallbackDisposable);
                });

        RecordingStreamCallback callback = new RecordingStreamCallback();
        Disposable returned = service.streamChat(new Prompt(List.of(new UserMessage("hello"))), false, callback);

        assertThat(returned).isSameAs(fallbackDisposable);
        assertThat(primaryDisposable.disposed).isTrue();
        assertThat(fallbackDisposable.disposed).isFalse();
        assertThat(callback.contents).containsExactly("fallback content");
        assertThat(callback.errors).isEmpty();
        verify(providerDirectStreamSupport).submit(eq(primary), any(Prompt.class), any(StreamCallback.class), eq(false));
        verify(providerDirectStreamSupport).submit(eq(fallback), any(Prompt.class), any(StreamCallback.class), eq(false));
    }

    @Test
    void streamChatShouldFallbackWhenPrimaryTimesOutBeforeFirstPacket() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(0);
        ModelSelector modelSelector = mock(ModelSelector.class);
        ProviderDirectStreamSupport providerDirectStreamSupport = mock(ProviderDirectStreamSupport.class);
        RoutingLLMService service = service(properties, modelSelector, providerDirectStreamSupport);

        ModelTarget primary = target("glm-5.2");
        ModelTarget fallback = target("deepseek-v4-flash");
        when(modelSelector.selectChatCandidates(false)).thenReturn(List.of(primary, fallback));

        RecordingDisposable primaryDisposable = new RecordingDisposable();
        RecordingDisposable fallbackDisposable = new RecordingDisposable();
        when(providerDirectStreamSupport.submit(eq(primary), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenReturn(Optional.of(primaryDisposable));
        when(providerDirectStreamSupport.submit(eq(fallback), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("fallback after timeout");
                    return Optional.of(fallbackDisposable);
                });

        RecordingStreamCallback callback = new RecordingStreamCallback();
        Disposable returned = service.streamChat(new Prompt(List.of(new UserMessage("hello"))), false, callback);

        assertThat(returned).isSameAs(fallbackDisposable);
        assertThat(primaryDisposable.disposed).isTrue();
        assertThat(callback.contents).containsExactly("fallback after timeout");
        assertThat(callback.errors).isEmpty();
    }

    @Test
    void streamChatShouldFallbackWhenPrimaryCompletesWithoutContent() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(1);
        ModelSelector modelSelector = mock(ModelSelector.class);
        ProviderDirectStreamSupport providerDirectStreamSupport = mock(ProviderDirectStreamSupport.class);
        RoutingLLMService service = service(properties, modelSelector, providerDirectStreamSupport);

        ModelTarget primary = target("glm-5.2");
        ModelTarget fallback = target("deepseek-v4-flash");
        when(modelSelector.selectChatCandidates(false)).thenReturn(List.of(primary, fallback));

        RecordingDisposable primaryDisposable = new RecordingDisposable();
        RecordingDisposable fallbackDisposable = new RecordingDisposable();
        when(providerDirectStreamSupport.submit(eq(primary), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onComplete();
                    return Optional.of(primaryDisposable);
                });
        when(providerDirectStreamSupport.submit(eq(fallback), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("fallback after no content");
                    return Optional.of(fallbackDisposable);
                });

        RecordingStreamCallback callback = new RecordingStreamCallback();
        Disposable returned = service.streamChat(new Prompt(List.of(new UserMessage("hello"))), false, callback);

        assertThat(returned).isSameAs(fallbackDisposable);
        assertThat(primaryDisposable.disposed).isTrue();
        assertThat(callback.contents).containsExactly("fallback after no content");
        assertThat(callback.errors).isEmpty();
        assertThat(callback.completeCount).isZero();
    }

    @Test
    void streamDecisionShouldUseDecisionTimeoutAndDisposeHungStreamAfterFirstPacket() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(1);
        properties.setDecisionTotalTimeoutSeconds(1);
        ModelSelector modelSelector = mock(ModelSelector.class);
        ProviderDirectStreamSupport providerDirectStreamSupport = mock(ProviderDirectStreamSupport.class);
        RoutingLLMService service = service(properties, modelSelector, providerDirectStreamSupport);

        ModelTarget primary = target("glm-5.2");
        when(modelSelector.selectChatCandidates(false)).thenReturn(List.of(primary));

        RecordingDisposable primaryDisposable = new RecordingDisposable();
        when(providerDirectStreamSupport.submit(eq(primary), any(Prompt.class), any(StreamCallback.class), eq(false)))
                .thenAnswer(invocation -> {
                    StreamCallback callback = invocation.getArgument(2);
                    callback.onContent("partial decision");
                    return Optional.of(primaryDisposable);
                });

        assertThatThrownBy(() -> service.streamDecisionWithRouting(
                new Prompt(List.of(new UserMessage("hello"))), "system", List.of(), false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("流式决策超时: 1s");
        assertThat(primaryDisposable.disposed).isTrue();
    }

    private static RoutingLLMService service(ChatRoutingProperties properties,
                                             ModelSelector modelSelector,
                                             ProviderDirectStreamSupport providerDirectStreamSupport) {
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        RoutingPromptFactory promptFactory = new RoutingPromptFactory(capabilityResolver());
        return new RoutingLLMService(
                modelSelector,
                healthStore,
                properties,
                promptFactory,
                providerDirectStreamSupport);
    }

    private static ModelTarget target(String id) {
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setId(id);
        candidate.setSpringClientKey(id);
        candidate.setEnabled(true);
        candidate.setSupportsThinking(true);
        return new ModelTarget(id, candidate, mock(ChatClient.class));
    }

    private static ModelCapabilityResolver capabilityResolver() {
        ModelCapabilityResolver resolver = mock(ModelCapabilityResolver.class);
        when(resolver.supportsThinking(any())).thenReturn(true);
        return resolver;
    }

    private static final class RecordingDisposable implements Disposable {
        private boolean disposed;

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static final class RecordingStreamCallback implements StreamCallback {
        private final List<String> contents = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private int completeCount;

        @Override
        public void onContent(String content) {
            contents.add(content);
        }

        @Override
        public void onComplete() {
            completeCount++;
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }
}
