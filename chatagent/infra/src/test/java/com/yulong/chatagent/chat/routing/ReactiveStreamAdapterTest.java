package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.zhipuai.ZhiPuAiAssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveStreamAdapterTest {

    @Test
    void shouldDispatchReasoningFromDeepSeekProviderMessage() {
        DeepSeekAssistantMessage message = new DeepSeekAssistantMessage.Builder()
                .content("final answer")
                .reasoningContent("deep reasoning")
                .build();
        RecordingCallback callback = new RecordingCallback();

        ReactiveStreamAdapter.extractAndDispatch(chatResponse(message), callback);

        assertThat(callback.contents).containsExactly("final answer");
        assertThat(callback.thinkings).containsExactly("deep reasoning");
    }

    @Test
    void shouldDispatchReasoningFromZhiPuProviderMessage() {
        ZhiPuAiAssistantMessage message = new ZhiPuAiAssistantMessage.Builder()
                .content("final answer")
                .reasoningContent("zhipu reasoning")
                .build();
        RecordingCallback callback = new RecordingCallback();

        ReactiveStreamAdapter.extractAndDispatch(chatResponse(message), callback);

        assertThat(callback.contents).containsExactly("final answer");
        assertThat(callback.thinkings).containsExactly("zhipu reasoning");
    }

    @Test
    void shouldFallbackToMetadataReasoningWhenProviderGetterIsUnavailable() {
        AssistantMessage message = new MetadataAssistantMessage(
                "final answer",
                Map.of("reasoning_content", "metadata reasoning"));
        RecordingCallback callback = new RecordingCallback();

        ReactiveStreamAdapter.extractAndDispatch(chatResponse(message), callback);

        assertThat(callback.contents).containsExactly("final answer");
        assertThat(callback.thinkings).containsExactly("metadata reasoning");
    }

    private static ChatResponse chatResponse(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static final class RecordingCallback implements StreamCallback {
        private final List<String> contents = new ArrayList<>();
        private final List<String> thinkings = new ArrayList<>();

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
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError("Unexpected streaming error", error);
        }
    }

    private static final class MetadataAssistantMessage extends AssistantMessage {
        private MetadataAssistantMessage(String content, Map<String, Object> metadata) {
            super(content, metadata, List.of(), List.of());
        }
    }
}
