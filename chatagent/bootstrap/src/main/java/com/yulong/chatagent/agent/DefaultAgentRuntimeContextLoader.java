package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.agent.runtime.AgentDefinition;
import com.yulong.chatagent.agent.runtime.AgentDefinitionLoader;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentMemoryLoader;
import com.yulong.chatagent.agent.runtime.AgentSessionFileSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentSessionSummaryResolver;
import com.yulong.chatagent.agent.runtime.AgentToolCallbackFactory;
import com.yulong.chatagent.agent.runtime.contract.RetrievalMode;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.memory.application.LongTermMemoryRecallService;
import com.yulong.chatagent.support.dto.AgentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

/**
 * 默认 Agent 运行时上下文加载器。
 * <p>
 * 它是 Agent 启动前的"装配台"：读取 Agent 定义、恢复 L1 记忆、解析 L2/L3 摘要、
 * 根据意图筛选工具，并把这些信息拼成最终系统提示词。
 */
@Component
@Slf4j
public class DefaultAgentRuntimeContextLoader implements AgentRuntimeContextLoader {

    private final PromptLoader promptLoader;
    private final AgentDefinitionLoader agentDefinitionLoader;
    private final AgentMemoryLoader agentMemoryLoader;
    private final AgentSessionFileSummaryResolver sessionFileSummaryResolver;
    private final AgentSessionSummaryResolver sessionSummaryResolver;
    private final AgentToolCallbackFactory agentToolCallbackFactory;
    private final LongTermMemoryRecallService longTermMemoryRecallService;
    private final ChatRoutingProperties chatRoutingProperties;

    public DefaultAgentRuntimeContextLoader(PromptLoader promptLoader,
                                            AgentDefinitionLoader agentDefinitionLoader,
                                            AgentMemoryLoader agentMemoryLoader,
                                            AgentSessionFileSummaryResolver sessionFileSummaryResolver,
                                            AgentSessionSummaryResolver sessionSummaryResolver,
                                            AgentToolCallbackFactory agentToolCallbackFactory,
                                            LongTermMemoryRecallService longTermMemoryRecallService,
                                            ChatRoutingProperties chatRoutingProperties) {
        this.promptLoader = promptLoader;
        this.agentDefinitionLoader = agentDefinitionLoader;
        this.agentMemoryLoader = agentMemoryLoader;
        this.sessionFileSummaryResolver = sessionFileSummaryResolver;
        this.sessionSummaryResolver = sessionSummaryResolver;
        this.agentToolCallbackFactory = agentToolCallbackFactory;
        this.longTermMemoryRecallService = longTermMemoryRecallService;
        this.chatRoutingProperties = chatRoutingProperties;
    }

    @Override
    public AgentRuntimeContext load(String agentId, String chatSessionId) {
        return load(agentId, chatSessionId, null, null);
    }

    @Override
    public AgentRuntimeContext load(String agentId,
                                    String chatSessionId,
                                    IntentResolution intentResolution,
                                    String rewrittenInput) {
        return load(agentId, chatSessionId, intentResolution, rewrittenInput, AgentExecutionMode.REACT);
    }

    @Override
    public AgentRuntimeContext load(String agentId,
                                    String chatSessionId,
                                    IntentResolution intentResolution,
                                    String rewrittenInput,
                                    AgentExecutionMode executionMode) {
        return load(agentId, chatSessionId, intentResolution, rewrittenInput, executionMode, null);
    }

    @Override
    public AgentRuntimeContext load(String agentId,
                                    String chatSessionId,
                                    IntentResolution intentResolution,
                                    String rewrittenInput,
                                    AgentExecutionMode executionMode,
                                    String currentUserInput) {
        return load(agentId, chatSessionId, intentResolution, rewrittenInput,
                executionMode, currentUserInput, null);
    }

