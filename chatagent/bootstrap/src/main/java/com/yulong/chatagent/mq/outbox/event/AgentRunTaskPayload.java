package com.yulong.chatagent.mq.outbox.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.intent.application.IntentResolution;

/**
 * Serialized chat dispatch payload for the staged {@code agent.run} MQ migration.
 */
public record AgentRunTaskPayload(
        String agentId,
        String sessionId,
        String turnId,
        String chatMessageId,
        String userInput,
        int recentHistorySize,
        IntentResolution intentResolution,
        String rewrittenInput,
        String userId,
        boolean forceRollback
) {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public AgentRunTaskPayload(
            @JsonProperty("agentId") String agentId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("turnId") String turnId,
            @JsonProperty("chatMessageId") String chatMessageId,
            @JsonProperty("userInput") String userInput,
            @JsonProperty("recentHistorySize") int recentHistorySize,
            @JsonProperty("intentResolution") IntentResolution intentResolution,
            @JsonProperty("rewrittenInput") String rewrittenInput,
            @JsonProperty(value = "userId", defaultValue = "") String userId,
            @JsonProperty(value = "forceRollback", defaultValue = "false") boolean forceRollback
    ) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.chatMessageId = chatMessageId;
        this.userInput = userInput;
        this.recentHistorySize = recentHistorySize;
        this.intentResolution = intentResolution;
        this.rewrittenInput = rewrittenInput;
        this.userId = userId == null ? "" : userId;
        this.forceRollback = forceRollback;
    }

    public static AgentRunTaskPayload fromChatEvent(ChatEvent event) {
        return new AgentRunTaskPayload(
                event.getAgentId(),
                event.getSessionId(),
                event.getTurnId(),
                event.getChatMessageId(),
                event.getUserInput(),
                event.getRecentHistorySize(),
                event.getIntentResolution(),
                event.getRewrittenInput(),
                event.getUserId(),
                false
        );
    }

    public AgentRunTaskPayload withForceRollback(boolean forceRollback) {
        return new AgentRunTaskPayload(
                agentId,
                sessionId,
                turnId,
                chatMessageId,
                userInput,
                recentHistorySize,
                intentResolution,
                rewrittenInput,
                userId,
                forceRollback
        );
    }

    public ChatEvent toChatEvent() {
        return new ChatEvent(
                agentId,
                sessionId,
                turnId,
                chatMessageId,
                userInput,
                recentHistorySize,
                intentResolution,
                rewrittenInput,
                userId
        );
    }
}
