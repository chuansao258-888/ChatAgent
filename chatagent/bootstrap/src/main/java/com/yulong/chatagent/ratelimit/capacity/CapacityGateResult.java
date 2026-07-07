package com.yulong.chatagent.ratelimit.capacity;

/**
 * Outcome of an execution-capacity gate check.
 *
 * <p>Three terminal outcomes plus one carrying a held permit:</p>
 * <ul>
 *     <li>{@link #proceed(Permit)} — capacity granted; the permit must be
 *         released in a {@code finally} block after Agent execution.</li>
 *     <li>{@link #waitInQueue()} — no capacity now; the task should be
 *         requeued via MQ delayed requeue without incrementing retryCount.</li>
 *     <li>{@link #failFast()} — Redis unavailable and policy is FAIL_FAST;
 *         the task should fail immediately.</li>
 * </ul>
 */
public sealed interface CapacityGateResult permits CapacityGateResult.Proceed, CapacityGateResult.WaitInQueue, CapacityGateResult.FailFast {

    /**
     * Capacity was granted; the carried permit must be released when done.
     *
     * @param permit permit handle to release in finally
     */
    record Proceed(Permit permit) implements CapacityGateResult {
    }

    /**
     * No capacity available; requeue the task without incrementing retryCount.
     */
    record WaitInQueue() implements CapacityGateResult {
    }

    /**
     * Redis unavailable and FAIL_FAST policy active; fail the task immediately.
     */
    record FailFast() implements CapacityGateResult {
    }

    static CapacityGateResult proceed(Permit permit) {
        return new Proceed(permit);
    }

    static CapacityGateResult waitInQueue() {
        return WaitInQueueHolder.INSTANCE;
    }

    static CapacityGateResult failFast() {
        return FailFastHolder.INSTANCE;
    }

    /** Singleton holder for WaitInQueue to avoid allocation. */
    final class WaitInQueueHolder {
        private static final WaitInQueue INSTANCE = new WaitInQueue();
    }

    /** Singleton holder for FailFast to avoid allocation. */
    final class FailFastHolder {
        private static final FailFast INSTANCE = new FailFast();
    }
}
