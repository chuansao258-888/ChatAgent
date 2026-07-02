package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从数据库消息恢复 Spring AI 可用的 L1 短期记忆。
 * <p>
 * 这里采用 token 预算 + turn 级滑动窗口：预算足够时保留完整 turn；预算受压时
 * 缩短 assistant 文本并优先保留用户更新，同时始终保持工具调用序列完整或整体省略。
 */
@Component
public class AgentMemoryLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryLoader.class);

    /**
     * 给不同模型 tokenizer 的估算误差留余量，避免刚好打满上下文窗口。
     */
    private static final double TOKEN_SAFETY_MARGIN = 0.8;
    private static final int COMPACTED_ASSISTANT_MAX_CHARS = 160;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final ToolResultCompactor toolResultCompactor;
    private final int l1WindowTurns;
    private final int globalTokenBudget;

    public AgentMemoryLoader(ChatMessageRepository chatMessageRepository,
                             ChatSessionSummaryRepository chatSessionSummaryRepository,
                             ToolResultCompactor toolResultCompactor,
                             @Value("${chatagent.memory.l1-window-turns:48}") int l1WindowTurns,
                             @Value("${chatagent.memory.l1-token-budget:256000}") int globalTokenBudget) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
        this.toolResultCompactor = toolResultCompactor;
        this.l1WindowTurns = Math.max(l1WindowTurns, 1);
        this.globalTokenBudget = Math.max(globalTokenBudget, 1);
    }

    /**
     * 加载最近历史消息，并转换为 Spring AI 的 Message 类型。
     *
     * @param chatSessionId 会话 ID
     * @param agentConfig Agent 配置
     * @return 可直接放入 ChatMemory 的消息列表
     */
    public List<Message> load(String chatSessionId, AgentDTO agentConfig) {
        // 全局 L1 预算是运行时下限，避免旧 Agent 行中的小预算继续压低整个应用的窗口。
        int configuredTokenBudget = agentConfig.getChatOptions().getTokenBudget() != null
                ? agentConfig.getChatOptions().getTokenBudget()
                : globalTokenBudget;
        int tokenBudget = Math.max(configuredTokenBudget, globalTokenBudget);
        int effectiveBudget = (int) (tokenBudget * TOKEN_SAFETY_MARGIN);

        // 先多取一些最近消息，再按 turn 级别向前筛选，避免数据库分页直接切断上下文。
        // 已经被 L2 watermark 覆盖的旧 turn 只通过 Historical Context Summary 进入模型，
        // 不再作为 raw L1 memory 重复发送。
        long summarizedUntilSeqNo = summarizedUntilSeqNo(chatSessionId);
        List<ChatMessageDTO> chatMessages = chatMessageRepository.findRecentBySessionId(chatSessionId, 100)
                .stream()
                .filter(message -> message.getSeqNo() == null || message.getSeqNo() > summarizedUntilSeqNo)
                .toList();
        if (chatMessages.isEmpty()) {
            return List.of();
        }

        // 按 turn_id 分组，保证用户消息、assistant tool_call、tool_response、最终回答保持原子性。
        Map<String, List<ChatMessageDTO>> groupedTurns = new LinkedHashMap<>();
        for (ChatMessageDTO msg : chatMessages) {
            if (StringUtils.hasText(msg.getTurnId())) {
                groupedTurns.computeIfAbsent(msg.getTurnId(), ignored -> new ArrayList<>()).add(msg);
            }
        }
        if (groupedTurns.isEmpty()) {
            return List.of();
        }

        List<String> turnIds = new ArrayList<>(groupedTurns.keySet());
        List<ChatMessageDTO> latestTurn = groupedTurns.get(turnIds.get(turnIds.size() - 1));
        int tailTurnLimit = l1WindowTurns + (isOpenUserTurn(latestTurn) ? 1 : 0);
        int tailStart = Math.max(0, turnIds.size() - tailTurnLimit);
        List<List<ChatMessageDTO>> tailTurns = turnIds.subList(tailStart, turnIds.size()).stream()
                .map(groupedTurns::get)
                .toList();
        List<List<Message>> fullTurns = tailTurns.stream()
                .map(this::convertToSpringAiMessages)
                .toList();

        int fullTokenCount = estimateTurnTokens(fullTurns);
        if (fullTokenCount <= effectiveBudget) {
            return flattenTurns(fullTurns);
        }

        List<List<Message>> compactTurns = tailTurns.stream()
                .map(this::convertToBudgetCompactMessages)
                .toList();
        List<List<Message>> selectedTurnMessages;
        if (estimateTurnTokens(compactTurns) <= effectiveBudget) {
            selectedTurnMessages = new ArrayList<>(compactTurns);
        } else {
            List<List<Message>> userOnlyTurns = tailTurns.stream()
                    .map(this::convertToUserMessages)
                    .toList();
            if (estimateTurnTokens(userOnlyTurns) > effectiveBudget) {
                return selectMostRecentTurns(fullTurns, effectiveBudget);
            }
            selectedTurnMessages = upgradeTurnsWithinBudget(userOnlyTurns, compactTurns, effectiveBudget);
        }
        selectedTurnMessages = upgradeTurnsWithinBudget(selectedTurnMessages, fullTurns, effectiveBudget);

        int currentTokenCount = estimateTurnTokens(selectedTurnMessages);
        if (selectedTurnMessages.size() == 1 && currentTokenCount > effectiveBudget) {
            log.warn("Single turn exceeds L1 token budget: sessionId={}, turnTokens={}, budget={}",
                    chatSessionId,
                    currentTokenCount,
                    effectiveBudget);
        }
        return flattenTurns(selectedTurnMessages);
    }

    private boolean isOpenUserTurn(List<ChatMessageDTO> turnMessages) {
        boolean hasUser = turnMessages.stream()
                .anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.USER);
        boolean hasAssistant = turnMessages.stream()
                .anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT);
        return hasUser && !hasAssistant;
    }

    private long summarizedUntilSeqNo(String chatSessionId) {
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(chatSessionId);
        return summary == null || summary.getSummarizedUntilSeqNo() == null
                ? 0L
                : summary.getSummarizedUntilSeqNo();
    }

    private List<Message> convertToBudgetCompactMessages(List<ChatMessageDTO> turnMessages) {
        List<Message> compacted = new ArrayList<>();
        for (Message message : convertToSpringAiMessages(turnMessages)) {
            if (message instanceof UserMessage || message instanceof SystemMessage) {
                compacted.add(message);
            } else if (message instanceof AssistantMessage assistantMessage
                    && assistantMessage.getToolCalls().isEmpty()
                    && StringUtils.hasText(assistantMessage.getText())) {
                compacted.add(new AssistantMessage(compactAssistantText(assistantMessage.getText())));
            }
        }
        return compacted;
    }

    private List<Message> convertToUserMessages(List<ChatMessageDTO> turnMessages) {
        List<Message> userMessages = new ArrayList<>();
        for (ChatMessageDTO message : turnMessages) {
            if (message.getRole() == ChatMessageDTO.RoleType.USER && StringUtils.hasText(message.getContent())) {
                userMessages.add(new UserMessage(message.getContent()));
            } else if (message.getRole() == ChatMessageDTO.RoleType.SYSTEM && StringUtils.hasText(message.getContent())) {
                userMessages.add(new SystemMessage(message.getContent()));
            }
        }
        return userMessages;
    }

    private String compactAssistantText(String content) {
        String trimmed = content.trim();
        if (trimmed.length() <= COMPACTED_ASSISTANT_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, COMPACTED_ASSISTANT_MAX_CHARS - 3) + "...";
    }

    private List<List<Message>> upgradeTurnsWithinBudget(List<List<Message>> baseline,
                                                          List<List<Message>> richerTurns,
                                                          int effectiveBudget) {
        List<List<Message>> upgraded = new ArrayList<>(baseline);
        int currentTokenCount = estimateTurnTokens(upgraded);
        for (int i = upgraded.size() - 1; i >= 0; i--) {
            List<Message> current = upgraded.get(i);
            List<Message> richer = richerTurns.get(i);
            int candidateTokenCount = currentTokenCount - estimateTokens(current) + estimateTokens(richer);
            if (candidateTokenCount <= effectiveBudget) {
                upgraded.set(i, richer);
                currentTokenCount = candidateTokenCount;
                continue;
            }

            List<Message> toolCompacted = applyBudgetCompaction(richer);
            int toolCompactedTokenCount = currentTokenCount - estimateTokens(current) + estimateTokens(toolCompacted);
            if (toolCompactedTokenCount < candidateTokenCount && toolCompactedTokenCount <= effectiveBudget) {
                upgraded.set(i, toolCompacted);
                currentTokenCount = toolCompactedTokenCount;
            }
        }
        return upgraded;
    }

    private List<Message> selectMostRecentTurns(List<List<Message>> turns, int effectiveBudget) {
        List<List<Message>> selected = new ArrayList<>();
        int currentTokenCount = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            List<Message> turn = turns.get(i);
            int turnTokens = estimateTokens(turn);
            if (turnTokens > effectiveBudget) {
                turn = applyBudgetCompaction(turn);
                turnTokens = estimateTokens(turn);
            }
            if (!selected.isEmpty() && currentTokenCount + turnTokens > effectiveBudget) {
                List<Message> toolCompacted = applyBudgetCompaction(turn);
                int toolCompactedTokens = estimateTokens(toolCompacted);
                if (toolCompactedTokens < turnTokens
                        && currentTokenCount + toolCompactedTokens <= effectiveBudget) {
                    turn = toolCompacted;
                    turnTokens = toolCompactedTokens;
                } else {
                    break;
                }
            }
            selected.add(0, turn);
            currentTokenCount += turnTokens;
        }
        return flattenTurns(selected);
    }

    private int estimateTurnTokens(List<List<Message>> turns) {
        return turns.stream().mapToInt(this::estimateTokens).sum();
    }

    private List<Message> flattenTurns(List<List<Message>> turns) {
        return turns.stream().flatMap(List::stream).collect(Collectors.toList());
    }

    private List<Message> convertToSpringAiMessages(List<ChatMessageDTO> chatMessages) {
        // 数据库里是统一 DTO，模型调用需要还原成 Spring AI 的 User/Assistant/ToolResponse 类型。
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < chatMessages.size(); i++) {
            ChatMessageDTO dto = chatMessages.get(i);
            switch (dto.getRole()) {
                case SYSTEM -> result.add(new SystemMessage(dto.getContent()));
                case USER -> result.add(new UserMessage(dto.getContent()));
                case ASSISTANT -> {
                    ToolCallSequenceResult sequence = collectAssistantSequence(chatMessages, i);
                    if (sequence != null) {
                        result.addAll(sequence.messages());
                        i = sequence.lastConsumedIndex();
                    }
                }
                case TOOL -> log.warn("Skip orphan tool message: {}", dto.getId());
            }
        }
        return result;
    }

    private ToolCallSequenceResult collectAssistantSequence(List<ChatMessageDTO> chatMessages, int assistantIndex) {
        // 如果 assistant 消息包含 tool_calls，它后面必须跟完整 tool_response；
        // 否则该工具调用序列不能放进模型上下文。
        ChatMessageDTO assistantDto = chatMessages.get(assistantIndex);
        List<AssistantMessage.ToolCall> toolCalls = getToolCalls(assistantDto);
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(assistantDto.getContent())
                .toolCalls(toolCalls)
                .build();

        if (toolCalls.isEmpty()) {
            return new ToolCallSequenceResult(List.of(assistantMessage), assistantIndex);
        }

        List<Message> sequence = new ArrayList<>();
        sequence.add(assistantMessage);

        Set<String> requiredToolCallIds = toolCalls.stream()
                .map(AssistantMessage.ToolCall::id)
                .filter(StringUtils::hasLength)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> resolvedToolCallIds = new HashSet<>();

        int lastConsumedIndex = assistantIndex;
        // 只收集紧跟在 assistant 后面的 TOOL 消息，遇到其他角色就认为工具响应序列结束。
        for (int i = assistantIndex + 1; i < chatMessages.size(); i++) {
            ChatMessageDTO nextMessage = chatMessages.get(i);
            if (nextMessage.getRole() != ChatMessageDTO.RoleType.TOOL) {
                break;
            }

            ChatMessageDTO.MetaData metadata = nextMessage.getMetadata();
            if (metadata == null || metadata.getToolResponse() == null) {
                log.warn("Skip tool message without tool response metadata: {}", nextMessage.getId());
                lastConsumedIndex = i;
                continue;
            }

            ToolResponseMessage.ToolResponse toolResponse = metadata.getToolResponse();
            if (!requiredToolCallIds.isEmpty()
                    && StringUtils.hasLength(toolResponse.id())
                    && !requiredToolCallIds.contains(toolResponse.id())) {
                log.warn("Skip mismatched tool response while rebuilding memory: messageId={}, toolCallId={}",
                        nextMessage.getId(), toolResponse.id());
                lastConsumedIndex = i;
                continue;
            }

            // Apply deterministic microcompact to oversized tool responses (no DB mutation).
            String responseContent = toolResultCompactor.compactIfNeeded(toolResponse.responseData());
            ToolResponseMessage.ToolResponse compactedResponse = new ToolResponseMessage.ToolResponse(
                    toolResponse.id(), toolResponse.name(), responseContent);
            sequence.add(ToolResponseMessage.builder()
                    .responses(List.of(compactedResponse))
                    .build());
            if (StringUtils.hasLength(toolResponse.id())) {
                resolvedToolCallIds.add(toolResponse.id());
            }
            lastConsumedIndex = i;
        }

        boolean hasAnyToolResponse = sequence.size() > 1;
        boolean allToolCallsResolved = requiredToolCallIds.isEmpty() || resolvedToolCallIds.containsAll(requiredToolCallIds);
        if (!hasAnyToolResponse || !allToolCallsResolved) {
            // 不完整工具序列宁可整组丢弃，也不要传给模型造成“有调用无响应”的非法历史。
            log.warn("Skip incomplete assistant tool-call sequence while rebuilding memory: assistantMessageId={}, required={}, resolved={}",
                    assistantDto.getId(), requiredToolCallIds.size(), resolvedToolCallIds.size());
            return new ToolCallSequenceResult(List.of(), lastConsumedIndex);
        }

        return new ToolCallSequenceResult(sequence, lastConsumedIndex);
    }

    private List<AssistantMessage.ToolCall> getToolCalls(ChatMessageDTO chatMessageDTO) {
        ChatMessageDTO.MetaData metadata = chatMessageDTO.getMetadata();
        return metadata == null || metadata.getToolCalls() == null
                ? List.of()
                : metadata.getToolCalls();
    }

    /**
     * 粗略估算消息 token 数。
     * <p>
     * 这里不追求精确 tokenizer，只用于 L1 记忆窗口裁剪：中文字符按 2 token，
     * 其他字符按 1 token，配合 80% 安全系数使用。
     * <p>
     * 委托给 {@link com.yulong.chatagent.conversation.summary.TokenEstimator} 保持 L1/L2 估算一致。
     * 注意：{@code ToolResponseMessage.getText()} 返回空串，实际内容在
     * {@code responseData()} 里，所以需要单独处理。
     */
    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolMsg) {
                for (ToolResponseMessage.ToolResponse response : toolMsg.getResponses()) {
                    total += com.yulong.chatagent.conversation.summary.TokenEstimator
                            .estimateTokens(response.responseData());
                }
            } else {
                total += com.yulong.chatagent.conversation.summary.TokenEstimator
                        .estimateTokens(message.getText());
            }
        }
        return total;
    }

    /**
     * Apply budget-pressure compaction to all tool responses in a turn.
     * Force-compacts regardless of char threshold, to help the turn fit within budget.
     */
    private List<Message> applyBudgetCompaction(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolMsg) {
                List<ToolResponseMessage.ToolResponse> compacted = new ArrayList<>();
                for (ToolResponseMessage.ToolResponse response : toolMsg.getResponses()) {
                    String compactedData = toolResultCompactor.compactForBudget(response.responseData());
                    compacted.add(new ToolResponseMessage.ToolResponse(
                            response.id(), response.name(), compactedData));
                }
                result.add(ToolResponseMessage.builder().responses(compacted).build());
            } else {
                result.add(message);
            }
        }
        return result;
    }

    /**
     * 保存一组还原后的 assistant/tool_response 序列，以及扫描时消耗到的原始下标。
     */
    private record ToolCallSequenceResult(List<Message> messages, int lastConsumedIndex) {
    }
}
