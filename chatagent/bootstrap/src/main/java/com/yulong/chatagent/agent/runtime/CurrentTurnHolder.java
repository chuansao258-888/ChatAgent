package com.yulong.chatagent.agent.runtime;

/**
 * Thread-local holder for the current logical turn while an agent run is executing.
 */
public final class CurrentTurnHolder {

    private static final ThreadLocal<String> CURRENT_TURN_ID = new ThreadLocal<>();

    private CurrentTurnHolder() {
    }

    public static void set(String turnId) {
        CURRENT_TURN_ID.set(turnId);
    }

    public static String get() {
        return CURRENT_TURN_ID.get();
    }

    public static String require() {
        String turnId = CURRENT_TURN_ID.get();
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalStateException("No active turn bound to current thread");
        }
        return turnId;
    }

    public static void clear() {
        CURRENT_TURN_ID.remove();
    }
}
