package com.yulong.chatagent.trace;

import org.slf4j.MDC;

/**
 * Stores the request trace identifier in both thread-local state and SLF4J MDC.
 */
public final class TraceContext {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_LOG_KEY = "traceId";

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * Returns the trace ID currently associated with the executing thread.
     *
     * @return current trace ID, or {@code null} when none is set
     */
    public static String getTraceId() {
        return TRACE_ID_HOLDER.get();
    }

    /**
     * Associates the given trace ID with the current thread and log context.
     *
     * @param traceId trace identifier for the current request
     */
    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
        MDC.put(TRACE_ID_LOG_KEY, traceId);
    }

    /**
     * Removes the trace ID from both thread-local state and the logging MDC.
     */
    public static void clear() {
        TRACE_ID_HOLDER.remove();
        MDC.remove(TRACE_ID_LOG_KEY);
    }
}
