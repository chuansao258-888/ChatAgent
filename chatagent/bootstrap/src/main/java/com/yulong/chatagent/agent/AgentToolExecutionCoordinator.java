package com.yulong.chatagent.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.util.StringUtils;

import java.util.List;

/** One Spring-AI tool execution boundary shared by ReAct and DeepThink. */
public final class AgentToolExecutionCoordinator {

    private final ToolCallingManager toolCallingManager;

    public AgentToolExecutionCoordinator(List<ToolCallback> availableTools) {
        this.toolCallingManager = ToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(sanitize(availableTools)))
                .build();
    }

    public ToolExecutionResult execute(Prompt prompt, ChatResponse response) {
        try {
            return toolCallingManager.executeToolCalls(prompt, response);
        } catch (RuntimeException exception) {
            throw new ToolExecutionException("Tool execution failed", exception);
        }
    }

    public ToolResponseMessage executeDirect(AssistantMessage assistantMessage,
                                             ChatOptions chatOptions) {
        if (assistantMessage == null || assistantMessage.getToolCalls() == null
                || assistantMessage.getToolCalls().isEmpty()) {
            throw new ToolExecutionException("Tool execution requires at least one tool call");
        }
        ChatResponse response = new ChatResponse(List.of(new Generation(assistantMessage)));
        Prompt prompt = Prompt.builder()
                .messages(List.of(assistantMessage))
                .chatOptions(chatOptions)
                .build();
        ToolExecutionResult result = execute(prompt, response);
        List<org.springframework.ai.chat.messages.Message> history = result.conversationHistory();
        if (history == null || history.isEmpty()
                || !(history.get(history.size() - 1) instanceof ToolResponseMessage toolResponse)) {
            throw new ToolExecutionException("Tool execution produced no paired tool response");
        }
        return toolResponse;
    }

    private static List<ToolCallback> sanitize(List<ToolCallback> availableTools) {
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
