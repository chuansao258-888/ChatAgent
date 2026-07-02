package com.yulong.chatagent.conversation.summary;

/**
 * Reason why compaction was triggered or skipped.
 */
public enum CompactionTrigger {

    DISABLED,
    NO_STABLE_TURNS,
    BACKOFF_ACTIVE,
    BELOW_THRESHOLD,
    UNSUMMARIZED_TURNS,
    PENDING_TOKENS,
    L1_TOKEN_PRESSURE
}
