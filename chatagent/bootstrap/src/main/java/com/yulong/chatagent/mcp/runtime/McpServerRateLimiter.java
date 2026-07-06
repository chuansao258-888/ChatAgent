package com.yulong.chatagent.mcp.runtime;

import com.yulong.chatagent.ratelimit.LocalTokenBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-server token-bucket limiter for outbound MCP calls.
 *
 * <p>Delegates the token-bucket implementation to {@link LocalTokenBucket} so
 * there is a single shared token-bucket implementation in the codebase.</p>
 */
@Component
public class McpServerRateLimiter {

    private final McpRuntimeProtectionProperties properties;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentHashMap<String, LocalTokenBucket> buckets = new ConcurrentHashMap<>();

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
        LocalTokenBucket bucket = buckets.computeIfAbsent(serverId, ignored -> new LocalTokenBucket(
                Math.max(1, properties.getRateLimitRequestsPerSecond()),
                Math.max(1, properties.getRateLimitBurstCapacity()),
                nanoTimeSupplier
        ));
        return bucket.tryAcquire();
    }
}
