package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.tools.ToolCallPreflight;
import com.yulong.chatagent.agent.runtime.contract.RetrievalMode;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
/**
 * ReAct 循环里的“思考阶段”封装。
 * <p>
 * 这个类只负责把当前记忆、系统提示词和工具定义交给 LLM，让模型决定下一步：
 * 直接输出最终答案，或返回一组工具调用。真正执行工具的逻辑在
 * {@link AgentToolExecutionEngine} 中完成。
 */
public class AgentThinkingEngine {

    private AgentMessageBridge.FinalStreamResult lastFinalStreamResult;

    public AgentMessageBridge.FinalStreamResult getLastFinalStreamResult() {
        return lastFinalStreamResult;
    }

    private static final String SESSION_FILE_SEARCH_TOOL = "SessionFileSearchTool";
    private static final String ATTACHED_SESSION_FILES_PREFIX = "Attached session files:";
    private static final String BOUND_KNOWLEDGE_BASES_PREFIX = "Bound knowledge bases:";
    private static final Pattern SESSION_FILE_REFERENCE = Pattern.compile(
            "(?i)\\b(?:i|we)\\s+(?:just\\s+)?(?:uploaded|attached|sent|shared)\\b"
                    + "|\\b(?:uploaded|attached)\\s+(?:file|document|note|briefing|txt|md|markdown|pdf|image|photo|picture|spreadsheet|csv|docx|xlsx)\\b"
                    + "|\\b(?:same\\s+)?(?:uploaded|attached|session)\\s+(?:briefing|brief|note|item)\\b"
                    + "|\\b(?:the|this|that|same)\\s+(?:uploaded|attached|session)\\s+(?:briefing\\s+)?item\\b"
                    + "|\\b(?:the|this|that|my|our|these|those)\\s+(?:attachment|file|document|pdf|image|photo|picture|spreadsheet|csv|docx|xlsx)\\b"
                    + "|\\b(?:uploaded|attached|session|local)\\s+(?:note|file|document|source|sources)\\b"
                    + "|\\b[\\w.-]+\\.(?:txt|md|markdown|pdf|png|jpe?g|webp|csv|docx?|xlsx?)\\b"
                    + "|\\bwhat\\s+(?:did|does)\\s+(?:the\\s+)?(?:(?:old|previous|prior|uploaded|attached|session)\\s+)?(?:room\\s+)?card\\s+(?:say|list|show|mention|record)\\b"
                    + "|\\bwhat\\s+(?:was|is)\\s+on\\s+(?:the\\s+)?(?:(?:old|previous|prior|uploaded|attached|session)\\s+)?(?:room\\s+)?card\\b"
                    + "|\\b(?:the|this|that|my|our)\\s+(?:uploaded|attached|floor|dock|room)[\\s-]+(?:card|note|brief|checklist)\\b"
                    + "|(?:我|我们)(?:刚才)?上传(?:了|的)?|刚才上传(?:了|的)?"
                    + "|(?:这个|该|我的|我们的|这些|那些|刚才的|上述)(?:附件|文件|文档|图片|照片|表格)"
                    + "|(?:查看|看|阅读|分析|总结).{0,4}(?:附件|文件|文档|图片|照片|表格)"
    );
    private static final Pattern KNOWLEDGE_SCOPE_REFERENCE = Pattern.compile(
            "(?i)\\b(?:internal|private|company|policy|reference\\s+pack|reference\\s+materials?|launch\\s+review\\s+materials?|supporting\\s+sources?|cite|cites|citation|evidence|source-backed|listed\\s+(?:code|category|value)|what\\s+code\\s+is\\s+listed|which\\s+.+\\s+has\\s+\\d+)\\b"
                    + "|\\b(?:local|bound|scoped|internal|private|our|my|company)\\s+(?:kb|knowledge\\s+bases?|sources?|materials?|notes?)\\b"
                    + "|\\b(?:handoff|escalation(?:\\s+contact)?|support\\s+contact|runbook|launch\\s+notes?|briefing\\s+notes?)\\b"
                    + "|\\b(?:archive|dossier|case|project|handoff|escalation|verification)\\s+(?:code|marker|contact|owner|vendor)\\b"
                    + "|\\b(?:warehouse|carrier|vendor)\\s+(?:readiness|contact|risk|note|checklist)\\b"
                    + "|\\b(?:readiness|open|current|remaining)\\s+(?:risk|issue|item|status|checklist)\\b"
                    + "|\\b(?:contact|code|marker|owner|vendor)\\s+(?:listed|named|shown|recorded|documented)\\b"
                    + "|\\b(?:do\\s+you\\s+)?remember\\s+.+\\b(?:handoff|escalation|contact|code|marker)\\b"
                    + "|(?:内部|私有|公司|政策|资料包|参考资料|引用|证据|来源|出处|列出|记载|交接|升级联系人|升级联络人|运行手册)"
    );
    private static final Pattern GENERIC_KNOWLEDGE_ADVICE_REQUEST = Pattern.compile(
            "(?i)\\b(?:generic\\s+question|general\\s+advice|reusable\\s+template)\\b"
                    + "|\\bany\\s+(?:handoff|runbook|briefing|launch\\s+note|status\\s+update)\\b"
                    + "|\\bhow\\s+(?:do|can|should)\\s+(?:i|we|you)\\s+"
                    + "(?:make|keep|improve|maintain|write|draft|structure|organize|format|scan|summarize)\\b"
                    + "|(?:通用|泛化|模板|任何).{0,12}(?:建议|交接|运行手册|简报)"
    );
    private static final Pattern ACKNOWLEDGEMENT_FRAGMENT = Pattern.compile(
            "(?i)^(?:ok|okay|yes|yeah|yep|no|nope|continue|go\\s+on|thanks?|thank\\s+you|done|好|好的|可以|继续|谢谢)$"
    );
    private static final Pattern AMBIGUOUS_TOPIC_FRAGMENT = Pattern.compile(
            "(?i)^(?:operations?|ops|status|note|project|vendor|owner|locker|room|meeting|schedule|file|document|knowledge|memory|tool|database|mcp|rag|evidence|citation|report|summary)"
                    + "(?:\\s+(?:operations?|ops|status|note|project|vendor|owner|locker|room|meeting|schedule|file|document|knowledge|memory|tool|database|mcp|rag|evidence|citation|report|summary)){0,2}$"
    );
    private static final RetrievalToolCallPlanner RETRIEVAL_CALL_PLANNER =
            new RetrievalToolCallPlanner();

