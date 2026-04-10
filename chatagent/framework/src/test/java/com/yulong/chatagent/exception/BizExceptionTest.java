package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class BizExceptionTest {

    @Test
    void bizException_carriesClientErrorCode() {
        BizException ex = new BizException("invalid input");

        assertThat(ex.getErrorCode()).isEqualTo(BaseErrorCode.CLIENT_ERROR);
        assertThat(ex.getErrorMessage()).isEqualTo("invalid input");
        assertThat(ex.getCode()).isEqualTo(400);
    }

    @Test
    void clientException_withCustomErrorCode() {
        ClientException ex = new ClientException(BaseErrorCode.FORBIDDEN, "no access");

        assertThat(ex.getCode()).isEqualTo(403);
        assertThat(ex.getErrorMessage()).isEqualTo("no access");
    }

    @Test
    void clientException_withCause() {
        Throwable cause = new RuntimeException("root cause");
        ClientException ex = new ClientException(BaseErrorCode.CONFLICT, "dup", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getErrorCode()).isEqualTo(BaseErrorCode.CONFLICT);
    }

    @Test
    void serviceException_defaultsToServiceErrorCode() {
        ServiceException ex = new ServiceException("db down");

        assertThat(ex.getErrorCode()).isEqualTo(BaseErrorCode.SERVICE_ERROR);
        assertThat(ex.getCode()).isEqualTo(500);
        assertThat(ex.getErrorMessage()).isEqualTo("db down");
    }

    @Test
    void serviceException_withCustomErrorCode() {
        ServiceException ex = new ServiceException(BaseErrorCode.REMOTE_ERROR, "upstream timeout");

        assertThat(ex.getCode()).isEqualTo(502);
    }

    @Test
    void serviceException_withCause() {
        Throwable cause = new IOException("connection reset");
        ServiceException ex = new ServiceException(BaseErrorCode.SERVICE_ERROR, "fail", cause);

        assertThat(ex.getCause()).isSameAs(cause);
    }
}
