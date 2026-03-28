package com.yulong.chatagent.conversation.event;

/**
 * Raised after one full user turn has been durably completed.
 *
 * @param sessionId chat session identifier
 * @param turnId turn boundary identifier spanning the whole turn
 * @param lastSeqNo latest persisted message seq_no at completion time
 */
public record ConversationTurnCompletedEvent(
        String sessionId,
        String turnId,
        long lastSeqNo
) {
}
