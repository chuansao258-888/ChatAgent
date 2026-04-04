package com.yulong.chatagent.rag.parser;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SessionScopedVdpCacheStoreTest {

    @Test
    void shouldExpireIdleSessionBuckets() {
        ManualTicker ticker = new ManualTicker();
        SessionScopedVdpCacheStore store = new SessionScopedVdpCacheStore(64, 1, ticker);
        VdpPageResult result = new VdpPageResult(0, "cached", VdpPageStatus.SUCCESS, Map.of());

        store.put("session-1", "digest-1", result);
        assertThat(store.bucketCount()).isEqualTo(1);

        ticker.advance(3, TimeUnit.MINUTES);

        assertThat(store.get("session-1", "digest-1")).isNull();
        assertThat(store.bucketCount()).isZero();
    }

    private static final class ManualTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        private void advance(long value, TimeUnit unit) {
            nanos.addAndGet(unit.toNanos(value));
        }
    }
}
