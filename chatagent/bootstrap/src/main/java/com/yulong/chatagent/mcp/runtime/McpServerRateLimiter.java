package com.yulong.chatagent.mcp.runtime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-server token-bucket limiter for outbound MCP calls.
 */
@Component
public class McpServerRateLimiter {

    private final McpRuntimeProtectionProperties properties;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public McpServerRateLimiter(McpRuntimeProtectionProperties properties) {
        this(properties, System::nanoTime);
    }

    McpServerRateLimiter(McpRuntimeProtectionProperties properties, LongSupplier nanoTimeSupplier) {
        this.properties = properties;
        this.nanoTimeSupplier = nanoTimeSupplier;
    }

    public boolean tryAcquire(String serverId) {
        if (!properties.isRateLimitEnabled() || !StringUtils.hasText(serverId)) {
            return true;
        }
        TokenBucket bucket = buckets.computeIfAbsent(serverId, ignored -> new TokenBucket(
                Math.max(1, properties.getRateLimitRequestsPerSecond()),
                Math.max(1, properties.getRateLimitBurstCapacity())
        ));
        return bucket.tryAcquire(nanoTimeSupplier.getAsLong());
    }

    private static final class TokenBucket {

        private final double refillPerSecond;
        private final double burstCapacity;
        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int refillPerSecond, int burstCapacity) {
            this.refillPerSecond = Math.max(1, refillPerSecond);
            this.burstCapacity = Math.max(1, burstCapacity);
            this.tokens = this.burstCapacity;
            this.lastRefillNanos = -1L;
        }

        private synchronized boolean tryAcquire(long nowNanos) {
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
}
