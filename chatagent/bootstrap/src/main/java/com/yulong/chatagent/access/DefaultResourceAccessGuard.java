package com.yulong.chatagent.access;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ClientException;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Default resource-level access rules for chat sessions and knowledge bases.
 */
@Component
@RequiredArgsConstructor
public class DefaultResourceAccessGuard implements ResourceAccessGuard {

    private final ChatSessionRepository chatSessionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Override
    public ChatSessionDTO assertCanReadSession(LoginUser user, String sessionId) {
        Assert.notNull(user, "LoginUser must not be null");
        ChatSessionDTO chatSession = chatSessionRepository.findById(sessionId);
        if (chatSession == null || !user.getUserId().equals(chatSession.getUserId())) {
            throw new BizException("Chat session not found: " + sessionId);
        }
        return chatSession;
    }

    @Override
    public KnowledgeBaseDTO assertCanManageKnowledgeBase(LoginUser user, String knowledgeBaseId) {
        Assert.notNull(user, "LoginUser must not be null");
        if (!UserRole.ADMIN.matches(user.getRole())) {
            throw new ClientException(BaseErrorCode.FORBIDDEN, "Admin access required");
        }
        KnowledgeBaseDTO knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new BizException("Knowledge base not found: " + knowledgeBaseId);
        }
        return knowledgeBase;
    }
}
