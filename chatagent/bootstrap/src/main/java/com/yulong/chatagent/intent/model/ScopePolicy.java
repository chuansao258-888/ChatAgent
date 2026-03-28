package com.yulong.chatagent.intent.model;

/**
 * Controls whether scoped KB retrieval may expand to assistant-bound KBs when empty.
 */
public enum ScopePolicy {
    STRICT,
    FALLBACK_ALLOWED
}

