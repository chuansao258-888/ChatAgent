package com.yulong.chatagent.conversation.summary;

import java.util.List;

/**
 * Result of an incremental L2 summarization pass.
 *
 * @param updated whether the summary record was actually written
 * @param range   the seq_no range that was processed (empty range if nothing was pending)
 * @param turns   the raw turns that were extracted for this range
 */
public record SummaryResult(
        boolean updated,
        SummaryWatermarkRange range,
        List<AtomicConversationTurn> turns
) {
}
