package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.errorcode.IErrorCode;

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
