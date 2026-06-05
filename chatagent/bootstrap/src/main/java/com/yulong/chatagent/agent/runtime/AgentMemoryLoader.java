package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
 * 这里采用 token 预算 + turn 级滑动窗口：从最近轮次往前装载，
 * 并保证一个 turn 要么完整进入上下文，要么整体丢弃，不截断工具调用序列。
 */
@Component
public class AgentMemoryLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryLoader.class);

    /**
     * 给不同模型 tokenizer 的估算误差留余量，避免刚好打满上下文窗口。
     */
    private static final double TOKEN_SAFETY_MARGIN = 0.8;

    private final ChatMessageRepository chatMessageRepository;

    public AgentMemoryLoader(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * 加载最近历史消息，并转换为 Spring AI 的 Message 类型。
     *
     * @param chatSessionId 会话 ID
     * @param agentConfig Agent 配置
     * @return 可直接放入 ChatMemory 的消息列表
     */
    public List<Message> load(String chatSessionId, AgentDTO agentConfig) {
        // tokenBudget 来自 Agent 配置；没有配置时使用保守默认值。
        int tokenBudget = agentConfig.getChatOptions().getTokenBudget() != null
                ? agentConfig.getChatOptions().getTokenBudget()
                : 4000;
        int effectiveBudget = (int) (tokenBudget * TOKEN_SAFETY_MARGIN);

        // 先多取一些最近消息，再按 turn 级别向前筛选，避免数据库分页直接切断上下文。
        List<ChatMessageDTO> chatMessages = chatMessageRepository.findRecentBySessionId(chatSessionId, 100);
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

        List<String> turnIds = new ArrayList<>(groupedTurns.keySet());
        List<List<Message>> selectedTurnMessages = new ArrayList<>();
        int currentTokenCount = 0;

        // 从最近的 turn 开始往前装载；一旦超过预算，就停止继续向更早历史扩展。
        for (int i = turnIds.size() - 1; i >= 0; i--) {
            String turnId = turnIds.get(i);
            List<ChatMessageDTO> turnMessages = groupedTurns.get(turnId);
            List<Message> springAiMessages = convertToSpringAiMessages(turnMessages);

            int turnTokens = estimateTokens(springAiMessages);
            if (!selectedTurnMessages.isEmpty() && currentTokenCount + turnTokens > effectiveBudget) {
                break;
            }

            selectedTurnMessages.add(0, springAiMessages);
            currentTokenCount += turnTokens;
        }

        if (selectedTurnMessages.size() == 1 && currentTokenCount > effectiveBudget) {
            log.warn("Single turn exceeds L1 token budget: sessionId={}, turnTokens={}, budget={}",
                    chatSessionId,
                    currentTokenCount,
                    effectiveBudget);
        }

        return selectedTurnMessages.stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
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

            sequence.add(ToolResponseMessage.builder()
                    .responses(List.of(toolResponse))
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
     */
    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += com.yulong.chatagent.conversation.summary.TokenEstimator.estimateTokens(message.getText());
        }
        return total;
    }

    /**
     * 保存一组还原后的 assistant/tool_response 序列，以及扫描时消耗到的原始下标。
     */
    private record ToolCallSequenceResult(List<Message> messages, int lastConsumedIndex) {
    }
}
