package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.IErrorCode;
import lombok.Getter;

/**
 * Common base for all application-defined runtime exceptions.
 *
 * <p>Every application exception carries an {@link IErrorCode} that decides the
 * HTTP status and stable numeric code returned to clients, alongside a
 * human-readable message. Subclasses pin a default error code for a category
 * of failure (client, service, remote, ...).</p>
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    private final IErrorCode errorCode;
    private final String errorMessage;

    /**
     * Builds an exception whose message is taken from the supplied error code.
     *
     * @param errorCode structured error code
     */
    protected AbstractException(IErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    /**
     * Builds an exception with an explicit message.
     *
     * @param errorCode structured error code
     * @param message   human-readable detail message
     */
    protected AbstractException(IErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errorMessage = message;
    }

    /**
     * Builds an exception that wraps an underlying cause.
     *
     * @param errorCode structured error code
     * @param message   human-readable detail message
     * @param cause     underlying cause
     */
    protected AbstractException(IErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.errorMessage = message;
    }

    /**
     * Returns the stable numeric code carried by this error.
     *
     * @return error code value
     */
    public int getCode() {
        return errorCode.code();
    }
}
