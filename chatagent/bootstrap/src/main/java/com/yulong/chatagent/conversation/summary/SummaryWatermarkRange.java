package com.yulong.chatagent.conversation.summary;

/**
 * Represents the pending seq_no range that still needs summarization for one session.
 */
public record SummaryWatermarkRange(
        String sessionId,
        long lastSummarizedSeqNo,
        long anchorSeqNo
) {
    public boolean hasPendingMessages() {
        return anchorSeqNo > lastSummarizedSeqNo;
    }

    public long startExclusiveSeqNo() {
        return lastSummarizedSeqNo;
    }

    public long endInclusiveSeqNo() {
        return anchorSeqNo;
    }
}
