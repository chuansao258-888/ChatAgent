package com.yulong.chatagent.conversation.model.request;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for creating a chat message within a session turn. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateChatMessageRequest {
    private String sessionId;
    private String turnId;
    private Long turnSeq;
    private ChatMessageDTO.RoleType role;
    private String content;
    private AgentExecutionMode executionMode;
    private ChatMessageDTO.MetaData metadata;
}
