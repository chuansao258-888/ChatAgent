package com.yulong.chatagent.mq.lock;

/**
 * Acquisition result for session-scoped execution locks.
 */
public record MqSessionExecLockAcquisition(
        MqTaskLockAcquireOutcome outcome,
        MqSessionExecLockLease lease
) {
}
