package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;

/**
 * Coordinates one user turn from the conversation entrypoint.
 * <p>
 * This service is intentionally placed above message CRUD so that turn-level
 * concerns such as validation, session checks, event dispatch, and later
 * planning/retrieval steps can evolve without polluting the message facade.
 */
public interface ConversationOrchestratorService {

    /**
     * Handles one user input turn and starts the downstream conversation flow.
     *
     * @param request user message creation request
     * @return response describing the created user-side message record
     */
    CreateChatMessageResponse handleUserTurn(CreateChatMessageRequest request);
}
