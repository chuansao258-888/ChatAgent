package com.yulong.chatagent.conversation.summary;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether to trigger L2 compaction based on V2 policy rules.
 * <p>
 * Trigger priority:
 * <ol>
 *     <li>pending stable turns ≥ min-pending-turns</li>
 *     <li>pending stable tokens ≥ min-pending-tokens</li>
 *     <li>L1 raw load exceeds l1-token-budget × l1-token-warning-ratio</li>
 * </ol>
 */
@Component
public class MemoryCompactionPolicy {

    private final boolean v2Enabled;
    private final int minPendingTurns;
    private final int minPendingTokens;
    private final double l1TokenWarningRatio;
    private final int l1TokenBudget;

    public MemoryCompactionPolicy(
            @Value("${chatagent.memory.compaction.v2.enabled:true}") boolean v2Enabled,
            @Value("${chatagent.memory.compaction.v2.min-pending-turns:1}") int minPendingTurns,
            @Value("${chatagent.memory.compaction.v2.min-pending-tokens:24000}") int minPendingTokens,
            @Value("${chatagent.memory.compaction.v2.l1-token-warning-ratio:0.92}") double l1TokenWarningRatio,
            @Value("${chatagent.memory.l1-token-budget:256000}") int l1TokenBudget) {
        this.v2Enabled = v2Enabled;
        this.minPendingTurns = Math.max(minPendingTurns, 1);
        this.minPendingTokens = Math.max(minPendingTokens, 100);
        this.l1TokenWarningRatio = l1TokenWarningRatio;
        this.l1TokenBudget = l1TokenBudget;
    }

    /**
     * Evaluate whether compaction should proceed for the given boundary.
     *
     * @param boundary           resolved stable/tail boundary
     * @param pendingStableTokens estimated tokens of stable turns in the pending range
     * @param l1RawTokenEstimate  estimated tokens of the L1 preserved tail
     * @return decision with trigger reason
     */
    public CompactionDecision evaluate(CompactionBoundary boundary,
                                       int pendingStableTokens,
                                       int l1RawTokenEstimate) {
        if (!v2Enabled) {
            return new CompactionDecision(false, CompactionTrigger.DISABLED);
        }

        if (!boundary.hasStableTurns()) {
            return new CompactionDecision(false, CompactionTrigger.NO_STABLE_TURNS);
        }

        if (boundary.backoffActive()) {
            return new CompactionDecision(false, CompactionTrigger.BACKOFF_ACTIVE);
        }

        if (boundary.pendingStableTurnCount() >= minPendingTurns) {
            return new CompactionDecision(true, CompactionTrigger.PENDING_TURNS);
        }

        if (pendingStableTokens >= minPendingTokens) {
            return new CompactionDecision(true, CompactionTrigger.PENDING_TOKENS);
        }

        int warningThreshold = (int) (l1TokenBudget * l1TokenWarningRatio);
        if (l1RawTokenEstimate > warningThreshold) {
            return new CompactionDecision(true, CompactionTrigger.L1_TOKEN_PRESSURE);
        }

        return new CompactionDecision(false, CompactionTrigger.BELOW_THRESHOLD);
    }
}
