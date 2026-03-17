package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.IErrorCode;
import lombok.Getter;

@Getter
public abstract class AbstractException extends RuntimeException {

    private final IErrorCode errorCode;
    private final String errorMessage;

    protected AbstractException(IErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    protected AbstractException(IErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errorMessage = message;
    }

    protected AbstractException(IErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.errorMessage = message;
    }

    public int getCode() {
        return errorCode.code();
    }
}
