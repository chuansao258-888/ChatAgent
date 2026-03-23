package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Restores Spring AI chat memory from persisted conversation messages.
 */
@Component
public class AgentMemoryLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryLoader.class);

    private final ChatMessageFacadeService chatMessageFacadeService;

    public AgentMemoryLoader(ChatMessageFacadeService chatMessageFacadeService) {
        this.chatMessageFacadeService = chatMessageFacadeService;
    }

    /**
     * Loads recent chat history and converts it back into Spring AI message types.
     *
     * @param chatSessionId chat session identifier
     * @param agentConfig persisted agent configuration
     * @return reconstructed chat memory
     */
    public List<Message> load(String chatSessionId, AgentDTO agentConfig) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        int fetchLimit = Math.max(messageLength * 3, messageLength + 10);
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService
                .getChatMessagesBySessionIdRecently(chatSessionId, fetchLimit);

        List<List<Message>> groupedMemory = new ArrayList<>();
        for (int i = 0; i < chatMessages.size(); i++) {
            ChatMessageDTO chatMessageDTO = chatMessages.get(i);
            switch (chatMessageDTO.getRole()) {
                case SYSTEM -> {
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        groupedMemory.add(List.of(new SystemMessage(chatMessageDTO.getContent())));
                    }
                }
                case USER -> {
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        groupedMemory.add(List.of(new UserMessage(chatMessageDTO.getContent())));
                    }
                }
                case ASSISTANT -> {
                    ToolCallSequenceResult toolCallSequenceResult = collectAssistantSequence(chatMessages, i);
                    if (toolCallSequenceResult != null) {
                        groupedMemory.add(toolCallSequenceResult.messages());
                        i = toolCallSequenceResult.lastConsumedIndex();
                    }
                }
                case TOOL -> {
                    log.warn("Skip orphan tool message while rebuilding memory: {}", chatMessageDTO.getId());
                }
                default -> {
                    log.error("Unsupported message role: {}, content={}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent());
                    throw new IllegalStateException("Unsupported message role");
                }
            }
        }

        return trimToRecentGroups(groupedMemory, messageLength);
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

    private List<Message> trimToRecentGroups(List<List<Message>> groupedMemory, int maxMessages) {
        if (groupedMemory.isEmpty()) {
            return List.of();
        }

        List<Message> trimmed = new ArrayList<>();
        int currentSize = 0;
        for (int i = groupedMemory.size() - 1; i >= 0; i--) {
            List<Message> group = groupedMemory.get(i);
            if (group.isEmpty()) {
                continue;
            }
            if (!trimmed.isEmpty() && currentSize + group.size() > maxMessages) {
                break;
            }
            trimmed.addAll(0, group);
            currentSize += group.size();
        }
        return trimmed;
    }

    private record ToolCallSequenceResult(List<Message> messages, int lastConsumedIndex) {
    }
}
