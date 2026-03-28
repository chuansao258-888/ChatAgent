package com.yulong.chatagent.agent;

import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
/**
 * Encapsulates the model-thinking phase of the agent loop.
 */
class AgentThinkingEngine {

    private final ChatClient chatClient;
    private final ChatOptions chatOptions;
    private final List<ToolCallback> availableTools;
    private final String sessionFileSummary;
    private final String userProfileSummary;
    private final String turnId;
    private final AgentMessageBridge messageBridge;

    AgentThinkingEngine(ChatClient chatClient,
                        ChatOptions chatOptions,
                        List<ToolCallback> availableTools,
                        String sessionFileSummary,
                        String userProfileSummary,
                        String turnId,
                        AgentMessageBridge messageBridge) {
        this.chatClient = chatClient;
        this.chatOptions = chatOptions;
        this.availableTools = availableTools;
        this.sessionFileSummary = sessionFileSummary;
        this.userProfileSummary = userProfileSummary;
        this.turnId = turnId;
        this.messageBridge = messageBridge;
    }

    /**
     * Invokes the chat model with current memory and available tool callbacks.
     *
     * @param chatMemory chat memory store
     * @param chatSessionId chat session identifier
     * @return raw model response for the current step
     */
    ChatResponse think(ChatMemory chatMemory, String chatSessionId) {
        long startTime = System.nanoTime();
        String thinkPrompt = """
                You are the agent decision module.
                Decide the next action from the current conversation context.

                Additional context:
                - Attached session files: %s
                - Persistent user profile: %s
                - If context is missing, prefer searching the current chat session files first.
                - When the user profile contains stable preferences, keep responses consistent with it.
                """.formatted(this.sessionFileSummary, this.userProfileSummary);

        List<Message> promptMessages = sanitizePromptMessages(chatMemory.get(chatSessionId));
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(promptMessages)
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
        this.messageBridge.persistAndPublish(chatSessionId, turnId, output);
        logToolCalls(toolCalls);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Agent think completed: traceId={}, sessionId={}, toolCalls={}, durationMs={}",
                TraceContext.getTraceId(),
                chatSessionId,
                toolCalls == null ? 0 : toolCalls.size(),
                durationMs);
        return chatResponse;
    }

    private List<Message> sanitizePromptMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Message> sanitized = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage) {
                log.warn("Skip orphan tool message before prompt assembly at index={}", i);
                continue;
            }

            if (message instanceof AssistantMessage assistantMessage) {
                List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    sanitized.add(assistantMessage);
                    continue;
                }

                ToolSequenceResult toolSequenceResult = collectToolSequence(messages, i, assistantMessage);
                if (toolSequenceResult.messages().isEmpty()) {
                    log.warn("Skip incomplete assistant tool-call sequence before prompt assembly at index={}, toolCalls={}",
                            i, toolCalls.size());
                    i = toolSequenceResult.lastConsumedIndex();
                    continue;
                }

                sanitized.addAll(toolSequenceResult.messages());
                i = toolSequenceResult.lastConsumedIndex();
                continue;
            }

            sanitized.add(message);
        }
        return sanitized;
    }

    private ToolSequenceResult collectToolSequence(List<Message> messages,
                                                   int assistantIndex,
                                                   AssistantMessage assistantMessage) {
        List<Message> sequence = new ArrayList<>();
        sequence.add(assistantMessage);

        Set<String> requiredToolCallIds = assistantMessage.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::id)
                .filter(StringUtils::hasLength)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> resolvedToolCallIds = new HashSet<>();

        int lastConsumedIndex = assistantIndex;
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            Message nextMessage = messages.get(i);
            if (!(nextMessage instanceof ToolResponseMessage toolResponseMessage)) {
                break;
            }

            List<ToolResponseMessage.ToolResponse> validResponses = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                if (!requiredToolCallIds.isEmpty()
                        && StringUtils.hasLength(toolResponse.id())
                        && !requiredToolCallIds.contains(toolResponse.id())) {
                    log.warn("Skip mismatched tool response before prompt assembly: toolCallId={}, toolName={}",
                            toolResponse.id(), toolResponse.name());
                    continue;
                }

                validResponses.add(toolResponse);
                if (StringUtils.hasLength(toolResponse.id())) {
                    resolvedToolCallIds.add(toolResponse.id());
                }
            }

            if (!validResponses.isEmpty()) {
                sequence.add(ToolResponseMessage.builder()
                        .responses(validResponses)
                        .build());
            }
            lastConsumedIndex = i;
        }

        boolean hasAnyToolResponse = sequence.size() > 1;
        boolean allToolCallsResolved = requiredToolCallIds.isEmpty() || resolvedToolCallIds.containsAll(requiredToolCallIds);
        if (!hasAnyToolResponse || !allToolCallsResolved) {
            return new ToolSequenceResult(List.of(), lastConsumedIndex);
        }

        return new ToolSequenceResult(sequence, lastConsumedIndex);
    }

    /**
     * Logs tool calls in a readable block so a full agent decision step can be inspected.
     *
     * @param toolCalls tool calls emitted by the model
     */
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

    private record ToolSequenceResult(List<Message> messages, int lastConsumedIndex) {
    }
}
