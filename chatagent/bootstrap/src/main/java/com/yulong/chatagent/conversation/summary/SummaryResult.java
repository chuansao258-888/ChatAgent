package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;

import java.util.List;

/**
 * Result of an incremental L2 summarization pass.
 *
 * @param updated  whether the summary record was actually written
 * @param range    the seq_no range that was processed (empty range if nothing was pending)
 * @param turns    the raw turns that were extracted for this range
 * @param segments the segment rows created during this pass (V2 structured flow)
 * @param synopsis the updated session synopsis after this pass
 */
public record SummaryResult(
        boolean updated,
        SummaryWatermarkRange range,
        List<AtomicConversationTurn> turns,
        List<ChatSessionSummarySegmentDTO> segments,
        String synopsis
) {
    /**
     * Backward-compatible constructor for callers that don't need segments or synopsis.
     */
    public SummaryResult(boolean updated, SummaryWatermarkRange range, List<AtomicConversationTurn> turns) {
        this(updated, range, turns, List.of(), null);
    }
}
