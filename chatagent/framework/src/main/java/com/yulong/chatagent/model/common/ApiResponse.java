package com.yulong.chatagent.model.common;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.errorcode.IErrorCode;
import com.yulong.chatagent.trace.TraceContext;
import lombok.Data;

/**
 * Standard API envelope used by HTTP controllers across the application.
 *
 * @param <T> payload type carried by the response
 */
@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    /**
     * Builds a success response with payload and the current trace ID.
     *
     * @param data response payload
     * @param <T> payload type
     * @return success response
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                BaseErrorCode.SUCCESS.code(),
                BaseErrorCode.SUCCESS.message(),
                data,
                TraceContext.getTraceId()
        );
    }

    /**
     * Builds an empty success response with the current trace ID.
     *
     * @param <T> payload type
     * @return success response carrying no payload
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * Builds a success response with a custom message.
     *
     * @param data response payload
     * @param message custom success message
     * @param <T> payload type
     * @return success response
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(
                BaseErrorCode.SUCCESS.code(),
                message,
                data,
                TraceContext.getTraceId()
        );
    }

    /**
     * Builds an error response using the error code's default message.
     *
     * @param errorCode application error code
     * @param <T> payload type
     * @return error response
     */
    public static <T> ApiResponse<T> error(IErrorCode errorCode) {
        return error(errorCode, errorCode.message());
    }

    /**
     * Builds an error response from a structured error code and message.
     *
     * @param errorCode application error code
     * @param message response message
     * @param <T> payload type
     * @return error response
     */
    public static <T> ApiResponse<T> error(IErrorCode errorCode, String message) {
        return new ApiResponse<>(
                errorCode.code(),
                message,
                null,
                TraceContext.getTraceId()
        );
    }

    /**
     * Builds a generic internal-server-error response with a custom message.
     *
     * @param message response message
     * @param <T> payload type
     * @return error response
     */
    public static <T> ApiResponse<T> error(String message) {
        return error(BaseErrorCode.SERVICE_ERROR, message);
    }
}
