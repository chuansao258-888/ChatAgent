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
import org.springframework.util.Assert;

import java.util.stream.Collectors;

@Slf4j
/**
 * Encapsulates the tool-execution phase of the agent loop.
 */
class AgentToolExecutionEngine {

    private final ToolCallingManager toolCallingManager;
    private final ChatOptions chatOptions;
    private final AgentMessageBridge messageBridge;

    AgentToolExecutionEngine(ToolCallingManager toolCallingManager,
                             ChatOptions chatOptions,
                             AgentMessageBridge messageBridge) {
        this.toolCallingManager = toolCallingManager;
        this.chatOptions = chatOptions;
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
        this.messageBridge.persistAndPublish(chatSessionId, toolResponseMessage);

        return toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"));
    }
}
