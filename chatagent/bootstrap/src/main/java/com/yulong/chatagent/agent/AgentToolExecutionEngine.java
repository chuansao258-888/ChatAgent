package com.yulong.chatagent.agent;

import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
/**
 * Encapsulates the tool-execution phase of the agent loop.
 */
class AgentToolExecutionEngine {

    private final ToolCallingManager toolCallingManager;
    private final ChatOptions chatOptions;
    private final String turnId;
    private final AgentMessageBridge messageBridge;

    AgentToolExecutionEngine(List<ToolCallback> availableTools,
                             ChatOptions chatOptions,
                             String turnId,
                             AgentMessageBridge messageBridge) {
        this.toolCallingManager = ToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(
                        sanitizeToolCallbacks(availableTools)
                ))
                .build();
        this.chatOptions = chatOptions;
        this.turnId = turnId;
        this.messageBridge = messageBridge;
    }

    /**
     * Executes model-requested tool calls and appends tool responses back into memory.
     *
     * @param chatMemory chat memory store
     * @param chatSessionId chat session identifier
     * @param chatResponse last model response
     * @return {@code true} when a terminate tool response signals that the run should stop
     */
    boolean execute(ChatMemory chatMemory, String chatSessionId, ChatResponse chatResponse) {
        Assert.notNull(chatResponse, "Last chat client response cannot be null");

        if (!chatResponse.hasToolCalls()) {
            return false;
        }

        long startTime = System.nanoTime();
        Prompt prompt = Prompt.builder()
                .messages(chatMemory.get(chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, chatResponse);

        chatMemory.clear(chatSessionId);
        chatMemory.add(chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "Tool " + resp.name() + " response: " + resp.responseData())
                .collect(Collectors.joining("\n"));

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Tool execution completed: traceId={}, sessionId={}, responses={}, durationMs={}, result={}",
                TraceContext.getTraceId(),
                chatSessionId,
                toolResponseMessage.getResponses().size(),
                durationMs,
                collect);
        this.messageBridge.persistAndPublish(chatSessionId, turnId, toolResponseMessage);

        return toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"));
    }

    private List<ToolCallback> sanitizeToolCallbacks(List<ToolCallback> availableTools) {
        if (availableTools == null || availableTools.isEmpty()) {
            return List.of();
        }
        return availableTools.stream()
                .filter(callback -> callback != null
                        && callback.getToolDefinition() != null
                        && StringUtils.hasText(callback.getToolDefinition().name()))
                .toList();
    }
}
