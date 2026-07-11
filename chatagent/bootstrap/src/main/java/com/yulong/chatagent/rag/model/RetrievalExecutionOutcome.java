package com.yulong.chatagent.rag.model;

/**
 * Contract-aware retrieval outcome for one executed query.
 *
 * <p>This is intentionally separate from {@link RetrievalHit#isFallback()}:
 * hit fallback describes ranking degradation, while {@code FALLBACK_HIT}
 * describes execution of a fallback source declared by the retrieval policy.</p>
 */
public enum RetrievalExecutionOutcome {
    /** Retrieval was explicitly disabled by the turn contract. */
    DISABLED,
    /** No allowed, active source scope was available to search. */
    BLOCKED_NO_SCOPE,
    /** At least one allowed source was searched, but no usable evidence was found. */
    NO_HIT,
    /** Usable evidence was found from a primary source. */
    HIT,
    /** Usable evidence was found from a fallback source declared by policy. */
    FALLBACK_HIT,
    /** Retrieval could not complete because its input or a dependency failed. */
    FAILED
}
