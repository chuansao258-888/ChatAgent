package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.agent.application.InternalAssistantService;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.SessionFileMilvusIndexer;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.conversation.converter.ChatSessionConverter;
import com.yulong.chatagent.conversation.model.request.CreateChatSessionRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatSessionRequest;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import com.yulong.chatagent.context.UserContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class ChatSessionFacadeServiceImpl implements ChatSessionFacadeService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final ChatSessionConverter chatSessionConverter;
    private final InternalAssistantService internalAssistantService;
    private final ChatSessionFileRepository chatSessionFileRepository;
    private final DocumentStorageService documentStorageService;
    private final SessionFileMilvusIndexer sessionFileMilvusIndexer;
    private final ResourceAccessGuard resourceAccessGuard;

    @Override
    public ChatSessionVO[] getChatSessions() {
        String userId = requireCurrentUserId();
        List<ChatSessionDTO> chatSessions = chatSessionRepository.findByUserId(userId);
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSessionDTO chatSession : chatSessions) {
            result.add(chatSessionConverter.toVO(chatSession));
        }
        return result.toArray(new ChatSessionVO[0]);
    }

    @Override
    public ChatSessionVO getChatSession(String chatSessionId) {
        ChatSessionDTO chatSession = resourceAccessGuard.assertCanReadSession(UserContext.requireUser(), chatSessionId);
        return chatSessionConverter.toVO(chatSession);
    }

    @Override
    public String createChatSession(CreateChatSessionRequest request) {
        String userId = requireCurrentUserId();
        String agentId = internalAssistantService.getRequiredAssistantId();
        ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(request);
        chatSessionDTO.setAgentId(agentId);
        chatSessionDTO.setUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        chatSessionDTO.setCreatedAt(now);
        chatSessionDTO.setUpdatedAt(now);

        if (!chatSessionRepository.save(chatSessionDTO)) {
            throw new BizException("Failed to create chat session");
        }

        return chatSessionDTO.getId();
    }

    @Override
    @Transactional
    public void deleteChatSession(String chatSessionId) {
        resourceAccessGuard.assertCanReadSession(UserContext.requireUser(), chatSessionId);
        List<ChatSessionFileDTO> sessionFiles = chatSessionFileRepository.findBySessionId(chatSessionId);

        if (!chatSessionRepository.deleteById(chatSessionId)) {
            throw new BizException("Failed to delete chat session");
        }

        // Phase 3C: Clean up historical context summary
        chatSessionSummaryRepository.deleteBySessionId(chatSessionId);

        cleanupDetachedSessionFiles(chatSessionId, sessionFiles);
    }

    @Override
    public void updateChatSession(String chatSessionId, UpdateChatSessionRequest request) {
        ChatSessionDTO existingChatSession = resourceAccessGuard.assertCanReadSession(UserContext.requireUser(), chatSessionId);

        chatSessionConverter.updateDTOFromRequest(existingChatSession, request);
        existingChatSession.setUpdatedAt(LocalDateTime.now());

        if (!chatSessionRepository.update(existingChatSession)) {
            throw new BizException("Failed to update chat session");
        }
    }

    private String requireCurrentUserId() {
        return UserContext.requireUser().getUserId();
    }
    private void cleanupDetachedSessionFiles(String chatSessionId, List<ChatSessionFileDTO> sessionFiles) {
        sessionFileMilvusIndexer.deleteBySessionId(chatSessionId);
        for (ChatSessionFileDTO sessionFile : sessionFiles) {
            try {
                if (sessionFile.getStoragePath() != null) {
                    documentStorageService.deleteFile(sessionFile.getStoragePath());
                }
            } catch (Exception e) {
                log.warn("Failed to delete stored session file: chatSessionId={}, sessionFileId={}, error={}",
                        chatSessionId, sessionFile.getId(), e.getMessage());
            }
        }
    }
}
