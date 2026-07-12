package com.yulong.chatagent.agent.runtime;

/** Thread-bound remaining deadline for owned synchronous tool adapters. */
public final class CurrentToolDeadlineHolder {

    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

    private CurrentToolDeadlineHolder() {
    }

    public static void bindRemainingMillis(long remainingMillis) {
        if (remainingMillis <= 0) {
            throw new ToolDeadlineExceededException();
        }
        long nanos = Math.multiplyExact(remainingMillis, 1_000_000L);
        DEADLINE_NANOS.set(System.nanoTime() + nanos);
    }

    public static long requireRemainingMillis() {
        Long deadline = DEADLINE_NANOS.get();
        if (deadline == null) {
            throw new IllegalStateException("Tool deadline context is required");
        }
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;
        if (remaining <= 0) {
            throw new ToolDeadlineExceededException();
        }
        return remaining;
    }

    /** Keeps direct adapter tests and non-agent callers usable while production dispatch remains deadline-bound. */
    public static long remainingMillisOrDefault(long defaultMillis) {
        if (DEADLINE_NANOS.get() == null) {
            return defaultMillis;
        }
        return requireRemainingMillis();
    }

    public static void clear() {
        DEADLINE_NANOS.remove();
    }

    public static final class ToolDeadlineExceededException extends RuntimeException {
        public ToolDeadlineExceededException() {
            super("Tool execution deadline exceeded");
        }
    }
}
