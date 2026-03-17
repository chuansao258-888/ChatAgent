package com.yulong.chatagent.conversation.model.response;

import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetChatMessagesResponse {
    private ChatMessageVO[] chatMessages;
}


