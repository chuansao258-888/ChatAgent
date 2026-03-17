package com.yulong.chatagent.agent;

import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
class AgentThinkingEngine {

    private final ChatClient chatClient;
    private final ChatOptions chatOptions;
    private final List<ToolCallback> availableTools;
    private final String knowledgeBaseSummary;
    private final AgentMessageBridge messageBridge;

    AgentThinkingEngine(ChatClient chatClient,
                        ChatOptions chatOptions,
                        List<ToolCallback> availableTools,
                        String knowledgeBaseSummary,
                        AgentMessageBridge messageBridge) {
        this.chatClient = chatClient;
        this.chatOptions = chatOptions;
        this.availableTools = availableTools;
        this.knowledgeBaseSummary = knowledgeBaseSummary;
        this.messageBridge = messageBridge;
    }

    ChatResponse think(ChatMemory chatMemory, String chatSessionId) {
        long startTime = System.nanoTime();
        String thinkPrompt = """
                You are the agent decision module.
                Decide the next action from the current conversation context.

                Additional context:
                - Available knowledge bases: %s
                - If context is missing, prefer searching the knowledge base first.
                """.formatted(this.knowledgeBaseSummary);

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(chatMemory.get(chatSessionId))
                .build();

        ChatResponse chatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(chatResponse, "Last chat client response cannot be null");

        AssistantMessage output = chatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        this.messageBridge.persistAndPublish(chatSessionId, output);
        logToolCalls(toolCalls);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Agent think completed: traceId={}, sessionId={}, toolCalls={}, durationMs={}",
                TraceContext.getTraceId(),
                chatSessionId,
                toolCalls == null ? 0 : toolCalls.size(),
                durationMs);
        return chatResponse;
    }

    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] no tool calls");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }
}
