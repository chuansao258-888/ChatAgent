package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;

import java.util.List;

/**
 * Published after L2 summarization completes with a real pending range.
 * Carries the exact raw turns that L2 compressed, so L3 promotion can inspect
 * them for long-term memory extraction without re-querying the database.
 *
 * @param sessionId chat session identifier
 * @param range     the seq_no range that L2 just summarized
 * @param turns     the raw turns that were extracted for this range
 */
public record LongTermMemoryPromotionRequestedEvent(
        String sessionId,
        SummaryWatermarkRange range,
        List<AtomicConversationTurn> turns
) {
}