    @Override
    public AgentRuntimeContext load(String agentId,
                                    String chatSessionId,
                                    IntentResolution intentResolution,
                                    String rewrittenInput,
                                    AgentExecutionMode executionMode,
                                    String currentUserInput,
                                    TurnExecutionContract executionContract) {
        // 1. 读取静态 Agent 配置，包括系统提示词、工具 allowlist 和 chatOptions。
        AgentDefinition definition = agentDefinitionLoader.load(agentId);
        AgentDTO agentConfig = definition.config();
        // 2. 恢复短期记忆和各类摘要；这些内容会同时影响 Prompt 和后续上下文窗口。
        List<Message> memory = agentMemoryLoader.load(chatSessionId, agentConfig);
        memory = ensureCurrentUserMessage(memory, currentUserInput);
        String sessionFileSummary = sessionFileSummaryResolver.resolve(agentConfig, chatSessionId);
        String sessionSummary = sessionSummaryResolver.resolve(chatSessionId);
        // 3. 工具列表要结合 Agent 配置和意图结果动态收窄，避免模型看到不该使用的工具。
        List<ToolCallback> toolCallbacks = executionContract == null
                ? agentToolCallbackFactory.create(agentConfig, intentResolution)
                : agentToolCallbackFactory.create(agentConfig, intentResolution, executionContract);
        toolCallbacks = filterUnavailableSessionFileSearchTool(
                chatSessionId, sessionFileSummary, toolCallbacks, executionContract);

        // 3.5 L3 recall: embed query, search user memories, format for prompt injection.
        String query = resolveQuery(rewrittenInput, memory);
        String relevantLongTermMemories = longTermMemoryRecallService.recall(chatSessionId, query);

        // 4. 系统提示词是最终喂给模型的"运行合同"，包含身份、历史摘要、意图边界和工具策略。
        String resolvedSystemPrompt = buildSystemPrompt(
                agentConfig.getSystemPrompt(),
                sessionSummary,
                intentResolution,
                rewrittenInput,
                memory,
                sessionFileSummary,
                relevantLongTermMemories,
                toolCallbacks
        );
        logResolvedPrompt(intentResolution, resolvedSystemPrompt);

        return new AgentRuntimeContext(
                agentConfig.getId(),
                agentConfig.getName(),
                agentConfig.getDescription(),
                resolvedSystemPrompt,
                chatRoutingProperties.getAgentPrimaryModel(),
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                sessionFileSummary,
                sessionSummary,
                relevantLongTermMemories,
                executionMode == null ? AgentExecutionMode.REACT : executionMode,
                executionContract
        );
    }

    private List<Message> ensureCurrentUserMessage(List<Message> memory, String currentUserInput) {
        List<Message> existing = memory == null ? List.of() : memory;
        if (!StringUtils.hasText(currentUserInput)) {
            return existing;
        }

        String normalizedCurrentInput = currentUserInput.trim();
        for (int i = existing.size() - 1; i >= 0; i--) {
            Message message = existing.get(i);
            if (message instanceof UserMessage userMessage && StringUtils.hasText(userMessage.getText())) {
                if (normalizedCurrentInput.equals(userMessage.getText().trim())) {
                    return existing;
                }
                break;
            }
        }

        List<Message> augmented = new ArrayList<>(existing.size() + 1);
        augmented.addAll(existing);
        augmented.add(new UserMessage(normalizedCurrentInput));
        return List.copyOf(augmented);
    }

    private String buildSystemPrompt(String baseSystemPrompt,
                                     String sessionSummary,
                                     IntentResolution intentResolution,
                                     String rewrittenInput,
                                     List<Message> memory,
                                     String sessionFileSummary,
                                     String relevantLongTermMemories,
                                     List<ToolCallback> toolCallbacks) {
        StringBuilder builder = new StringBuilder();

        // 1. 基础系统提示词：优先使用 Agent 配置，否则回退到默认模板。
        String effectivePrompt = StringUtils.hasText(baseSystemPrompt) ? baseSystemPrompt.trim() : promptLoader.load(PromptConstants.AGENT_DEFAULT_SYSTEM);
        builder.append(effectivePrompt).append("\n\n");

        // 2. L2 历史摘要：补充已经滑出 L1 窗口的长对话背景。
        //    V2 resolver returns empty when neither synopsis nor active nonblank segments exist.
        if (StringUtils.hasText(sessionSummary)) {
            builder.append("[Historical Context Summary]\n")
                    .append(sessionSummary).append("\n\n");
        }

        // 3. 意图路由上下文：把本轮意图、知识库范围和工具范围显式写进系统提示词。
        if (intentResolution != null) {
            boolean hasScopedKnowledgeBases = !intentResolution.scopedKbIds().isEmpty();
            boolean hasNarrowedTools = !intentResolution.allowedTools().isEmpty();
            builder.append("[Intent Routing Context]\n")
                    .append("- Intent kind: ").append(intentResolution.kind()).append("\n");
            if (StringUtils.hasText(intentResolution.pathLabel())) {
                builder.append("- Intent path: ").append(intentResolution.pathLabel()).append("\n");
            }
            if (hasScopedKnowledgeBases) {
                builder.append("- Scoped knowledge bases: ").append(intentResolution.scopedKbIds()).append("\n");
            }
            if (hasNarrowedTools) {
                builder.append("- Intent-narrowed tools: ").append(intentResolution.allowedTools()).append("\n");
            }
            if (StringUtils.hasText(rewrittenInput)) {
                builder.append("- Search hint: ").append(rewrittenInput).append("\n");
            }
            appendIntentBoundaryInstructions(builder, hasScopedKnowledgeBases, hasNarrowedTools);
        }

        // 4. 会话上下文：附件摘要让模型知道"当前会话有什么材料"。
        builder.append("[Session Context]\n");
        if (StringUtils.hasText(sessionFileSummary)) {
            builder.append("- Assets: ").append(sessionFileSummary).append("\n");
        }
        appendLatestTurnGuidance(builder, memory);

        // 5. L3 相关长期记忆：从用户历史对话中提取的稳定偏好和事实。
        if (StringUtils.hasText(relevantLongTermMemories)) {
            builder.append("\n[Relevant Long-Term Memory]\n")
                    .append(relevantLongTermMemories).append("\n");
        }

        appendToolStrategyGuidance(builder, toolCallbacks);

        if (hasMcpTools(toolCallbacks)) {
            builder.append("\n").append(promptLoader.load(PromptConstants.AGENT_MCP_TOOL_SAFETY)).append("\n");
        }
        if (hasWebSearchTool(toolCallbacks)) {
            builder.append("\n").append(promptLoader.load(PromptConstants.AGENT_WEB_SEARCH_SAFETY)).append("\n");
        }

        return builder.toString().trim();
    }

