package com.yulong.chatagent.model.common;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private static final String TRACE_ID = "test-trace-123";

    @BeforeEach
    void setUp() {
        TraceContext.setTraceId(TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void success_withData_returnsOkResponse() {
        ApiResponse<String> response = ApiResponse.success("hello");

        assertThat(response.getCode()).isEqualTo(BaseErrorCode.SUCCESS.code());
        assertThat(response.getMessage()).isEqualTo(BaseErrorCode.SUCCESS.message());
        assertThat(response.getData()).isEqualTo("hello");
        assertThat(response.getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void success_noData_returnsNullPayload() {
        ApiResponse<Void> response = ApiResponse.success();

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isNull();
    }

    @Test
    void success_customMessage_overridesDefault() {
        ApiResponse<String> response = ApiResponse.success("data", "custom ok");

        assertThat(response.getMessage()).isEqualTo("custom ok");
        assertThat(response.getData()).isEqualTo("data");
    }

    @Test
    void error_withErrorCode_usesCodeValues() {
        ApiResponse<Void> response = ApiResponse.error(BaseErrorCode.CLIENT_ERROR);

        assertThat(response.getCode()).isEqualTo(BaseErrorCode.CLIENT_ERROR.code());
        assertThat(response.getMessage()).isEqualTo(BaseErrorCode.CLIENT_ERROR.message());
        assertThat(response.getData()).isNull();
        assertThat(response.getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void error_withMessage_overridesDefaultMessage() {
        ApiResponse<Void> response = ApiResponse.error(BaseErrorCode.NOT_FOUND, "gone");

        assertThat(response.getCode()).isEqualTo(404);
        assertThat(response.getMessage()).isEqualTo("gone");
    }

    @Test
    void error_defaultCode_isServiceError() {
        ApiResponse<Void> response = ApiResponse.error("something broke");

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).isEqualTo("something broke");
    }

    @Test
    void traceId_isNull_whenTraceContextEmpty() {
        TraceContext.clear();
        ApiResponse<Void> response = ApiResponse.success();

        assertThat(response.getTraceId()).isNull();
    }
}
