package com.yulong.chatagent.conversation.model.response;

import lombok.Builder;
import lombok.Data;

/** Response returned after a chat message is created, identifying the message, turn, and sequence. */
@Data
@Builder
public class CreateChatMessageResponse {
    private String chatMessageId;
    private String turnId;
    private Long turnSeq;
}
