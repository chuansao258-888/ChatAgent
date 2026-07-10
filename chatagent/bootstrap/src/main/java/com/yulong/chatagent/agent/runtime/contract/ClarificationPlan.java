package com.yulong.chatagent.agent.runtime.contract;

/**
 * Clarification policy for the turn.
 *
 * <p>Phase 1 always produces a non-blocking {@code NONE} plan. Phase 4 will
 * populate route-choice and execution-clarification plans.</p>
 *
 * @param kind          clarification category
 * @param blocking      whether the turn is blocked until the user clarifies
 * @param userQuestion  question to show the user when blocking
 */
public record ClarificationPlan(
        ClarificationKind kind,
        boolean blocking,
        String userQuestion
) {
    /** A non-blocking, no-clarification plan. */
    public static ClarificationPlan none() {
        return new ClarificationPlan(ClarificationKind.NONE, false, null);
    }
}