    private void appendToolStrategyGuidance(StringBuilder builder, List<ToolCallback> toolCallbacks) {
        // 只有存在工具时才追加工具策略，避免无工具 Agent 被无关规则干扰。
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return;
        }
        builder.append("\n").append(promptLoader.load(PromptConstants.AGENT_TOOL_STRATEGY)).append("\n");
    }

    private List<ToolCallback> filterUnavailableSessionFileSearchTool(String chatSessionId,
                                                                      String sessionFileSummary,
                                                                      List<ToolCallback> toolCallbacks,
                                                                      TurnExecutionContract executionContract) {
        if (requiresRetrieval(executionContract)) {
            return toolCallbacks == null ? List.of() : toolCallbacks;
        }
        if (toolCallbacks == null || toolCallbacks.isEmpty() || hasRetrievableSessionAssets(sessionFileSummary)) {
            return toolCallbacks == null ? List.of() : toolCallbacks;
        }

        List<ToolCallback> filteredToolCallbacks = toolCallbacks.stream()
                .filter(callback -> callback.getToolDefinition() == null
                        || !"SessionFileSearchTool".equals(callback.getToolDefinition().name()))
                .toList();
        if (filteredToolCallbacks.size() != toolCallbacks.size()) {
            log.info("Hiding SessionFileSearchTool because no retrievable session assets are available: sessionId={}",
                    chatSessionId);
        }
        return filteredToolCallbacks;
    }

    private boolean requiresRetrieval(TurnExecutionContract contract) {
        return contract != null
                && contract.retrieval() != null
                && contract.retrieval().mode() == RetrievalMode.REQUIRED_BEFORE_ANSWER;
    }

    private boolean hasRetrievableSessionAssets(String sessionFileSummary) {
        if (!StringUtils.hasText(sessionFileSummary)) {
            return false;
        }
        String normalized = sessionFileSummary.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("attached session files:")
                || normalized.contains("bound knowledge bases:");
    }

    private String resolveQuery(String rewrittenInput, List<Message> memory) {
        if (StringUtils.hasText(rewrittenInput)) {
            return rewrittenInput;
        }
        if (memory == null || memory.isEmpty()) {
            return "";
        }
        for (int i = memory.size() - 1; i >= 0; i--) {
            if (memory.get(i) instanceof UserMessage userMsg) {
                String text = userMsg.getText();
                return StringUtils.hasText(text) ? text : "";
            }
        }
        return "";
    }

    private void appendLatestTurnGuidance(StringBuilder builder, List<Message> memory) {
        // 用户如果追问"你怎么知道的/依据是什么"，模型应解释上一轮依据，而不是盲目重新检索。
        if (!isPriorAnswerBasisFollowUp(memory)) {
            return;
        }
        builder.append("\n").append(promptLoader.load(PromptConstants.AGENT_LATEST_TURN_GUIDANCE)).append("\n");
    }

    private boolean isPriorAnswerBasisFollowUp(List<Message> memory) {
        // 从最近一条用户消息判断是否是"追问上一答复依据"的问题。
        if (memory == null || memory.isEmpty()) {
            return false;
        }

        int latestUserIndex = -1;
        for (int i = memory.size() - 1; i >= 0; i--) {
            if (memory.get(i) instanceof UserMessage) {
                latestUserIndex = i;
                break;
            }
        }
        if (latestUserIndex < 0) {
            return false;
        }

        String latestUserText = memory.get(latestUserIndex).getText();
        if (!looksLikeAnswerBasisQuestion(latestUserText)) {
            return false;
        }

        for (int i = latestUserIndex - 1; i >= 0; i--) {
            Message priorMessage = memory.get(i);
            if (priorMessage instanceof AssistantMessage || priorMessage instanceof ToolResponseMessage) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeAnswerBasisQuestion(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }

        String normalized = text.trim().toLowerCase(Locale.ROOT);
        String compact = normalized.replace(" ", "");

        return compact.contains("怎么知道")
                || compact.contains("怎么直到")
                || compact.contains("怎么得")
                || compact.contains("如何知道")
                || compact.contains("为什么知道")
                || compact.contains("为什么说")
                || compact.contains("为什么这么")
                || compact.contains("怎么来的")
                || compact.contains("依据")
                || compact.contains("来源")
                || compact.contains("根据什么")
                || compact.contains("怎么算")
                || normalized.contains("how did you know")
                || normalized.contains("how do you know")
                || normalized.contains("how did you get")
                || normalized.contains("where did you get")
                || normalized.contains("why did you say")
                || normalized.contains("what was that based on")
                || normalized.contains("what did you base that on")
                || normalized.contains("what source")
                || normalized.contains("which source");
    }

    private void appendIntentBoundaryInstructions(StringBuilder builder,
                                                  boolean hasScopedKnowledgeBases,
                                                  boolean hasNarrowedTools) {
        // 工具或知识库被意图路由收窄时，要把边界写进系统提示词，减少越界调用。
        if (hasNarrowedTools) {
            builder.append(promptLoader.load(PromptConstants.AGENT_INTENT_BOUNDARY_NARROWED)).append("\n");
            if (hasScopedKnowledgeBases) {
                builder.append(promptLoader.load(PromptConstants.AGENT_INTENT_BOUNDARY_KB_ONLY)).append("\n");
            }
            builder.append("\n");
            return;
        }
        if (hasScopedKnowledgeBases) {
            builder.append(promptLoader.load(PromptConstants.AGENT_INTENT_BOUNDARY_KB_ONLY)).append("\n\n");
        }
    }

    private void logResolvedPrompt(IntentResolution intentResolution, String resolvedSystemPrompt) {
        if (!log.isDebugEnabled() || !StringUtils.hasText(resolvedSystemPrompt)) {
            return;
        }
        boolean hasScopedKnowledgeBases = intentResolution != null && !intentResolution.scopedKbIds().isEmpty();
        boolean hasNarrowedTools = intentResolution != null && !intentResolution.allowedTools().isEmpty();
        boolean hasL3Memory = resolvedSystemPrompt.contains("[Relevant Long-Term Memory]");
        log.debug("Resolved system prompt: intentKind={}, scopedKb={}, narrowedTools={}, hasL3Memory={}, promptChars={}",
                intentResolution == null ? "NONE" : intentResolution.kind(),
                hasScopedKnowledgeBases,
                hasNarrowedTools,
                hasL3Memory,
                resolvedSystemPrompt.length());
    }

    private boolean hasMcpTools(List<ToolCallback> toolCallbacks) {
        // MCP 工具来自外部服务，只有出现 mcp_ 工具时才追加额外安全提示。
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return false;
        }
        return toolCallbacks.stream()
                .map(ToolCallback::getToolDefinition)
                .filter(java.util.Objects::nonNull)
                .anyMatch(definition -> StringUtils.hasText(definition.name()) && definition.name().startsWith("mcp_"));
    }

    private boolean hasWebSearchTool(List<ToolCallback> toolCallbacks) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return false;
        }
        return toolCallbacks.stream()
                .map(ToolCallback::getToolDefinition)
                .filter(java.util.Objects::nonNull)
                .anyMatch(definition -> "webSearch".equals(definition.name()));
    }
}
