package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.response.GetChatMessagesResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatMessageFacadeServiceImpl implements ChatMessageFacadeService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageConverter chatMessageConverter;
    private final ResourceAccessGuard resourceAccessGuard;

    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String sessionId) {
        requireOwnedSessionIfAuthenticated(sessionId);
        List<ChatMessageDTO> chatMessages = chatMessageRepository.findBySessionId(sessionId);
        List<ChatMessageVO> result = new ArrayList<>();
        for (ChatMessageDTO chatMessage : chatMessages) {
            result.add(chatMessageConverter.toVO(chatMessage));
        }

        return GetChatMessagesResponse.builder()
                .chatMessages(result.toArray(new ChatMessageVO[0]))
                .build();
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit) {
        requireOwnedSessionIfAuthenticated(sessionId);
        return chatMessageRepository.findRecentBySessionId(sessionId, limit);
    }

    @Override
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        ChatMessageDTO chatMessage = doCreateChatMessage(request);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO) {
        ChatMessageDTO chatMessage = doCreateChatMessage(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        ChatMessageDTO chatMessage = doCreateChatMessage(request);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent) {
        ChatMessageDTO existingChatMessage = chatMessageRepository.findById(chatMessageId);
        if (existingChatMessage == null) {
            throw new BizException("Chat message not found: " + chatMessageId);
        }
        requireOwnedSessionIfAuthenticated(existingChatMessage.getSessionId());

        String currentContent = existingChatMessage.getContent() != null ? existingChatMessage.getContent() : "";
        ChatMessageDTO updatedChatMessage = ChatMessageDTO.builder()
                .id(existingChatMessage.getId())
                .sessionId(existingChatMessage.getSessionId())
                .turnId(existingChatMessage.getTurnId())
                .role(existingChatMessage.getRole())
                .content(currentContent + appendContent)
                .metadata(existingChatMessage.getMetadata())
                .seqNo(existingChatMessage.getSeqNo())
                .createdAt(existingChatMessage.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        if (!chatMessageRepository.update(updatedChatMessage)) {
            throw new BizException("Failed to append chat message");
        }

        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessageId)
                .build();
    }

    @Override
    public void deleteChatMessage(String chatMessageId) {
        ChatMessageDTO chatMessage = chatMessageRepository.findById(chatMessageId);
        if (chatMessage == null) {
            throw new BizException("Chat message not found: " + chatMessageId);
        }
        requireOwnedSessionIfAuthenticated(chatMessage.getSessionId());

        if (!chatMessageRepository.deleteById(chatMessageId)) {
            throw new BizException("Failed to delete chat message");
        }
    }

    @Override
    public void deleteAssistantAndToolMessagesForTurn(String sessionId, String turnId) {
        // Rollbacks are typically triggered by background task retries where no user context exists,
        // so we bypass session ownership checks or rely on internal system-level authority.
        List<String> roles = List.of(
                ChatMessageDTO.RoleType.ASSISTANT.getRole(),
                ChatMessageDTO.RoleType.TOOL.getRole()
        );
        chatMessageRepository.deleteBySessionIdAndTurnIdAndRoles(sessionId, turnId, roles);
    }

    @Override
    public void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request) {
        ChatMessageDTO existingChatMessage = chatMessageRepository.findById(chatMessageId);
        if (existingChatMessage == null) {
            throw new BizException("Chat message not found: " + chatMessageId);
        }
        requireOwnedSessionIfAuthenticated(existingChatMessage.getSessionId());

        chatMessageConverter.updateDTOFromRequest(existingChatMessage, request);
        existingChatMessage.setUpdatedAt(LocalDateTime.now());

        if (!chatMessageRepository.update(existingChatMessage)) {
            throw new BizException("Failed to update chat message");
        }
    }

    private ChatMessageDTO doCreateChatMessage(CreateChatMessageRequest request) {
        return doCreateChatMessage(chatMessageConverter.toDTO(request));
    }

    private ChatMessageDTO doCreateChatMessage(ChatMessageDTO chatMessageDTO) {
        requireOwnedSessionIfAuthenticated(chatMessageDTO.getSessionId());
        LocalDateTime now = LocalDateTime.now();
        chatMessageDTO.setCreatedAt(now);
        chatMessageDTO.setUpdatedAt(now);

        if (!chatMessageRepository.save(chatMessageDTO)) {
            throw new BizException("Failed to create chat message");
        }
        return chatMessageDTO;
    }

    private void requireOwnedSessionIfAuthenticated(String sessionId) {
        LoginUser loginUser = UserContext.get();
        if (loginUser == null) {
            return;
        }
        resourceAccessGuard.assertCanReadSession(loginUser, sessionId);
    }
}
