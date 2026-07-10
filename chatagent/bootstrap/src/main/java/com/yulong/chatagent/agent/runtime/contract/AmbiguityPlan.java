package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * Whether and how the latest turn is ambiguous.
 *
 * <p>Phase 1 only records {@code blocking=false, kind=NONE}. Phase 4 will start
 * populating real blocking ambiguity and candidate lists.</p>
 *
 * @param blocking   whether the ambiguity blocks safe execution
 * @param kind       clarification category if blocking
 * @param userQuestion the question to show the user when blocking
 * @param candidates candidate labels the user can choose from when blocking
 */
public record AmbiguityPlan(
        boolean blocking,
        ClarificationKind kind,
        String userQuestion,
        List<String> candidates
) {
    public AmbiguityPlan {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** A non-blocking, no-clarification plan. */
    public static AmbiguityPlan none() {
        return new AmbiguityPlan(false, ClarificationKind.NONE, null, List.of());
    }
}
