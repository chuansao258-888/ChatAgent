package com.yulong.chatagent.errorcode;

import org.springframework.http.HttpStatus;

/**
 * Built-in error codes shared across the application.
 *
 * <p>Domain modules may define additional {@link IErrorCode} implementations,
 * but these cover the common success and failure outcomes surfaced by the
 * global exception handler.</p>
 */
public enum BaseErrorCode implements IErrorCode {

    SUCCESS(200, "success", HttpStatus.OK),
    CLIENT_ERROR(400, "bad request", HttpStatus.BAD_REQUEST),
    FORBIDDEN(403, "forbidden", HttpStatus.FORBIDDEN),
    NOT_FOUND(404, "resource not found", HttpStatus.NOT_FOUND),
    TOO_MANY_REQUESTS(429, "too many requests", HttpStatus.TOO_MANY_REQUESTS),
    CONFLICT(409, "conflict", HttpStatus.CONFLICT),
    SERVICE_ERROR(500, "internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    REMOTE_ERROR(502, "remote service error", HttpStatus.BAD_GATEWAY);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    BaseErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