    private final PromptLoader promptLoader;
    private final LLMService llmService;
    private final ChatOptions chatOptions;
    private final List<ToolCallback> availableTools;
    private final String sessionFileSummary;
    private final String relevantLongTermMemories;
    private final String turnId;
    private final AgentMessageBridge messageBridge;
    private final int maxToolCallsPerStep;
    private final TurnExecutionContract executionContract;
    private final ToolCallPreflight toolCallPreflight;

    public AgentThinkingEngine(PromptLoader promptLoader,
                        LLMService llmService,
                        ChatOptions chatOptions,
                        List<ToolCallback> availableTools,
                        String sessionFileSummary,
                        String relevantLongTermMemories,
                        String turnId,
                        AgentMessageBridge messageBridge,
                        int maxToolCallsPerStep) {
        this(promptLoader, llmService, chatOptions, availableTools, sessionFileSummary,
                relevantLongTermMemories, turnId, messageBridge, maxToolCallsPerStep, null);
    }

    public AgentThinkingEngine(PromptLoader promptLoader,
                        LLMService llmService,
                        ChatOptions chatOptions,
                        List<ToolCallback> availableTools,
                        String sessionFileSummary,
                        String relevantLongTermMemories,
                        String turnId,
                        AgentMessageBridge messageBridge,
                        int maxToolCallsPerStep,
                        TurnExecutionContract executionContract) {
        this.promptLoader = promptLoader;
        this.llmService = llmService;
        this.chatOptions = chatOptions;
        this.availableTools = availableTools;
        this.sessionFileSummary = sessionFileSummary;
        this.relevantLongTermMemories = relevantLongTermMemories;
        this.turnId = turnId;
        this.messageBridge = messageBridge;
        this.maxToolCallsPerStep = maxToolCallsPerStep;
        this.executionContract = executionContract;
        this.toolCallPreflight = new ToolCallPreflight(maxToolCallsPerStep);
    }

