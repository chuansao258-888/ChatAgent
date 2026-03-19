package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.model.request.CreateChatSessionRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatSessionRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionsResponse;

/**
 * Facade for chat session lifecycle operations.
 */
public interface ChatSessionFacadeService {

    /**
     * Lists all visible chat sessions for the current scope.
     *
     * @return chat session list response
     */
    GetChatSessionsResponse getChatSessions();

    /**
     * Loads a single chat session by identifier.
     *
     * @param chatSessionId chat session identifier
     * @return session detail response
     */
    GetChatSessionResponse getChatSession(String chatSessionId);

    /**
     * Lists chat sessions associated with a specific agent.
     *
     * @param agentId agent identifier
     * @return chat sessions for the agent
     */
    GetChatSessionsResponse getChatSessionsByAgentId(String agentId);

    /**
     * Creates a new chat session.
     *
     * @param request create session request
     * @return created session payload
     */
    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    /**
     * Deletes a chat session and its related resources.
     *
     * @param chatSessionId chat session identifier
     */
    void deleteChatSession(String chatSessionId);

    /**
     * Updates editable metadata of a chat session.
     *
     * @param chatSessionId chat session identifier
     * @param request update request payload
     */
    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);
}

