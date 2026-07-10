package com.yulong.chatagent.agent.runtime.contract;

/**
 * Fallback policy when the primary retrieval source returns nothing.
 */
public enum RetrievalFallbackPolicy {
    /** No fallback; return empty when the primary source misses. */
    NONE,
    /** Fall back to agent default knowledge bases. */
    AGENT_DEFAULT_KB,
    /** Restrict to session files only. */
    SESSION_FILES_ONLY
}
