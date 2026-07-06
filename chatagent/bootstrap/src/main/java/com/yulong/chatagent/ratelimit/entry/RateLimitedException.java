package com.yulong.chatagent.ratelimit.entry;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.AbstractException;

/**
 * Thrown when a chat entry request exceeds the configured token-bucket limit.
 *
 * <p>Maps to HTTP {@code 429} via the existing {@code GlobalExceptionHandler}.
 * The response text is fixed in this phase: no public custom-message
 * constructor is exposed, keeping the message safe and low-variance.</p>
 */
public class RateLimitedException extends AbstractException {

    /**
     * Fixed-message constructor used by the entry limiter on rejection.
     */
    public RateLimitedException() {
        super(BaseErrorCode.TOO_MANY_REQUESTS,
                "Too many chat requests. Please wait a moment and try again.");
    }
}
