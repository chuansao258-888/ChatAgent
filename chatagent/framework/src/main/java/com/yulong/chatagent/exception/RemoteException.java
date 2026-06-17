package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.errorcode.IErrorCode;

/**
 * Base for failures caused by an upstream or remote service.
 *
 * <p>The default error code is {@link BaseErrorCode#REMOTE_ERROR} (bad gateway).</p>
 */
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
