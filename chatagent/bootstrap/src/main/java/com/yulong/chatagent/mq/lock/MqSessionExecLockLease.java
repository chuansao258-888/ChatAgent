package com.yulong.chatagent.mq.lock;

/**
 * Lease granted to the consumer that currently owns a session-scoped execution lock.
 */
public record MqSessionExecLockLease(
        String key,
        String token,
        String owner,
        String sessionId
) {
}
