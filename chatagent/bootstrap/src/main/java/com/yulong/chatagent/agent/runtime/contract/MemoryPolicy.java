package com.yulong.chatagent.agent.runtime.contract;

/**
 * Memory policy for the turn.
 *
 * <p>Phase 1 records conservative defaults. Later phases may tighten recall or
 * decide whether the turn should persist to long-term memory.</p>
 *
 * @param recallEnabled   whether L3 long-term-memory recall should run
 */
public record MemoryPolicy(
        boolean recallEnabled
) {
    /** Default policy: recall enabled. */
    public static MemoryPolicy defaultRecall() {
        return new MemoryPolicy(true);
    }
}
