package com.yulong.chatagent.conversation.model.request;

import lombok.Data;

/** Request body for updating an existing chat session's title. */
@Data
public class UpdateChatSessionRequest {
    private String title;
}
