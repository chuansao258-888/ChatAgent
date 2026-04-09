package com.yulong.chatagent.rag.parser;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Per-session cache buckets so hot sessions do not evict one another from a shared global LRU.
 */
final class SessionScopedVdpCacheStore {

    private static final long MAX_ACTIVE_SESSION_BUCKETS = 1_000L;

    private final long perSessionMaxSize;
    private final long ttlMinutes;
    private final Cache<String, Cache<String, VdpPageResult>> sessionBuckets;

    SessionScopedVdpCacheStore(long perSessionMaxSize, long ttlMinutes) {
        this(perSessionMaxSize, ttlMinutes, Ticker.systemTicker());
    }

    SessionScopedVdpCacheStore(long perSessionMaxSize, long ttlMinutes, Ticker ticker) {
        this.perSessionMaxSize = Math.max(32L, perSessionMaxSize);
        this.ttlMinutes = Math.max(1L, ttlMinutes);
        this.sessionBuckets = Caffeine.newBuilder()
                .maximumSize(MAX_ACTIVE_SESSION_BUCKETS)
                .expireAfterAccess(Duration.ofMinutes(Math.max(2L, this.ttlMinutes * 2L)))
                .ticker(ticker == null ? Ticker.systemTicker() : ticker)
                .build();
    }

    VdpPageResult get(String sessionId, String keySuffix) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(keySuffix)) {
            return null;
        }
        Cache<String, VdpPageResult> cache = sessionBuckets.getIfPresent(normalizeSessionId(sessionId));
        return cache == null ? null : cache.getIfPresent(keySuffix.trim());
    }

    void put(String sessionId, String keySuffix, VdpPageResult result) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(keySuffix) || result == null) {
            return;
        }
        sessionBuckets.get(normalizeSessionId(sessionId), ignored -> createBucket())
                .put(keySuffix.trim(), result);
    }

    long bucketCount() {
        sessionBuckets.cleanUp();
        return sessionBuckets.estimatedSize();
    }

    private Cache<String, VdpPageResult> createBucket() {
        return Caffeine.newBuilder()
                .maximumSize(perSessionMaxSize)
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .build();
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId.trim();
    }
}
