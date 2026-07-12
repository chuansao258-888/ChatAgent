package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.chat.routing.StreamCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.List;

/**
 * In-process stub {@link LLMService} active only under the {@code capacity-test}
 * profile.
 *
 * <p>Because this bean is {@code @Primary} under the profile, the agent runtime
 * ({@code AgentMessageBridgeImpl}, {@code AgentThinkingEngine},
 * {@code ReactRuntimeEngine}, DeepThink engines) injects it instead of
 * {@code RoutingLLMService}. The provider-direct SSE path and the
 * {@code ChatClient.stream()} fallback therefore never execute under capacity-test,
 * and no real provider HTTP call is made.</p>
 *
 * <p>Latency is simulated on the calling (MQ consumer) thread via
 * {@link Thread#sleep}, occupying the {@code agent-concurrency} slot for the
 * turn — the intended, realistic behavior. The stub never returns a tool call,
 * so the ReAct loop terminates in one iteration. It never throws on empty or
 * malformed prompts.</p>
 */
@Profile("capacity-test")
@Primary
@Service
@Slf4j
public class CapacityStubLlmService implements LLMService {

    /**
     * Canned content split into chunks so the streaming path (SSE delivery) is
     * exercised, not just a single final write.
     */
    private static final String[] CANNED_CHUNKS = {
            "这是", "压测", "环境", "的", "模拟", "回答。", "已收到", "你的问题，", "并返回", "稳定结果。"
    };

    private static final String CANNED_ANSWER = String.join("", CANNED_CHUNKS);

    private final CapacityTestProperties properties;

    public CapacityStubLlmService(CapacityTestProperties properties) {
        this.properties = properties;
    }

    @Override
    public Disposable streamChat(Prompt prompt, boolean deepThinking, StreamCallback callback) {
        try {
            sleepQuietly(properties.getMockTtftMs());
            // Signal that a valid first chunk has arrived (mirrors the real first-packet path).
            callback.onSignal();
            long chunkDelayMs = deriveChunkDelayMs();
            for (String chunk : CANNED_CHUNKS) {
                callback.onContent(chunk);
                sleepQuietly(chunkDelayMs);
            }
            callback.onComplete();
        } catch (RuntimeException e) {
            // sleepQuietly only throws on interrupt; surface as onError rather than
            // letting the stub kill the consumer thread.
            callback.onError(e);
        }
        return () -> {
        };
    }

    @Override
    public BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt,
                                                               String systemPrompt,
                                                               List<ToolCallback> tools,
                                                               boolean deepThinking) {
        return streamDecisionWithRouting(prompt, systemPrompt, tools, null, deepThinking);
    }

    @Override
    public BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt,
                                                               String systemPrompt,
                                                               List<ToolCallback> tools,
                                                               StreamCallback callback,
                                                               boolean deepThinking) {
        sleepQuietly(properties.getMockTtftMs());
        long chunkDelayMs = deriveChunkDelayMs();
        if (callback != null) {
            callback.onSignal();
            for (String chunk : CANNED_CHUNKS) {
                callback.onContent(chunk);
                sleepQuietly(chunkDelayMs);
            }
        }
        AssistantMessage assistantMessage = new AssistantMessage(CANNED_ANSWER);
        ChatResponse response = new ChatResponse(List.of(new Generation(assistantMessage)));
        // Never returns a tool call → ReAct loop terminates in one iteration.
        return new BufferedStreamingResponse(
                response,
                List.of(new BufferedStreamingResponse.BufferedStreamEvent(
                        BufferedStreamingResponse.EventType.CONTENT, CANNED_ANSWER))
        );
    }

    private long deriveChunkDelayMs() {
        long total = properties.getMockStreamTotalMs();
        int chunks = CANNED_CHUNKS.length;
        // Space chunks evenly across the streaming window AFTER the first token.
        // Guard against zero/negative to avoid a degenerate tight loop.
        if (total <= 0L || chunks <= 0) {
            return 0L;
        }
        return Math.max(1L, total / chunks);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("CapacityStubLlmService sleep interrupted", e);
        }
    }
}
