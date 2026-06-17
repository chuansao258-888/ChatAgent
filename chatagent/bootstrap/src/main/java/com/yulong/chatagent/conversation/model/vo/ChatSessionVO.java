package com.yulong.chatagent.conversation.model.vo;

import lombok.Builder;
import lombok.Data;

/** Read-only view of a chat session returned to clients. */
@Data
@Builder
public class ChatSessionVO {
    private String id;
    private String agentId;
    private String title;
}
