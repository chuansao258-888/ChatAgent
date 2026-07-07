package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.sse.SseClientDisconnects;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

@RestControllerAdvice
@Slf4j
/**
 * Converts application exceptions into the standard {@link ApiResponse} format.
 */
public class GlobalExceptionHandler {

    /**
     * Handles known business and service exceptions carrying explicit error codes.
     *
     * @param e structured application exception
     * @return error response with the original HTTP status
     */
    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<ApiResponse<Void>> handleAbstractException(AbstractException e) {
        log.warn("Request failed: code={}, message={}, traceId={}",
                e.getCode(), e.getErrorMessage(), TraceContext.getTraceId(), e);
        return ResponseEntity.status(e.getErrorCode().httpStatus())
                .body(ApiResponse.error(e.getErrorCode(), e.getErrorMessage()));
    }

    /**
     * Returns a normalized payload for unmapped resources.
     *
     * @param e missing-resource exception
     * @return 404 response in the standard API envelope
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handle404(NoResourceFoundException e) {
        return ResponseEntity.status(BaseErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.error(BaseErrorCode.NOT_FOUND, "Resource not found"));
    }

    /**
     * Returns a client-facing validation error when multipart requests exceed the configured upload limit.
     *
     * @param e multipart size exception thrown before controller invocation
     * @return normalized client error response with guidance about the configured limit
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("Upload rejected because request exceeded multipart size limit, traceId={}",
                TraceContext.getTraceId(), e);
        return ResponseEntity.status(BaseErrorCode.CLIENT_ERROR.httpStatus())
                .body(ApiResponse.error(
                        BaseErrorCode.CLIENT_ERROR,
                        "Uploaded file exceeds the configured size limit"
                ));
    }

    /**
     * Treats client disconnects during streaming response writes as already-handled.
     * Non-disconnect IOExceptions are rethrown for the fallback handler.
     *
     * @param e IOException from a streaming response write
     * @return 204 No Content for client-disconnect cases (the client is gone);
     *         never returned for non-disconnect cases (rethrown instead)
     * @throws IOException when the exception is not a client disconnect
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleIOException(IOException e) throws IOException {
        if (SseClientDisconnects.isLikelyClientDisconnect(e)) {
            log.debug("Client disconnected before response write completed, traceId={}", TraceContext.getTraceId());
            return ResponseEntity.noContent().build();
        }
        throw e;
    }

    /**
     * Fallback handler for unexpected server-side failures.
     *
     * @param e unhandled exception
     * @return generic internal-server-error response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled server error, traceId={}", TraceContext.getTraceId(), e);
        return ResponseEntity.status(BaseErrorCode.SERVICE_ERROR.httpStatus())
                .body(ApiResponse.error(BaseErrorCode.SERVICE_ERROR, "Internal server error"));
    }
}