    /**
     * 调用模型完成一次决策。
     *
     * @param chatMemory 当前 Agent 的窗口记忆
     * @param chatSessionId 当前会话 ID
     * @return 本轮模型输出，可能包含 tool calls，也可能已经是最终文本
     */
    public ChatResponse think(ChatMemory chatMemory, String chatSessionId) {
        long startTime = System.nanoTime();
        // 历史消息里如果存在不完整的 tool_call/tool_response 序列，部分模型会直接拒绝请求。
        // 因此在组装 Prompt 前先清洗一遍，只保留格式完整的上下文片段。
        List<Message> promptMessages = sanitizePromptMessages(chatMemory.get(chatSessionId));
        String latestUserRequest = latestUserRequest(promptMessages);
        // The typed contract owns clarification in enforce mode. Keep this
        // heuristic only for the explicit legacy/rollback path.
        if (executionContract == null && shouldClarifyAmbiguousTopicFragment(latestUserRequest)) {
            AssistantMessage clarification = new AssistantMessage(
                    "Could you clarify what operations context you mean? For example, ask about the current project, owner, locker, readiness note, or another area."
            );
            this.messageBridge.persistAndPublish(chatSessionId, turnId, clarification);
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Agent short-fragment clarification returned without model call: traceId={}, sessionId={}, durationMs={}",
                    TraceContext.getTraceId(), chatSessionId, durationMs);
            return new ChatResponse(List.of(new Generation(clarification)));
        }
        Map<String, String> vars = Map.of(
                "sessionFileSummary", this.sessionFileSummary,
                "relevantLongTermMemories", this.relevantLongTermMemories,
                "latestUserRequest", latestUserRequest
        );
        String decisionPrompt = promptLoader.render(PromptConstants.AGENT_DECISION_MODULE, vars);
        Prompt prompt = buildPrompt(promptMessages, this.chatOptions);

        // A required retrieval contract is executable only when its named
        // capability is present; unrelated tools cannot satisfy that contract.
        if (requiresRetrieval() && !hasNamedRetrievalTool()) {
            throw new IllegalStateException(
                    "Contract requires retrieval but SessionFileSearchTool is not available");
        }

