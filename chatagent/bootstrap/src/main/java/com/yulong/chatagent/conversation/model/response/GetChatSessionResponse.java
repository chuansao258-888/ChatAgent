package com.yulong.chatagent.conversation.model.response;

import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class GetChatSessionResponse {
    private ChatSessionVO chatSession;
}

