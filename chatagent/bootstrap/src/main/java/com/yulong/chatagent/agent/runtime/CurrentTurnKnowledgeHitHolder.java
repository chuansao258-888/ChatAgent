package com.yulong.chatagent.agent.runtime;

/**
 * Thread-local holder for whether a turn found at least one retrieval hit.
 */
public final class CurrentTurnKnowledgeHitHolder {

    private static final ThreadLocal<KnowledgeState> CURRENT_STATE = new ThreadLocal<>();

    private CurrentTurnKnowledgeHitHolder() {
    }

    public static void reset() {
        CURRENT_STATE.set(new KnowledgeState());
    }

    public static void recordRetrievalResult(boolean hit) {
        KnowledgeState state = CURRENT_STATE.get();
        if (state == null) {
            state = new KnowledgeState();
            CURRENT_STATE.set(state);
        }
        state.retrievalAttempted = true;
        state.anyHit = state.anyHit || hit;
    }

    public static boolean isKnowledgeHit() {
        KnowledgeState state = CURRENT_STATE.get();
        if (state == null || !state.retrievalAttempted) {
            return true;
        }
        return state.anyHit;
    }

    public static void clear() {
        CURRENT_STATE.remove();
    }

    private static final class KnowledgeState {
        private boolean retrievalAttempted;
        private boolean anyHit;
    }
}
