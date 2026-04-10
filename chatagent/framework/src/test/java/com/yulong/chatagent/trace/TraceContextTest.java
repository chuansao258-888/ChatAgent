package com.yulong.chatagent.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        TraceContext.clear();
    }

    @Test
    void getTraceId_returnsNull_whenNotSet() {
        assertThat(TraceContext.getTraceId()).isNull();
    }

    @Test
    void setTraceId_storesValue() {
        TraceContext.setTraceId("abc-123");

        assertThat(TraceContext.getTraceId()).isEqualTo("abc-123");
    }

    @Test
    void setTraceId_populatesMdc() {
        TraceContext.setTraceId("mdc-test");

        assertThat(MDC.get(TraceContext.TRACE_ID_LOG_KEY)).isEqualTo("mdc-test");
    }

    @Test
    void clear_removesTraceIdAndMdc() {
        TraceContext.setTraceId("will-be-cleared");
        TraceContext.clear();

        assertThat(TraceContext.getTraceId()).isNull();
        assertThat(MDC.get(TraceContext.TRACE_ID_LOG_KEY)).isNull();
    }

    @Test
    void setTraceId_overwritesPrevious() {
        TraceContext.setTraceId("first");
        TraceContext.setTraceId("second");

        assertThat(TraceContext.getTraceId()).isEqualTo("second");
        assertThat(MDC.get(TraceContext.TRACE_ID_LOG_KEY)).isEqualTo("second");
    }

    @Test
    void constants_areConsistent() {
        assertThat(TraceContext.TRACE_ID_HEADER).isEqualTo("X-Trace-Id");
        assertThat(TraceContext.TRACE_ID_LOG_KEY).isEqualTo("traceId");
    }
}
