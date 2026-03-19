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
import java.util.List;

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
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService
                .getChatMessagesBySessionIdRecently(chatSessionId, messageLength);

        List<Message> memory = new ArrayList<>();
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            ChatMessageDTO.MetaData metadata = chatMessageDTO.getMetadata();
            switch (chatMessageDTO.getRole()) {
                case SYSTEM -> {
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    }
                }
                case USER -> {
                    if (StringUtils.hasLength(chatMessageDTO.getContent())) {
                        memory.add(new UserMessage(chatMessageDTO.getContent()));
                    }
                }
                case ASSISTANT -> memory.add(AssistantMessage.builder()
                        .content(chatMessageDTO.getContent())
                        .toolCalls(metadata != null ? metadata.getToolCalls() : null)
                        .build());
                case TOOL -> {
                    if (metadata == null || metadata.getToolResponse() == null) {
                        log.warn("Skip tool message without tool response metadata: {}", chatMessageDTO.getId());
                        continue;
                    }
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(metadata.getToolResponse()))
                            .build());
                }
                default -> {
                    log.error("Unsupported message role: {}, content={}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent());
                    throw new IllegalStateException("Unsupported message role");
                }
            }
        }
        return memory;
    }
}
