package com.yulong.chatagent.mq.lock;

/**
 * Result of attempting to acquire the task-level MQ idempotency lock.
 */
public enum MqTaskLockAcquireOutcome {
    ACQUIRED,
    DUPLICATE,
    WAIT_REQUIRED
}
