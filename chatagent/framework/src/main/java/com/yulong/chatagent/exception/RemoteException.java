package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.errorcode.IErrorCode;

public class RemoteException extends AbstractException {

    public RemoteException(String message) {
        super(BaseErrorCode.REMOTE_ERROR, message);
    }

    public RemoteException(IErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public RemoteException(IErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
