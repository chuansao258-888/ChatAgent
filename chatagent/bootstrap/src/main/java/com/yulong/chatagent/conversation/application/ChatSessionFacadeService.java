package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.model.request.CreateChatSessionRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatSessionRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionsResponse;

public interface ChatSessionFacadeService {

    GetChatSessionsResponse getChatSessions();

    GetChatSessionResponse getChatSession(String chatSessionId);

    GetChatSessionsResponse getChatSessionsByAgentId(String agentId);

    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    void deleteChatSession(String chatSessionId);

    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);
}

