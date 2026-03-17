package com.yulong.chatagent.trace;

import org.slf4j.MDC;

public final class TraceContext {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_LOG_KEY = "traceId";

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String getTraceId() {
        return TRACE_ID_HOLDER.get();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
        MDC.put(TRACE_ID_LOG_KEY, traceId);
    }

    public static void clear() {
        TRACE_ID_HOLDER.remove();
        MDC.remove(TRACE_ID_LOG_KEY);
    }
}
