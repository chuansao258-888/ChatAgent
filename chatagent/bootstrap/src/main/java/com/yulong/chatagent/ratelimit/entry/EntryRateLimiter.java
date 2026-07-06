package com.yulong.chatagent.ratelimit.entry;

import com.yulong.chatagent.ratelimit.LocalTokenBucket;
import com.yulong.chatagent.ratelimit.RateLimitFailurePolicy;
import com.yulong.chatagent.ratelimit.RateLimitMetricsRecorder;
import com.yulong.chatagent.ratelimit.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry-layer token-bucket limiter for {@code POST /api/chat-messages}.
 *
 * <p>Uses a Redis Lua token bucket keyed by hashed user/IP identity. On Redis
 * failure with {@code LOCAL_BUCKET} policy (the default), falls back to a
 * JVM-local token bucket so a transient Redis outage does not disable chat
 * entry. If the local fallback itself errors unexpectedly, it fails open and
 * records a metric rather than breaking the request path.</p>
 *
 * <p>中文说明：HTTP 入口令牌桶限流器。Redis 不可用时用进程内令牌桶兜底，
 * 限流器自身异常时 fail-open，避免把聊天入口打挂。</p>
 */
@Slf4j
@Component
public class EntryRateLimiter {

    private static final String KEY_PREFIX = "chatagent:rate-limit:entry:";
    private static final int KEY_TTL_SECONDS = 120;

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;
    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setScriptText("""
                local key = KEYS[1]
                local capacity = tonumber(ARGV[1])
                local refillPerSecond = tonumber(ARGV[2])
                local requestedTokens = tonumber(ARGV[3])
                local nowMillis = tonumber(ARGV[4])
                local ttlSeconds = tonumber(ARGV[5])

                local bucket = redis.call('hmget', key, 'tokens', 'last_refill_ms')
                local tokens = tonumber(bucket[1])
                local lastRefillMs = tonumber(bucket[2])

                if tokens == nil then
                    tokens = capacity
                    lastRefillMs = nowMillis
                end

                local elapsedSeconds = math.max(0, (nowMillis - lastRefillMs) / 1000.0)
                tokens = math.min(capacity, tokens + elapsedSeconds * refillPerSecond)

                local allowed = 0
                if tokens >= requestedTokens then
                    tokens = tokens - requestedTokens
                    allowed = 1
                end

                redis.call('hmset', key, 'tokens', tostring(tokens), 'last_refill_ms', tostring(nowMillis))
                redis.call('expire', key, ttlSeconds)
                return allowed
                """);
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    private final RateLimitProperties properties;
    private final RateLimitMetricsRecorder metricsRecorder;
    private final EntryRateLimitIdentityResolver identityResolver;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ConcurrentHashMap<String, LocalTokenBucket> localFallbackBuckets = new ConcurrentHashMap<>();

    public EntryRateLimiter(RateLimitProperties properties,
                            RateLimitMetricsRecorder metricsRecorder,
                            EntryRateLimitIdentityResolver identityResolver,
                            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.identityResolver = identityResolver;
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * Checks whether the current request is allowed under the entry limit.
     *
     * <p>Throws {@link RateLimitedException} when the request must be rejected.
     * Does nothing when allowed. When the limiter is disabled, returns
     * immediately without touching Redis.</p>
     *
     * @param request current HTTP request, used for identity resolution
     */
    public void checkAllowed(HttpServletRequest request) {
        RateLimitProperties.Entry entry = properties.getEntry();
        if (!entry.isEnabled()) {
            return;
        }
        EntryRateLimitIdentityResolver.ResolvedIdentity identity = identityResolver.resolve(request);
        if (tryRedisAcquire(identity, entry)) {
            return;
        }
        throw new RateLimitedException();
    }

    private boolean tryRedisAcquire(EntryRateLimitIdentityResolver.ResolvedIdentity identity,
                                    RateLimitProperties.Entry entry) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return handleRedisUnavailable(identity, entry);
        }
        try {
            String key = KEY_PREFIX + identity.scope() + ":" + identity.key();
            long nowMillis = System.currentTimeMillis();
            Long allowed = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(key),
                    String.valueOf(entry.getRequestsPerSecond()),
                    String.valueOf(entry.getRequestsPerSecond()),
                    String.valueOf(1),
                    String.valueOf(nowMillis),
                    String.valueOf(KEY_TTL_SECONDS)
            );
            if (allowed != null && allowed == 1L) {
                metricsRecorder.recordEntryRequest("allowed", identity.scope(), "redis");
                return true;
            }
            if (allowed != null) {
                metricsRecorder.recordEntryRequest("rejected", identity.scope(), "redis");
                return false;
            }
            // Null return indicates a Redis-side issue; treat as unavailable.
            return handleRedisUnavailable(identity, entry);
        } catch (Exception e) {
            log.warn("Entry rate-limit Redis call failed, applying fallback policy: scope={}, error={}",
                    identity.scope(), e.getMessage());
            metricsRecorder.recordCapacityRedisFailure();
            return handleRedisUnavailable(identity, entry);
        }
    }

    private boolean handleRedisUnavailable(EntryRateLimitIdentityResolver.ResolvedIdentity identity,
                                           RateLimitProperties.Entry entry) {
        if (entry.getRedisFailurePolicy() != RateLimitFailurePolicy.LOCAL_BUCKET) {
            // Non-LOCAL_BUCKET policies on the entry layer reject when Redis is down.
            metricsRecorder.recordEntryRequest("rejected", identity.scope(), "local_bucket");
            return false;
        }
        try {
            boolean allowed = localFallbackBuckets
                    .computeIfAbsent(identity.scope() + ":" + identity.key(), ignored -> new LocalTokenBucket(
                            entry.getRequestsPerSecond(),
                            entry.getBurstCapacity(),
                            System::nanoTime
                    ))
                    .tryAcquire();
            metricsRecorder.recordEntryRequest(
                    allowed ? "fallback" : "rejected", identity.scope(), "local_bucket");
            return allowed;
        } catch (Exception fallbackException) {
            // Last-resort fail-open: never let the limiter break the chat entry path.
            log.warn("Entry rate-limit local fallback errored, failing open: scope={}, error={}",
                    identity.scope(), fallbackException.getMessage());
            metricsRecorder.recordEntryRequest("allowed", identity.scope(), "fail_open");
            return true;
        }
    }
}
