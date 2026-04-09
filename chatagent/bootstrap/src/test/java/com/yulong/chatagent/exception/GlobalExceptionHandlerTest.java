package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.model.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler subject = new GlobalExceptionHandler();

    @Test
    void handleAbstractExceptionShouldMapSessionConflictToHttp409() {
        ResponseEntity<ApiResponse<Void>> response = subject.handleAbstractException(
                new SessionConflictException("Another request is already starting a turn for this session")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(BaseErrorCode.CONFLICT.code());
        assertThat(response.getBody().getMessage()).contains("already starting a turn");
    }

    @Test
    void handleMaxUploadSizeExceededShouldMapToClientError() {
        ResponseEntity<ApiResponse<Void>> response = subject.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(1024 * 1024)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(BaseErrorCode.CLIENT_ERROR.code());
        assertThat(response.getBody().getMessage()).contains("size limit");
    }
}
