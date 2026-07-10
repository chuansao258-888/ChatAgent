package com.yulong.chatagent.agent.runtime.contract;

/**
 * The retrieval source a {@link RetrievalPlan} points at.
 */
public enum RetrievalSource {
    /** No retrieval source. */
    NONE,
    /** Uploaded/session files. */
    SESSION_FILES,
    /** Intent-bound knowledge bases. */
    INTENT_KB,
    /** Agent default knowledge bases (fallback). */
    AGENT_DEFAULT_KB,
    /** Mixed session files and knowledge bases. */
    MIXED_SESSION_AND_KB
}
