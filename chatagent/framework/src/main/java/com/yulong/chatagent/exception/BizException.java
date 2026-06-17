package com.yulong.chatagent.exception;

/**
 * Generic business-rule violation reported to the caller as a client error.
 *
 * <p>Use this for validation failures or domain invariants that map to a
 * {@code 400} response when no dedicated error-code subclass exists.</p>
 */
public class BizException extends ClientException {

    public BizException(String message) {
        super(message);
    }
}
