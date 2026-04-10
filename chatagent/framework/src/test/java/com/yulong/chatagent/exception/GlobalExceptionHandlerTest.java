package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @BeforeEach
    void setUp() {
        TraceContext.setTraceId("trace-abc");
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void handleAbstractException_clientError_returnsMatchingStatus() {
        ClientException ex = new ClientException(BaseErrorCode.FORBIDDEN, "denied");
        ResponseEntity<ApiResponse<Void>> entity = handler.handleAbstractException(ex);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().getCode()).isEqualTo(403);
        assertThat(entity.getBody().getMessage()).isEqualTo("denied");
    }

    @Test
    void handleAbstractException_serviceError_returns500() {
        ServiceException ex = new ServiceException("boom");
        ResponseEntity<ApiResponse<Void>> entity = handler.handleAbstractException(ex);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(entity.getBody().getCode()).isEqualTo(500);
    }

    @Test
    void handleMaxUploadSizeExceeded_returns400() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10_000_000L);
        ResponseEntity<ApiResponse<Void>> entity = handler.handleMaxUploadSizeExceeded(ex);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody().getMessage()).contains("size limit");
    }

    @Test
    void handleException_fallback_returns500() {
        ResponseEntity<ApiResponse<Void>> entity = handler.handleException(new RuntimeException("unexpected"));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(entity.getBody().getCode()).isEqualTo(500);
        assertThat(entity.getBody().getMessage()).isEqualTo("Internal server error");
    }
}