        // 没有可用工具时无需做“工具决策”，直接走最终答案流，减少一次不必要的路由判断。
        if (this.availableTools.isEmpty()) {
            log.info("No tools available for this turn. Streaming final response directly.");
            ChatResponse finalResponse = streamFinalAnswer(
                    chatSessionId, buildFinalAnswerPrompt(promptMessages, latestUserRequest));
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Agent think completed: traceId={}, sessionId={}, toolCalls=0, durationMs={}",
                    TraceContext.getTraceId(),
                    chatSessionId,
                    durationMs);
            return finalResponse;
        }
        AssistantMessage mandatorySessionFileCall = buildMandatorySessionFileCall(promptMessages);
        if (mandatorySessionFileCall != null) {
            this.messageBridge.persistAndPublish(chatSessionId, turnId, mandatorySessionFileCall);
            log.info("Deterministic session-file routing selected: traceId={}, sessionId={}, turnId={}",
                    TraceContext.getTraceId(), chatSessionId, turnId);
            return new ChatResponse(List.of(new Generation(mandatorySessionFileCall)));
        }

        // 工具决策只作为内部路由信号收集。若模型没有选择工具，仍然进入最终回答模块生成用户可见文本。
        // 这样可以避免 fallback 模型把“分析当前上下文”的决策提示误当成最终答案泄露给用户。
        BufferedStreamingResponse decision = this.messageBridge.collectDecisionResponse(
                chatSessionId,
                turnId,
                prompt,
                decisionPrompt,
                this.availableTools,
                this.llmService,
                DecisionVisibility.INTERNAL_TRACE_ONLY,
                false,
                null,
                null);
        ChatResponse chatResponse = decision.response();

        Assert.notNull(chatResponse, "Last chat client response cannot be null");

        AssistantMessage output = chatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        // 在持久化之前用共享 ToolCallPreflight 做 batch 截断、id 规范化和参数字节上限：
        // 保证 DB 中 assistant tool_calls 与后续 tool_response 数量严格配对，并拦截 raw 溢出参数。
        // 这替换了原本内联的、与 DeepThink 重复的 per-step 截断分支（ARRB-CLN-001）。
        if (toolCalls != null && !toolCalls.isEmpty()) {
            ToolCallPreflight.ToolCallPreflightResult preflight =
                    toolCallPreflight.normalize(output);
            if (preflight.batchTruncated()) {
                log.warn("Model returned {} tool calls in one step, exceeding maxToolCallsPerStep={}. "
                                + "Preflight truncated to first {} calls before persistence.",
                        toolCalls.size(), this.maxToolCallsPerStep, this.maxToolCallsPerStep);
            }
            output = preflight.assistantMessage();
            chatResponse = new ChatResponse(List.of(new Generation(output)));
            toolCalls = output.getToolCalls();
        }

        List<AssistantMessage.ToolCall> filteredToolCalls = suppressUnsupportedSessionFileSearchCalls(
                toolCalls, latestUserRequest);
        if (toolCalls != null && filteredToolCalls.size() != toolCalls.size()) {
            output = AssistantMessage.builder()
                    .content(output.getText())
                    .toolCalls(filteredToolCalls)
                    .build();
            chatResponse = new ChatResponse(List.of(new Generation(output)));
            toolCalls = filteredToolCalls;
        }

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("Agent decision selected direct answer. Streaming final response through final-answer module.");
            ChatResponse finalResponse = streamFinalAnswer(
                    chatSessionId, buildFinalAnswerPrompt(promptMessages, latestUserRequest));
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Agent think completed: traceId={}, sessionId={}, toolCalls=0, durationMs={}",
                    TraceContext.getTraceId(),
                    chatSessionId,
                    durationMs);
            return finalResponse;
        }

        // 工具调用决策也要落库，这样下一轮模型能看到 assistant 请求了哪些工具。
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

    private AssistantMessage buildMandatorySessionFileCall(List<Message> promptMessages) {
        if (!requiresRetrieval() && !StringUtils.hasText(this.sessionFileSummary)) {
            return null;
        }

        int latestUserIndex = -1;
        String latestUserText = null;
        for (int i = promptMessages.size() - 1; i >= 0; i--) {
            if (promptMessages.get(i) instanceof UserMessage userMessage) {
                latestUserIndex = i;
                latestUserText = userMessage.getText();
                break;
            }
        }
        if (!StringUtils.hasText(latestUserText) || hasSessionFileResponseAfter(promptMessages, latestUserIndex)) {
            return null;
        }

        if (!hasNamedRetrievalTool()) {
            return null;
        }
        if (!shouldRouteSessionFileSearch(latestUserText)) {
            return null;
        }

        List<AssistantMessage.ToolCall> toolCalls = RETRIEVAL_CALL_PLANNER.plan(
                requiresRetrieval() ? executionContract : null,
                latestUserText,
                maxToolCallsPerStep);
        return AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
    }

    private List<AssistantMessage.ToolCall> suppressUnsupportedSessionFileSearchCalls(
            List<AssistantMessage.ToolCall> toolCalls,
            String latestUserText) {
        if (toolCalls == null || toolCalls.isEmpty() || shouldRouteSessionFileSearch(latestUserText)) {
            return toolCalls == null ? List.of() : toolCalls;
        }
        List<AssistantMessage.ToolCall> filtered = toolCalls.stream()
                .filter(toolCall -> !SESSION_FILE_SEARCH_TOOL.equals(toolCall.name()))
                .toList();
        if (filtered.size() != toolCalls.size()) {
            log.info("Suppressed unsupported session-file search tool call: traceId={}, turnId={}, reason=contract_or_legacy_gate",
                    TraceContext.getTraceId(), turnId);
        }
        return filtered;
    }

    private boolean shouldRouteSessionFileSearch(String latestUserText) {
        // Phase 3: when the contract says retrieval is REQUIRED_BEFORE_ANSWER,
        // never suppress retrieval because of missing user trigger words.
        if (requiresRetrieval()) {
            return true;
        }
        if (!StringUtils.hasText(latestUserText) || !StringUtils.hasText(this.sessionFileSummary)) {
            return false;
        }
        boolean shouldSearchAttachedFile = this.sessionFileSummary.contains(ATTACHED_SESSION_FILES_PREFIX)
                && SESSION_FILE_REFERENCE.matcher(latestUserText).find();
        boolean shouldSearchBoundKnowledge = this.sessionFileSummary.contains(BOUND_KNOWLEDGE_BASES_PREFIX)
                && !GENERIC_KNOWLEDGE_ADVICE_REQUEST.matcher(latestUserText).find()
                && KNOWLEDGE_SCOPE_REFERENCE.matcher(latestUserText).find();
        return shouldSearchAttachedFile || shouldSearchBoundKnowledge;
    }

    private boolean requiresRetrieval() {
        return executionContract != null
                && executionContract.retrieval() != null
                && executionContract.retrieval().mode() == RetrievalMode.REQUIRED_BEFORE_ANSWER;
    }

    /**
     * Checks whether the contract-required retrieval capability is present.
     */
    private boolean hasNamedRetrievalTool() {
        return this.availableTools.stream()
                .map(ToolCallback::getToolDefinition)
                .filter(Objects::nonNull)
                .anyMatch(definition -> SESSION_FILE_SEARCH_TOOL.equals(definition.name()));
    }

    private boolean shouldClarifyAmbiguousTopicFragment(String latestUserText) {
        if (!StringUtils.hasText(latestUserText)) {
            return false;
        }
        String normalized = latestUserText.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}&&[^-]]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!StringUtils.hasText(normalized)
                || normalized.length() > 48
                || ACKNOWLEDGEMENT_FRAGMENT.matcher(normalized).matches()) {
            return false;
        }
        int tokenCount = normalized.split("\\s+").length;
        return tokenCount <= 3 && AMBIGUOUS_TOPIC_FRAGMENT.matcher(normalized).matches();
    }

    private boolean hasSessionFileResponseAfter(List<Message> promptMessages, int latestUserIndex) {
        for (int i = latestUserIndex + 1; i < promptMessages.size(); i++) {
            if (promptMessages.get(i) instanceof ToolResponseMessage toolResponseMessage
                    && toolResponseMessage.getResponses().stream()
                    .anyMatch(response -> SESSION_FILE_SEARCH_TOOL.equals(response.name()))) {
                return true;
            }
        }
        return false;
    }

    private ChatResponse streamFinalAnswer(String chatSessionId, Prompt prompt) {
        // ChatAgent.step() 只看返回的 ChatResponse 是否包含 tool_call。
        // 因此最终答案的流式输出也放在 think() 内完成，再包装成一个普通 ChatResponse 返回。
        this.lastFinalStreamResult = this.messageBridge.streamFinalResponseWithOutcome(
                chatSessionId, turnId, prompt, this.llmService, false);
        if (this.lastFinalStreamResult == null) {
            this.lastFinalStreamResult = new AgentMessageBridge.FinalStreamResult(
                    AgentMessageBridge.FinalStreamStatus.COMPLETE,
                    this.messageBridge.streamFinalResponse(
                            chatSessionId, turnId, prompt, this.llmService, false));
        }
        String finalContent = this.lastFinalStreamResult.content();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(finalContent))));
    }

    private Prompt buildFinalAnswerPrompt(List<Message> promptMessages, String latestUserRequest) {
        ChatOptions streamOptions = this.chatOptions.copy();
        if (streamOptions instanceof ToolCallingChatOptions toolOptions) {
            // 最终答案阶段必须清空工具，否则模型可能再次选择 tool_call，打破“无工具分支”的语义。
            toolOptions.setToolCallbacks(List.of());
        }

        List<Message> finalPromptMessages = new ArrayList<>(promptMessages.size() + 1);
        Map<String, String> vars = Map.of(
                "sessionFileSummary", this.sessionFileSummary,
                "relevantLongTermMemories", this.relevantLongTermMemories,
                "latestUserRequest", latestUserRequest
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

    private String latestUserRequest(List<Message> promptMessages) {
        for (int i = promptMessages.size() - 1; i >= 0; i--) {
            if (promptMessages.get(i) instanceof UserMessage userMessage
                    && StringUtils.hasText(userMessage.getText())) {
                return userMessage.getText();
            }
        }
        return "No user-role message is present in the current conversation history.";
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
     * Logs tool call names so a full agent decision step can be inspected without leaking
     * raw arguments. ARRB ARRB-AC-031: complete tool arguments/results must not appear in
     * INFO/WARN logs; only tool name, count, and order remain.
     *
     * @param toolCalls tool calls emitted by the model
     */
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("[ToolCalling] no tool calls");
            return;
        }
        String names = toolCalls.stream()
                .map(call -> call.name() == null ? "<missing>" : call.name())
                .collect(Collectors.joining(", "));
        log.info("[ToolCalling] count={}, names=[{}]", toolCalls.size(), names);
    }

    private record ToolSequenceResult(List<Message> messages, int lastConsumedIndex) {
    }
}
