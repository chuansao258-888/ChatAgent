package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.errorcode.IErrorCode;

public class ServiceException extends AbstractException {

    public ServiceException(String message) {
        super(BaseErrorCode.SERVICE_ERROR, message);
    }

    public ServiceException(IErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ServiceException(IErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
