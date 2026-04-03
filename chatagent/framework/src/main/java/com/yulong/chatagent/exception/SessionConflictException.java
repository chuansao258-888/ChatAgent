package com.yulong.chatagent.exception;

import com.yulong.chatagent.errorcode.BaseErrorCode;

/**
 * Raised when a session-scoped concurrency guard rejects an overlapping turn.
 */
public class SessionConflictException extends ClientException {

    public SessionConflictException(String message) {
        super(BaseErrorCode.CONFLICT, message);
    }
}
