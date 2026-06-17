package com.yulong.chatagent.conversation.model.request;

import lombok.Data;

/** Request body for creating a new chat session. */
@Data
public class CreateChatSessionRequest {
    private String title;
}
