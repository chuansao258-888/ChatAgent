package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.conversation.converter.ChatSessionConverter;
import com.yulong.chatagent.conversation.model.request.CreateChatSessionRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatSessionRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionsResponse;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatSessionFacadeServiceImpl implements ChatSessionFacadeService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionConverter chatSessionConverter;

    @Override
    public GetChatSessionsResponse getChatSessions() {
        List<ChatSessionDTO> chatSessions = chatSessionRepository.findAll();
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSessionDTO chatSession : chatSessions) {
            result.add(chatSessionConverter.toVO(chatSession));
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public GetChatSessionResponse getChatSession(String chatSessionId) {
        ChatSessionDTO chatSession = chatSessionRepository.findById(chatSessionId);
        if (chatSession == null) {
            throw new BizException("Chat session not found: " + chatSessionId);
        }

        return GetChatSessionResponse.builder()
                .chatSession(chatSessionConverter.toVO(chatSession))
                .build();
    }

    @Override
    public GetChatSessionsResponse getChatSessionsByAgentId(String agentId) {
        List<ChatSessionDTO> chatSessions = chatSessionRepository.findByAgentId(agentId);
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSessionDTO chatSession : chatSessions) {
            result.add(chatSessionConverter.toVO(chatSession));
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public CreateChatSessionResponse createChatSession(CreateChatSessionRequest request) {
        ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(request);
        LocalDateTime now = LocalDateTime.now();
        chatSessionDTO.setCreatedAt(now);
        chatSessionDTO.setUpdatedAt(now);

        if (!chatSessionRepository.save(chatSessionDTO)) {
            throw new BizException("Failed to create chat session");
        }

        return CreateChatSessionResponse.builder()
                .chatSessionId(chatSessionDTO.getId())
                .build();
    }

    @Override
    public void deleteChatSession(String chatSessionId) {
        ChatSessionDTO chatSession = chatSessionRepository.findById(chatSessionId);
        if (chatSession == null) {
            throw new BizException("Chat session not found: " + chatSessionId);
        }

        if (!chatSessionRepository.deleteById(chatSessionId)) {
            throw new BizException("Failed to delete chat session");
        }
    }

    @Override
    public void updateChatSession(String chatSessionId, UpdateChatSessionRequest request) {
        ChatSessionDTO existingChatSession = chatSessionRepository.findById(chatSessionId);
        if (existingChatSession == null) {
            throw new BizException("Chat session not found: " + chatSessionId);
        }

        chatSessionConverter.updateDTOFromRequest(existingChatSession, request);
        existingChatSession.setUpdatedAt(LocalDateTime.now());

        if (!chatSessionRepository.update(existingChatSession)) {
            throw new BizException("Failed to update chat session");
        }
    }
}

