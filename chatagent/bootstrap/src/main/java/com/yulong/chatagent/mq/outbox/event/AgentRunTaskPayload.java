package com.yulong.chatagent.mq.outbox.event;

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
        boolean forceRollback
) {

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
                rewrittenInput
        );
    }
}
