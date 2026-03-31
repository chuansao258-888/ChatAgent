package com.yulong.chatagent.mq.lock;

/**
 * Terminal and in-flight states tracked for MQ task-level idempotency.
 */
public enum MqTaskLockState {
    RUNNING,
    COMPLETED,
    FAILED
}
