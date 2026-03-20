package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.admin.port.AgentRepository;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.conversation.converter.ChatSessionConverter;
import com.yulong.chatagent.conversation.model.request.CreateChatSessionRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatSessionRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionsResponse;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import com.yulong.chatagent.context.UserContext;
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
    private final AgentRepository agentRepository;

    @Override
    public GetChatSessionsResponse getChatSessions() {
        String userId = requireCurrentUserId();
        List<ChatSessionDTO> chatSessions = chatSessionRepository.findByUserId(userId);
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
        ChatSessionDTO chatSession = requireOwnedSession(chatSessionId, requireCurrentUserId());

        return GetChatSessionResponse.builder()
                .chatSession(chatSessionConverter.toVO(chatSession))
                .build();
    }

    @Override
    public GetChatSessionsResponse getChatSessionsByAgentId(String agentId) {
        String userId = requireCurrentUserId();
        requireOwnedAgent(agentId, userId);
        List<ChatSessionDTO> chatSessions = chatSessionRepository.findByAgentIdAndUserId(agentId, userId);
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
        String userId = requireCurrentUserId();
        requireOwnedAgent(request.getAgentId(), userId);
        ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(request);
        chatSessionDTO.setUserId(userId);
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
        requireOwnedSession(chatSessionId, requireCurrentUserId());

        if (!chatSessionRepository.deleteById(chatSessionId)) {
            throw new BizException("Failed to delete chat session");
        }
    }

    @Override
    public void updateChatSession(String chatSessionId, UpdateChatSessionRequest request) {
        ChatSessionDTO existingChatSession = requireOwnedSession(chatSessionId, requireCurrentUserId());

        chatSessionConverter.updateDTOFromRequest(existingChatSession, request);
        existingChatSession.setUpdatedAt(LocalDateTime.now());

        if (!chatSessionRepository.update(existingChatSession)) {
            throw new BizException("Failed to update chat session");
        }
    }

    private String requireCurrentUserId() {
        return UserContext.requireUser().getUserId();
    }

    private ChatSessionDTO requireOwnedSession(String chatSessionId, String userId) {
        ChatSessionDTO chatSession = chatSessionRepository.findById(chatSessionId);
        if (chatSession == null || !userId.equals(chatSession.getUserId())) {
            throw new BizException("Chat session not found: " + chatSessionId);
        }
        return chatSession;
    }

    private void requireOwnedAgent(String agentId, String userId) {
        AgentDTO agent = agentRepository.findById(agentId);
        if (agent == null || !userId.equals(agent.getUserId())) {
            throw new BizException("Agent not found: " + agentId);
        }
    }
}

