package com.yulong.chatagent.ratelimit;

import java.util.function.LongSupplier;

/**
 * JVM-local token bucket shared by the entry limiter's Redis-failure fallback.
 *
 * <p>Extracted from {@code McpServerRateLimiter.TokenBucket} so there is a single
 * token-bucket implementation in the codebase rather than a third variant.
 * {@code McpServerRateLimiter} now delegates to this class.</p>
 *
 * <p>中文说明：进程内令牌桶实现。原本是 McpServerRateLimiter 的私有内部类，
 * 现在抽出来供入口限流器的 Redis 降级路径复用，避免出现第三份令牌桶代码。</p>
 */
public final class LocalTokenBucket {

    private final double refillPerSecond;
    private final double burstCapacity;
    private final LongSupplier nanoTimeSupplier;
    private double tokens;
    private long lastRefillNanos;

    /**
     * Builds a token bucket.
     *
     * @param refillPerSecond   tokens added per second (clamped to at least 1)
     * @param burstCapacity     maximum tokens that may accumulate (clamped to at least 1)
     * @param nanoTimeSupplier  wall-clock nanosecond source, injectable for tests
     */
    public LocalTokenBucket(int refillPerSecond, int burstCapacity, LongSupplier nanoTimeSupplier) {
        this.refillPerSecond = Math.max(1, refillPerSecond);
        this.burstCapacity = Math.max(1, burstCapacity);
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.tokens = this.burstCapacity;
        this.lastRefillNanos = -1L;
    }

    /**
     * Attempts to consume one token, refilling based on elapsed time first.
     *
     * @return {@code true} if a token was consumed; {@code false} if the bucket is empty
     */
    public synchronized boolean tryAcquire() {
        long nowNanos = nanoTimeSupplier.getAsLong();
        if (lastRefillNanos < 0L) {
            lastRefillNanos = nowNanos;
        }
        refill(nowNanos);
        if (tokens < 1.0d) {
            return false;
        }
        tokens -= 1.0d;
        return true;
    }

    private void refill(long nowNanos) {
        long elapsedNanos = Math.max(0L, nowNanos - lastRefillNanos);
        if (elapsedNanos == 0L) {
            return;
        }
        double refillTokens = (elapsedNanos / 1_000_000_000.0d) * refillPerSecond;
        tokens = Math.min(burstCapacity, tokens + refillTokens);
        lastRefillNanos = nowNanos;
    }
}
