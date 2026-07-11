package com.yulong.chatagent.agent;

/** Stable runtime failure for shared ReAct/DeepThink tool execution. */
public class ToolExecutionException extends RuntimeException {
    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ToolExecutionException(String message) {
        super(message);
    }
}
