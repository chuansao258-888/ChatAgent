package com.yulong.chatagent.ratelimit.capacity;

/**
 * Handle for an acquired execution permit.
 *
 * <p>Implementations are {@link AutoCloseable} so the permit can be released
 * in a {@code try-with-resources} block, matching the existing MQ lock
 * watchdog style. {@link #close()} releases the permit and must be safe to
 * call exactly once.</p>
 */
public interface Permit extends AutoCloseable {

    /**
     * Releases the permit. Must not throw; release failures are logged by the
     * implementation rather than propagating, so the original Agent result is
     * never masked.
     */
    @Override
    void close();
}
