package com.yulong.chatagent.agent.runtime;

/**
 * Thread-local holder for the chat session currently executing inside the agent loop.
 * Tool callbacks use this to resolve the active session without exposing internal IDs
 * to the model prompt.
 */
public final class CurrentChatSessionHolder {

    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    private CurrentChatSessionHolder() {
    }

    public static void set(String chatSessionId) {
        CURRENT_SESSION_ID.set(chatSessionId);
    }

    public static String get() {
        return CURRENT_SESSION_ID.get();
    }

    public static String require() {
        String chatSessionId = CURRENT_SESSION_ID.get();
        if (chatSessionId == null || chatSessionId.isBlank()) {
            throw new IllegalStateException("No active chat session bound to current thread");
        }
        return chatSessionId;
    }

    public static void clear() {
        CURRENT_SESSION_ID.remove();
    }
}
