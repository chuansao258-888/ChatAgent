package com.yulong.chatagent.access;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;

/**
 * Centralizes resource-level access checks so controllers and services do not
 * duplicate ownership and existence rules.
 */
public interface ResourceAccessGuard {

    ChatSessionDTO assertCanReadSession(LoginUser user, String sessionId);

    KnowledgeBaseDTO assertCanManageKnowledgeBase(LoginUser user, String knowledgeBaseId);
}
