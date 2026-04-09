package com.yulong.chatagent.agent;

/**
 * Runtime exception carrying the structured run result for failure metrics.
 */
public class AgentRunException extends RuntimeException {

    private final AgentRunResult result;

    public AgentRunException(String message, Throwable cause, AgentRunResult result) {
        super(message, cause);
        this.result = result;
    }

    public AgentRunResult getResult() {
        return result;
    }
}
