package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<ApiResponse<Void>> handleAbstractException(AbstractException e) {
        log.warn("Request failed: code={}, message={}, traceId={}",
                e.getCode(), e.getErrorMessage(), TraceContext.getTraceId(), e);
        return ResponseEntity.status(e.getErrorCode().httpStatus())
                .body(ApiResponse.error(e.getErrorCode(), e.getErrorMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handle404(NoResourceFoundException e) {
        return ResponseEntity.status(BaseErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.error(BaseErrorCode.NOT_FOUND, "Resource not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled server error, traceId={}", TraceContext.getTraceId(), e);
        return ResponseEntity.status(BaseErrorCode.SERVICE_ERROR.httpStatus())
                .body(ApiResponse.error(BaseErrorCode.SERVICE_ERROR, "Internal server error"));
    }
}
