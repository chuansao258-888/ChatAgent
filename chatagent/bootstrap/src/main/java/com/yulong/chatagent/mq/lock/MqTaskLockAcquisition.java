package com.yulong.chatagent.mq.lock;

/**
 * Acquisition result plus the existing state when the task is recognized as a duplicate.
 */
public record MqTaskLockAcquisition(
        MqTaskLockAcquireOutcome outcome,
        MqTaskLockLease lease,
        MqTaskLockState existingState
) {
}
