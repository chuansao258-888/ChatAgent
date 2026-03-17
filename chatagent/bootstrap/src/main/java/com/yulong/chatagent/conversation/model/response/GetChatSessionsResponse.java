package com.yulong.chatagent.conversation.model.response;

import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetChatSessionsResponse {
    private ChatSessionVO[] chatSessions;
}

