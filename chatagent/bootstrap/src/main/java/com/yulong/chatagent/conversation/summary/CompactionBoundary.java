package com.yulong.chatagent.conversation.summary;

import java.util.List;

/**
 * Resolved boundary between already summarized history and the next rolling L2 batch.
 *
 * @param sessionId           the session being evaluated
 * @param summarizedUntilSeqNo current L2 watermark
 * @param stableAnchorSeqNo   endSeqNo of the newest turn in the next compaction batch (0 if no pending turns)
 * @param totalTurns          total summarizable turns in the session
 * @param preservedTailTurns  number of turns currently preserved as unsummarized raw context
 * @param allTurns            all summarizable turns in the session (ordered by turn position)
 * @param consecutiveFailures consecutive compaction failures from current summary state
 * @param backoffActive       whether per-session backoff is currently active
 */
public record CompactionBoundary(
        String sessionId,
        long summarizedUntilSeqNo,
        long stableAnchorSeqNo,
        int totalTurns,
        int preservedTailTurns,
        List<AtomicConversationTurn> allTurns,
        int consecutiveFailures,
        boolean backoffActive
) {

    /**
     * @return true if there are pending raw turns not yet covered by the current L2 watermark
     */
    public boolean hasStableTurns() {
        return stableAnchorSeqNo > summarizedUntilSeqNo;
    }

    /**
     * @return number of pending turns selected for the next L2 compaction batch
     */
    public int stableTurnCount() {
        return pendingStableTurnCount();
    }

    /**
     * @return pending turns selected for the next L2 compaction batch
     */
    public List<AtomicConversationTurn> stableTurns() {
        return pendingStableTurns();
    }

    /**
     * @return unsummarized raw turns after the current L2 watermark
     */
    public List<AtomicConversationTurn> tailTurns() {
        return unsummarizedTurns();
    }

    /**
     * @return all turns after the current L2 watermark
     */
    public List<AtomicConversationTurn> unsummarizedTurns() {
        if (allTurns == null || allTurns.isEmpty()) {
            return List.of();
        }
        return allTurns.stream()
                .filter(t -> t.endSeqNo() > summarizedUntilSeqNo)
                .toList();
    }

    /**
     * @return number of turns after the current L2 watermark
     */
    public int unsummarizedTurnCount() {
        return unsummarizedTurns().size();
    }

    /**
     * @return count of turns selected for the next compaction batch
     *         (i.e., turns in the range (summarizedUntilSeqNo, stableAnchorSeqNo])
     */
    public int pendingStableTurnCount() {
        if (allTurns == null || !hasStableTurns()) {
            return 0;
        }
        return (int) allTurns.stream()
                .filter(t -> t.endSeqNo() > summarizedUntilSeqNo && t.endSeqNo() <= stableAnchorSeqNo)
                .count();
    }

    /**
     * @return turns selected for the next compaction batch
     *         (i.e., turns in the range (summarizedUntilSeqNo, stableAnchorSeqNo])
     */
    public List<AtomicConversationTurn> pendingStableTurns() {
        if (allTurns == null || !hasStableTurns()) {
            return List.of();
        }
        return allTurns.stream()
                .filter(t -> t.endSeqNo() > summarizedUntilSeqNo && t.endSeqNo() <= stableAnchorSeqNo)
                .toList();
    }
}
