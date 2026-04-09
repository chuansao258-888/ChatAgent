package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.intent.application.IntentResolution;
import lombok.Data;

@Data
public class ChatEvent {

    private String agentId;
    private String sessionId;
    private String turnId;
    private String chatMessageId;
    private String userInput;
    private int recentHistorySize;
    private IntentResolution intentResolution;
    private String rewrittenInput;
    private String userId;

    public ChatEvent(String agentId,
                     String sessionId,
                     String turnId,
                     String chatMessageId,
                     String userInput,
                     int recentHistorySize,
                     IntentResolution intentResolution,
                     String rewrittenInput) {
        this(agentId, sessionId, turnId, chatMessageId, userInput, recentHistorySize, intentResolution, rewrittenInput, null);
    }

    public ChatEvent(String agentId,
                     String sessionId,
                     String turnId,
                     String chatMessageId,
                     String userInput,
                     int recentHistorySize,
                     IntentResolution intentResolution,
                     String rewrittenInput,
                     String userId) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.chatMessageId = chatMessageId;
        this.userInput = userInput;
        this.recentHistorySize = recentHistorySize;
        this.intentResolution = intentResolution;
        this.rewrittenInput = rewrittenInput;
        this.userId = userId;
    }
}
