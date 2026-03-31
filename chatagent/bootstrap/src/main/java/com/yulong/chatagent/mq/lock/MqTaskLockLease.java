package com.yulong.chatagent.mq.lock;

import com.yulong.chatagent.mq.support.MqMessageIdentity;

/**
 * Lease granted to the consumer that currently owns a RUNNING MQ task lock.
 */
public record MqTaskLockLease(
        String key,
        String token,
        String owner,
        MqMessageIdentity identity
) {
}
