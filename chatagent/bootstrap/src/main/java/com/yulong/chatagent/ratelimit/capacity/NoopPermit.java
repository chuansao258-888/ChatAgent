package com.yulong.chatagent.ratelimit.capacity;

/**
 * Permit that does nothing on release, used as the default capacity-gate
 * result so the base {@code AbstractRetryingMqConsumer} no-op hook needs no
 * special-casing.
 */
public final class NoopPermit implements Permit {

    private static final NoopPermit INSTANCE = new NoopPermit();

    private NoopPermit() {
    }

    /**
     * Returns the singleton no-op permit.
     *
     * @return shared no-op permit instance
     */
    public static NoopPermit instance() {
        return INSTANCE;
    }

    @Override
    public void close() {
        // No-op: knowledge-ingest and other non-agent consumers inherit this.
    }
}
