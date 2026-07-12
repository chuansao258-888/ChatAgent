package com.yulong.chatagent.agent;

/** Typed, non-error stop raised by DeepThink's nested tool loop. */
public final class ToolExecutionStopException extends RuntimeException {

    private final AgentRunResult.Status status;
    private final String reason;

    public ToolExecutionStopException(AgentRunResult.Status status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }

    public AgentRunResult.Status status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}
