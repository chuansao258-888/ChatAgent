package com.yulong.chatagent.intent.application;

/**
 * Typed routing outcome for the intent-understanding pipeline.
 *
 * <p>Phase 2.5 replaces the old single-label {@code NONE} bucket (which conflated
 * general chat, out-of-domain, ambiguity, missing execution info, and multi-intent)
 * with six explicit outcomes so downstream code can act deterministically.</p>
 *
 * <ul>
 *   <li>{@link #KNOWN_INTENT} — a specific intent-tree node was selected.</li>
 *   <li>{@link #GENERAL_CHAT} — conversational turn with no business-intent need.</li>
 *   <li>{@link #OUT_OF_DOMAIN} — the question is outside the agent's scope.</li>
 *   <li>{@link #AMBIGUOUS_ROUTE} — multiple candidates compete; user must choose.</li>
 *   <li>{@link #EXECUTION_INFO_MISSING} — intent is known but execution data is incomplete.</li>
 *   <li>{@link #MULTI_INTENT} — the turn combines two or more compatible intents.</li>
 * </ul>
 */
public enum IntentRouteOutcome {
    /** A specific intent-tree node was confidently selected. */
    KNOWN_INTENT,
    /** Conversational turn; no business intent needed. Pass through to Agent. */
    GENERAL_CHAT,
    /** The question is outside the agent's configured scope. */
    OUT_OF_DOMAIN,
    /** Multiple intent candidates compete; the user must disambiguate. */
    AMBIGUOUS_ROUTE,
    /** Intent is known but execution information (source, object, action) is incomplete. */
    EXECUTION_INFO_MISSING,
    /** The turn combines two or more compatible intents. */
    MULTI_INTENT
}
