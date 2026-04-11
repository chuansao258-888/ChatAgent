package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.model.request.CreateChatSessionRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatSessionRequest;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;

/**
 * Facade for chat session lifecycle operations.
 */
public interface ChatSessionFacadeService {

    /**
     * Lists all visible chat sessions for the current scope.
     */
    ChatSessionVO[] getChatSessions();

    /**
     * Loads a single chat session by identifier.
     */
    ChatSessionVO getChatSession(String chatSessionId);

    /**
     * Creates a new chat session and returns its id.
     */
    String createChatSession(CreateChatSessionRequest request);

    /**
     * Deletes a chat session and its related resources.
     */
    void deleteChatSession(String chatSessionId);

    /**
     * Updates editable metadata of a chat session.
     */
    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);
}
