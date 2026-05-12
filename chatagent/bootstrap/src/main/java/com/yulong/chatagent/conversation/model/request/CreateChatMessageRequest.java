package com.yulong.chatagent.conversation.model.request;

import com.yulong.chatagent.support.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatMessageRequest {
    private String sessionId;
    private String turnId;
    private Long turnSeq;
    private ChatMessageDTO.RoleType role;
    private String content;
    private ChatMessageDTO.MetaData metadata;
}
