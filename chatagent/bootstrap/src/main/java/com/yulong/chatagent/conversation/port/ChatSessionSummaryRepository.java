package com.yulong.chatagent.conversation.port;

import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;

/**
 * Persistence port for rolling chat-session summaries.
 */
public interface ChatSessionSummaryRepository {

    ChatSessionSummaryDTO findBySessionId(String sessionId);

    boolean saveOrUpdate(ChatSessionSummaryDTO summary);

    boolean deleteBySessionId(String sessionId);
}
