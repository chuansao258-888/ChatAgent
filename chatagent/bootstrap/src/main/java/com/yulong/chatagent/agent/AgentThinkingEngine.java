package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
/**
 * ReAct 循环里的“思考阶段”封装。
 * <p>
 * 这个类只负责把当前记忆、系统提示词和工具定义交给 LLM，让模型决定下一步：
 * 直接输出最终答案，或返回一组工具调用。真正执行工具的逻辑在
 * {@link AgentToolExecutionEngine} 中完成。
 */
class AgentThinkingEngine {

    private final PromptLoader promptLoader;
    private final LLMService llmService;
    private final ChatOptions chatOptions;
    private final List<ToolCallback> availableTools;
    private final String sessionFileSummary;
    private final String userProfileSummary;
    private final String turnId;
    private final AgentMessageBridge messageBridge;

    AgentThinkingEngine(PromptLoader promptLoader,
                        LLMService llmService,
                        ChatOptions chatOptions,
                        List<ToolCallback> availableTools,
                        String sessionFileSummary,
                        String userProfileSummary,
                        String turnId,
                        AgentMessageBridge messageBridge) {
        this.promptLoader = promptLoader;
        this.llmService = llmService;
        this.chatOptions = chatOptions;
        this.availableTools = availableTools;
        this.sessionFileSummary = sessionFileSummary;
        this.userProfileSummary = userProfileSummary;
        this.turnId = turnId;
        this.messageBridge = messageBridge;
    }

    /**
     * 调用模型完成一次决策。
     *
     * @param chatMemory 当前 Agent 的窗口记忆
     * @param chatSessionId 当前会话 ID
     * @return 本轮模型输出，可能包含 tool calls，也可能已经是最终文本
     */
    ChatResponse think(ChatMemory chatMemory, String chatSessionId) {
        long startTime = System.nanoTime();
        Map<String, String> vars = Map.of(
                "sessionFileSummary", this.sessionFileSummary,
                "userProfileSummary", this.userProfileSummary
        );
        String decisionPrompt = promptLoader.render(PromptConstants.AGENT_DECISION_MODULE, vars);

        // 历史消息里如果存在不完整的 tool_call/tool_response 序列，部分模型会直接拒绝请求。
        // 因此在组装 Prompt 前先清洗一遍，只保留格式完整的上下文片段。
        List<Message> promptMessages = sanitizePromptMessages(chatMemory.get(chatSessionId));
        Prompt prompt = buildPrompt(promptMessages, this.chatOptions);

        // 没有可用工具时无需做“工具决策”，直接走最终答案流，减少一次不必要的路由判断。
        if (this.availableTools.isEmpty()) {
            log.info("No tools available for this turn. Streaming final response directly.");
            ChatResponse finalResponse = streamFinalAnswer(chatSessionId, buildFinalAnswerPrompt(promptMessages));
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Agent think completed: traceId={}, sessionId={}, toolCalls=0, durationMs={}",
                    TraceContext.getTraceId(),
                    chatSessionId,
                    durationMs);
            return finalResponse;
        }

        // 单次路由流：同一次模型调用既实时推送给前端，又缓冲出完整 ChatResponse。
        // 如果最终发现 tool_call，前端会回滚临时文本；如果没有 tool_call，这次流就是最终答案。
        BufferedStreamingResponse decision = this.messageBridge.streamDecisionResponse(
                chatSessionId,
                turnId,
                prompt,
                decisionPrompt,
                this.availableTools,
                this.llmService);
        ChatResponse chatResponse = decision.response();

        Assert.notNull(chatResponse, "Last chat client response cannot be null");

        AssistantMessage output = chatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 工具调用决策也要落库，这样下一轮模型能看到“assistant 请求了哪些工具”。
            this.messageBridge.persistAndPublish(chatSessionId, turnId, output);
            logToolCalls(toolCalls);
        } else {
            log.info("Agent decided final answer in a single routed stream. Live passthrough finalized without a second model call.");
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Agent think completed: traceId={}, sessionId={}, toolCalls={}, durationMs={}",
                TraceContext.getTraceId(),
                chatSessionId,
                toolCalls == null ? 0 : toolCalls.size(),
                durationMs);
        return chatResponse;
    }

    private ChatResponse streamFinalAnswer(String chatSessionId, Prompt prompt) {
        // ChatAgent.step() 只看返回的 ChatResponse 是否包含 tool_call。
        // 因此最终答案的流式输出也放在 think() 内完成，再包装成一个普通 ChatResponse 返回。
        String finalContent = this.messageBridge.streamFinalResponse(chatSessionId, turnId, prompt, this.llmService);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(finalContent))));
    }

    private Prompt buildFinalAnswerPrompt(List<Message> promptMessages) {
        ChatOptions streamOptions = this.chatOptions.copy();
        if (streamOptions instanceof ToolCallingChatOptions toolOptions) {
            // 最终答案阶段必须清空工具，否则模型可能再次选择 tool_call，打破“无工具分支”的语义。
            toolOptions.setToolCallbacks(List.of());
        }

        List<Message> finalPromptMessages = new ArrayList<>(promptMessages.size() + 1);
        Map<String, String> vars = Map.of(
                "sessionFileSummary", this.sessionFileSummary,
                "userProfileSummary", this.userProfileSummary
        );
        finalPromptMessages.add(new SystemMessage(promptLoader.render(PromptConstants.AGENT_FINAL_ANSWER, vars)));
        finalPromptMessages.addAll(promptMessages);
        return buildPrompt(finalPromptMessages, streamOptions);
    }

    private Prompt buildPrompt(List<Message> promptMessages, ChatOptions options) {
        return Prompt.builder()
                .chatOptions(options)
                .messages(promptMessages)
                .build();
    }

    private List<Message> sanitizePromptMessages(List<Message> messages) {
        // Spring AI / OpenAI 风格的工具消息要求严格成对：
        // assistant(tool_calls) 后面必须跟对应的 tool_response，孤立工具消息会被跳过。
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
                    // assistant 已要求工具但后续没有完整 tool_response 时，整组跳过，避免污染 Prompt。
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
        // 从 assistant 的 tool_call 开始向后收集连续 tool_response，并校验 call id 是否能全部对上。
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
