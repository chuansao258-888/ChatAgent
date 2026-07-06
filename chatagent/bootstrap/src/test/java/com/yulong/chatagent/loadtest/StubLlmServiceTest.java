package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.chat.routing.StreamCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubLlmServiceTest {

    @Test
    void streamChatShouldEmitContentThenCompleteAfterLatency() {
        LoadTestProperties props = new LoadTestProperties();
        props.setMockTtftMs(40L);
        props.setMockStreamTotalMs(40L);
        StubLLMService stub = new StubLLMService(props);

        RecordingCallback callback = new RecordingCallback();
        long start = System.nanoTime();
        Disposable disposable = stub.streamChat(new Prompt("hello"), false, callback);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(disposable).isNotNull();
        assertThat(callback.content).isNotEmpty();
        assertThat(callback.completed).isTrue();
        assertThat(callback.error).isNull();
        // Content must not arrive before TTFT.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(props.getMockTtftMs());
        // Never returns a tool call → toolCalls stays empty.
        assertThat(callback.toolCalls).isEmpty();
    }

    @Test
    void streamDecisionShouldReturnCannedResponseWithoutToolCall() {
        LoadTestProperties props = new LoadTestProperties();
        props.setMockTtftMs(10L);
        StubLLMService stub = new StubLLMService(props);

        var response = stub.streamDecisionWithRouting(
                new Prompt("decide"), "system", java.util.List.of(), false);

        assertThat(response).isNotNull();
        assertThat(response.response().getResult().getOutput()).isInstanceOf(AssistantMessage.class);
        String text = response.response().getResult().getOutput().getText();
        assertThat(text).isNotBlank();
        // One CONTENT event recorded for replay.
        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).type())
                .isEqualTo(com.yulong.chatagent.chat.routing.BufferedStreamingResponse.EventType.CONTENT);
        // No tool calls in the assistant message → ReAct loop terminates.
        assertThat(response.response().getResult().getOutput().getToolCalls()).isNullOrEmpty();
    }

    @Test
    void shouldNotThrowOnEmptyPrompt() {
        LoadTestProperties props = new LoadTestProperties();
        props.setMockTtftMs(1L);
        props.setMockStreamTotalMs(1L);
        StubLLMService stub = new StubLLMService(props);

        RecordingCallback callback = new RecordingCallback();
        // Empty / null-content prompt must not throw.
        stub.streamChat(new Prompt(""), false, callback);
        assertThat(callback.completed).isTrue();
        assertThat(callback.error).isNull();
    }

    @Test
    void shouldImplementLlmService() {
        assertThat(new StubLLMService(new LoadTestProperties())).isInstanceOf(LLMService.class);
    }

    private static final class RecordingCallback implements StreamCallback {
        final List<String> content = new ArrayList<>();
        final List<List<AssistantMessage.ToolCall>> toolCalls = new ArrayList<>();
        volatile boolean completed;
        volatile Throwable error;

        @Override
        public void onContent(String c) {
            content.add(c);
        }

        @Override
        public void onToolCalls(List<AssistantMessage.ToolCall> calls) {
            toolCalls.add(calls);
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }
    }
}
