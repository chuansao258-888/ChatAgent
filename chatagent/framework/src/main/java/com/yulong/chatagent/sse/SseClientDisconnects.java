package com.yulong.chatagent.sse;

import java.io.EOFException;
import java.io.IOException;
import java.util.Locale;

public final class SseClientDisconnects {

    private SseClientDisconnects() {
    }

    public static boolean isLikelyClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")
                    || className.contains("AsyncRequestNotUsableException")
                    || current instanceof EOFException) {
                return true;
            }
            if (current instanceof IOException && hasDisconnectMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasDisconnectMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("broken pipe")
                || lower.contains("connection reset")
                || lower.contains("connection aborted")
                || lower.contains("forcibly closed")
                || lower.contains("远程主机")
                || lower.contains("已建立的连接");
    }
}
