package com.yulong.chatagent.conversation.application.model;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import com.yulong.chatagent.support.dto.ChatMessageDTO;

import java.util.List;

/**
 * Immutable context assembled for one user turn before downstream execution starts.
 * This keeps the orchestrator on a harness-style flow: gather context first, then
 * dispatch the turn with explicit inputs.
 */
public record ConversationTurnContext(
        CreateChatMessageRequest request,
        ChatSessionVO session,
        CreateChatMessageResponse createdUserMessage,
        List<ChatMessageDTO> recentHistory,
        AgentExecutionMode executionMode
) {

    public int historySize() {
        return recentHistory == null ? 0 : recentHistory.size();
    }
}
