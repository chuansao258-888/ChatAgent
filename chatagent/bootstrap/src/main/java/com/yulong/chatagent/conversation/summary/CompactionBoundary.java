package com.yulong.chatagent.conversation.summary;

import java.util.List;

/**
 * Resolved boundary between stable (L2-eligible) turns and the preserved L1 tail.
 *
 * @param sessionId           the session being evaluated
 * @param summarizedUntilSeqNo current L2 watermark
 * @param stableAnchorSeqNo   endSeqNo of the newest turn outside the L1 tail (0 if no stable turns)
 * @param totalTurns          total summarizable turns in the session
 * @param preservedTailTurns  number of turns kept in the L1 tail
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
     * @return true if there are stable turns not yet covered by the current L2 watermark
     */
    public boolean hasStableTurns() {
        return stableAnchorSeqNo > summarizedUntilSeqNo;
    }

    /**
     * @return number of turns outside the L1 tail
     */
    public int stableTurnCount() {
        return totalTurns - preservedTailTurns;
    }

    /**
     * @return turns outside the L1 tail (stable, L2-eligible)
     */
    public List<AtomicConversationTurn> stableTurns() {
        int stableCount = stableTurnCount();
        if (stableCount <= 0 || allTurns == null) {
            return List.of();
        }
        return allTurns.subList(0, Math.min(stableCount, allTurns.size()));
    }

    /**
     * @return turns inside the L1 tail (preserved as raw context)
     */
    public List<AtomicConversationTurn> tailTurns() {
        int stableCount = stableTurnCount();
        if (allTurns == null || stableCount >= allTurns.size()) {
            return allTurns == null ? List.of() : allTurns;
        }
        return allTurns.subList(stableCount, allTurns.size());
    }

    /**
     * @return count of stable turns not yet covered by the L2 watermark
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
     * @return stable turns not yet covered by the L2 watermark
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
