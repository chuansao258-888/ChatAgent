package com.yulong.chatagent.conversation.model.vo;

import com.yulong.chatagent.support.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

/** Read-only view of a chat message returned to clients. */
@Data
@Builder
public class ChatMessageVO {
    private String id;
    private String sessionId;
    private String turnId;
    private Long turnSeq;
    private ChatMessageDTO.RoleType role;
    private String content;
    private ChatMessageDTO.MetaData metadata;
    private Long seqNo;
}
