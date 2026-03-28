package com.yulong.chatagent.conversation.model.vo;

import com.yulong.chatagent.support.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatMessageVO {
    private String id;
    private String sessionId;
    private String turnId;
    private ChatMessageDTO.RoleType role;
    private String content;
    private ChatMessageDTO.MetaData metadata;
    private Long seqNo;
}
