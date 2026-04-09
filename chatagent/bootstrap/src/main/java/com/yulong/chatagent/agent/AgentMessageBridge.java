package com.yulong.chatagent.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Bridges agent-generated messages into persistence and realtime delivery channels.
 */
public interface AgentMessageBridge {
    /**
     * Persists one runtime message and publishes it to interested clients.
     *
     * @param chatSessionId chat session identifier
     * @param turnId current conversation turn identifier
     * @param message runtime message to handle
     */
    void persistAndPublish(String chatSessionId, String turnId, Message message);

    /**
     * Executes a streaming pass for the final assistant response.
     *
     * @param chatSessionId chat session identifier
     * @param turnId current conversation turn identifier
     * @param prompt prompt without tools
     * @param llmService the routing LLM service
     * @return the final complete content
     */
    String streamFinalResponse(String chatSessionId, String turnId, Prompt prompt, LLMService llmService);

    /**
     * Streams a tool-enabled decision pass to the UI as a provisional assistant message.
     * If the model eventually emits tool calls, the provisional message is rolled back.
     *
     * @param chatSessionId chat session identifier
     * @param turnId current conversation turn identifier
     * @param prompt decision prompt
     * @param systemPrompt routing-time system prompt
     * @param tools available tools for the decision pass
     * @param llmService the routing LLM service
     * @return buffered streamed response for agent-loop branching
     */
    BufferedStreamingResponse streamDecisionResponse(String chatSessionId,
                                                    String turnId,
                                                    Prompt prompt,
                                                    String systemPrompt,
                                                    List<ToolCallback> tools,
                                                    LLMService llmService);

    /**
     * Replays a buffered final assistant response collected from a single routed stream.
     *
     * @param chatSessionId chat session identifier
     * @param turnId current conversation turn identifier
     * @param bufferedResponse buffered streamed response
     */
    void publishBufferedFinalResponse(String chatSessionId, String turnId, BufferedStreamingResponse bufferedResponse);
}
