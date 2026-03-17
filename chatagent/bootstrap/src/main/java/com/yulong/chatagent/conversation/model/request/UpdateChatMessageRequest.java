package com.yulong.chatagent.conversation.model.request;

import com.yulong.chatagent.support.dto.ChatMessageDTO;
import lombok.Data;

@Data
public class UpdateChatMessageRequest {
    private String content;
    private ChatMessageDTO.MetaData metadata;
}


