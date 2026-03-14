package com.yulong.chatagent.service;

import com.yulong.chatagent.model.dto.ChatMessageDTO;
import com.yulong.chatagent.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.model.response.GetChatMessagesResponse;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    void deleteChatMessage(String chatMessageId);

    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
