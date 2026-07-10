package com.yulong.chatagent.agent.runtime.contract;

/**
 * Whether and when retrieval must run for a turn.
 *
 * <p>This is the contract field that replaces the current cross-layer keyword
 * gate. Phase 1 derives it; Phase 3 enforces it.</p>
 */
public enum RetrievalMode {
    /** Retrieval must not run. */
    DISABLED,
    /** Retrieval may run if the runtime heuristics support it (uploaded/session asset references). */
    ALLOWED,
    /** Retrieval must run before final answer synthesis, regardless of user trigger words. */
    REQUIRED_BEFORE_ANSWER
}
