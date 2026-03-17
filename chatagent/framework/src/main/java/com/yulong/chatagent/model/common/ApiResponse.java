package com.yulong.chatagent.model.common;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.errorcode.IErrorCode;
import com.yulong.chatagent.trace.TraceContext;
import lombok.Data;

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

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                BaseErrorCode.SUCCESS.code(),
                BaseErrorCode.SUCCESS.message(),
                data,
                TraceContext.getTraceId()
        );
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(
                BaseErrorCode.SUCCESS.code(),
                message,
                data,
                TraceContext.getTraceId()
        );
    }

    public static <T> ApiResponse<T> error(IErrorCode errorCode) {
        return error(errorCode, errorCode.message());
    }

    public static <T> ApiResponse<T> error(IErrorCode errorCode, String message) {
        return new ApiResponse<>(
                errorCode.code(),
                message,
                null,
                TraceContext.getTraceId()
        );
    }

    public static <T> ApiResponse<T> error(String message) {
        return error(BaseErrorCode.SERVICE_ERROR, message);
    }
}
