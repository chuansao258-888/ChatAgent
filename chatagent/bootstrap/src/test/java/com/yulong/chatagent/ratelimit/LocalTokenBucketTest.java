package com.yulong.chatagent.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTokenBucketTest {

    @Test
    void shouldAllowBurstUntilExhaustedThenReject() {
        AtomicLong now = new AtomicLong(0L);
        LocalTokenBucket bucket = new LocalTokenBucket(2, 3, now::get);

        // Bucket starts full at burst capacity 3.
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        // Tokens exhausted.
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    void shouldRefillOverTimeAtConfiguredRate() {
        AtomicLong now = new AtomicLong(0L);
        // 1 token/sec, burst 1.
        LocalTokenBucket bucket = new LocalTokenBucket(1, 1, now::get);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();

        // Advance 1 second -> exactly 1 token refilled (capped at burst).
        now.set(1_000_000_000L);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    void shouldCapRefillAtBurstCapacity() {
        AtomicLong now = new AtomicLong(0L);
        // 10 tokens/sec, burst 2.
        LocalTokenBucket bucket = new LocalTokenBucket(10, 2, now::get);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();

        // Wait a long time; refill must not exceed burst capacity.
        now.set(10_000_000_000L);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    void shouldClampDegenerateConfigToOne() {
        LocalTokenBucket bucket = new LocalTokenBucket(0, 0, System::nanoTime);

        // Zero/negative capacity clamped to 1, so exactly one acquire succeeds.
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }
}
