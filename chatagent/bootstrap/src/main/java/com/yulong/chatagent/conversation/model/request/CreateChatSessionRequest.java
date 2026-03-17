package com.yulong.chatagent.conversation.model.request;

import lombok.Data;

@Data
public class CreateChatSessionRequest {
    private String agentId;
    private String title;
}
