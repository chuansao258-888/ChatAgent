package com.yulong.chatagent.agent.runtime.contract;

/**
 * Classifies which retrieval source a turn needs to be answered from.
 *
 * <p>It is the {@code TurnAnalysis.sourceNeed} field described in the Agent Turn
 * Execution Contract plan. Phase 1 derives it conservatively; later phases can
 * refine it from structured understanding.</p>
 */
public enum SourceNeed {
    /** No external source is required; the turn can be answered from chat history or general knowledge. */
    NONE,
    /** The turn needs bound knowledge-base evidence. */
    KB,
    /** The turn needs uploaded/session-file evidence. */
    FILE,
    /** The turn needs current web evidence. */
    WEB,
    /** The turn needs evidence from more than one source (e.g. KB + file comparison). */
    MIXED
}
