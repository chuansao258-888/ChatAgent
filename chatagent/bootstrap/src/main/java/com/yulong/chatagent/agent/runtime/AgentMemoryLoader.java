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
 * Restores Spring AI chat memory from persisted conversation messages.
 * Uses a token-budget-based sliding window for L1 memory.
 */
@Component
public class AgentMemoryLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryLoader.class);

    /**
     * Leaves headroom for tokenizer differences between persistence-time estimates and runtime models.
     */
    private static final double TOKEN_SAFETY_MARGIN = 0.8;

    private final ChatMessageRepository chatMessageRepository;

    public AgentMemoryLoader(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * Loads recent chat history and converts it back into Spring AI message types,
     * respecting the token budget for L1 memory.
     *
     * @param chatSessionId chat session identifier
     * @param agentConfig persisted agent configuration
     * @return reconstructed chat memory
     */
    public List<Message> load(String chatSessionId, AgentDTO agentConfig) {
        int tokenBudget = agentConfig.getChatOptions().getTokenBudget() != null
                ? agentConfig.getChatOptions().getTokenBudget()
                : 4000;
        int effectiveBudget = (int) (tokenBudget * TOKEN_SAFETY_MARGIN);

        // Fetch a generous amount of recent messages to find enough full turns.
        List<ChatMessageDTO> chatMessages = chatMessageRepository.findRecentBySessionId(chatSessionId, 100);
        if (chatMessages.isEmpty()) {
            return List.of();
        }

        // Group by turn_id to maintain atomic turns.
        Map<String, List<ChatMessageDTO>> groupedTurns = new LinkedHashMap<>();
        for (ChatMessageDTO msg : chatMessages) {
            if (StringUtils.hasText(msg.getTurnId())) {
                groupedTurns.computeIfAbsent(msg.getTurnId(), ignored -> new ArrayList<>()).add(msg);
            }
        }

        List<String> turnIds = new ArrayList<>(groupedTurns.keySet());
        List<List<Message>> selectedTurnMessages = new ArrayList<>();
        int currentTokenCount = 0;

        // Iterate backwards from the most recent turns.
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
     * Estimates token count for a list of messages.
     * Rule of thumb: 1 Chinese character is treated as 2 tokens and 1 other character as 1 token.
     */
    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            String content = message.getText();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            for (char c : content.toCharArray()) {
                if (isChinese(c)) {
                    total += 2;
                } else {
                    total += 1;
                }
            }
        }
        return total;
    }

    private boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    /**
     * Holds a fully rebuilt assistant turn and the last source index consumed while scanning tool responses.
     */
    private record ToolCallSequenceResult(List<Message> messages, int lastConsumedIndex) {
    }
}
