package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.intent.application.IntentResolution;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChatEvent {

    private String agentId;
    private String sessionId;
    private String turnId;
    private String chatMessageId;
    private String userInput;
    private int recentHistorySize;
    private IntentResolution intentResolution;
    private String rewrittenInput;
}
