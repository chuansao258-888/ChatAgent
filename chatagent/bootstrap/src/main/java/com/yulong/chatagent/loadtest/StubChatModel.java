package com.yulong.chatagent.loadtest;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Minimal {@link ChatModel} stub returning canned content after a fixed latency.
 *
 * <p>Used by {@link StubChatModelRouter} to serve auxiliary callers (intent,
 * memory, summarizer, reranker, ingestion) that resolve a {@code ChatClient} by
 * name and call it directly, bypassing {@code RoutingLLMService}. Only the two
 * abstract methods ({@link #call(Prompt)} and {@link #stream(Prompt)}) are
 * implemented; the default overloads resolve automatically.</p>
 */
public class StubChatModel implements ChatModel {

    private static final String CANNED_ANSWER = "[stub] ok";

    private final long ttftMs;
    private final long streamTotalMs;

    public StubChatModel(long ttftMs, long streamTotalMs) {
        this.ttftMs = ttftMs;
        this.streamTotalMs = streamTotalMs;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        sleepQuietly(ttftMs);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(CANNED_ANSWER))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Emit a canned chunk after TTFT, then complete after the streaming
        // window so the total latency is TTFT + streamTotal (matching the
        // StubLLMService round-trip semantics).
        long totalDelay = Math.max(1L, ttftMs + streamTotalMs);
        return Flux.just(buildChunk())
                .delayElements(java.time.Duration.ofMillis(totalDelay));
    }

    private ChatResponse buildChunk() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(CANNED_ANSWER))));
    }

    @SuppressWarnings("unused")
    long getStreamTotalMs() {
        return streamTotalMs;
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
