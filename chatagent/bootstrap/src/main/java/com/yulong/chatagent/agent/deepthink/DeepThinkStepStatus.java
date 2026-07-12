package com.yulong.chatagent.agent.deepthink;

/** Authoritative outcome of one bounded DeepThink plan step. */
public enum DeepThinkStepStatus {
    COMPLETED,
    PARTIAL,
    FAILED,
    BLOCKED,
    SKIPPED
}
