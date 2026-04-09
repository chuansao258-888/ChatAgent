package com.yulong.chatagent.agent;

import org.springframework.util.StringUtils;

import java.util.concurrent.TimeoutException;

/**
 * Lightweight summary of one agent run for dashboard metrics.
 */
public record AgentRunResult(
        Status status,
        long durationMs,
        String errorType,
        boolean knowledgeHit
) {

    public enum Status {
        SUCCESS,
        ERROR
    }

    public static AgentRunResult success(long durationMs, boolean knowledgeHit) {
        return new AgentRunResult(Status.SUCCESS, durationMs, null, knowledgeHit);
    }

    public static AgentRunResult failure(long durationMs, boolean knowledgeHit, Throwable throwable) {
        return new AgentRunResult(Status.ERROR, durationMs, classifyError(throwable), knowledgeHit);
    }

    private static String classifyError(Throwable throwable) {
        Throwable rootCause = unwrap(throwable);
        if (rootCause == null) {
            return "UNEXPECTED_ERROR";
        }
        if (rootCause instanceof TimeoutException || rootCause.getClass().getSimpleName().contains("Timeout")) {
            return "LLM_TIMEOUT";
        }
        String simpleName = rootCause.getClass().getSimpleName();
        if (simpleName.contains("Remote") || simpleName.contains("Http")) {
            return "UPSTREAM_ERROR";
        }
        if (simpleName.contains("Tool")) {
            return "TOOL_EXECUTION_ERROR";
        }
        String message = rootCause.getMessage();
        if (StringUtils.hasText(message) && message.toLowerCase().contains("retriev")) {
            return "RETRIEVAL_FAIL";
        }
        return "UNEXPECTED_ERROR";
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
