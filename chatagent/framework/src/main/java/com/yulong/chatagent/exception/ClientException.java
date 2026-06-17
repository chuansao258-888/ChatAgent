package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.errorcode.IErrorCode;

/**
 * Base for caller-side failures that map to {@code 4xx} HTTP responses.
 *
 * <p>The default error code is {@link BaseErrorCode#CLIENT_ERROR}.</p>
 */
public class ClientException extends AbstractException {

    public ClientException(String message) {
        super(BaseErrorCode.CLIENT_ERROR, message);
    }

    public ClientException(IErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ClientException(IErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
