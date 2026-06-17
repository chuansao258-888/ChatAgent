package com.yulong.chatagent.errorcode;

import org.springframework.http.HttpStatus;

/**
 * Contract for application error codes.
 *
 * <p>Each error code exposes a stable numeric identifier, a default message,
 * and the HTTP status used when the error reaches a controller through the
 * global exception handler.</p>
 */
public interface IErrorCode {

    /**
     * Returns the stable numeric error code.
     *
     * @return numeric code
     */
    int code();

    /**
     * Returns the default human-readable message for this error.
     *
     * @return default message
     */
    String message();

    /**
     * Returns the HTTP status associated with this error.
     *
     * @return HTTP status
     */
    HttpStatus httpStatus();
}
