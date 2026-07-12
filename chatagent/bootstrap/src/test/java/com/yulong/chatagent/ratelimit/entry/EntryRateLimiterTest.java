package com.yulong.chatagent.ratelimit.entry;

import com.github.benmanes.caffeine.cache.Ticker;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.ratelimit.RateLimitFailurePolicy;
import com.yulong.chatagent.ratelimit.RateLimitMetricsRecorder;
import com.yulong.chatagent.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntryRateLimiterTest {

    private RateLimitProperties properties;
    private RateLimitMetricsRecorder metricsRecorder;
    private EntryRateLimitIdentityResolver identityResolver;
    private EntryRateLimiter entryRateLimiter;
    private HttpServletRequest request;
    private EntryRateLimitIdentityResolver.ResolvedIdentity userIdentity;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new RateLimitProperties();
        properties.getEntry().setRequestsPerSecond(1);
        properties.getEntry().setBurstCapacity(1);
        // No-op metrics recorder (no MeterRegistry available).
        ObjectProvider<MeterRegistry> nullMetricsProvider = mock(ObjectProvider.class);
        when(nullMetricsProvider.getIfAvailable()).thenReturn(null);
        metricsRecorder = new RateLimitMetricsRecorder(nullMetricsProvider);
        identityResolver = mock(EntryRateLimitIdentityResolver.class);
        request = mock(HttpServletRequest.class);
        userIdentity = new EntryRateLimitIdentityResolver.ResolvedIdentity("user", "hashed-user-1");
        when(identityResolver.resolve(request)).thenReturn(userIdentity);

        ObjectProvider<StringRedisTemplate> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        entryRateLimiter = new EntryRateLimiter(properties, metricsRecorder, identityResolver, emptyProvider);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void shouldSelectProductionConstructorInSpringContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RateLimitProperties.class, () -> properties);
            context.registerBean(RateLimitMetricsRecorder.class, () -> metricsRecorder);
            context.registerBean(EntryRateLimitIdentityResolver.class, () -> identityResolver);
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            context.register(EntryRateLimiter.class);

            context.refresh();

            assertThat(context.getBean(EntryRateLimiter.class)).isNotNull();
        }
    }

    @Test
    void shouldSkipCheckWhenDisabled() {
        properties.getEntry().setEnabled(false);

        assertThatCode(() -> entryRateLimiter.checkAllowed(request))
                .doesNotThrowAnyException();

        verify(identityResolver, never()).resolve(any());
    }

    @Test
    void shouldRejectWithRateLimitedExceptionWhenRedisUnavailableAndFailFast() {
        // FAIL_FAST policy -> reject immediately on Redis unavailable.
        properties.getEntry().setRedisFailurePolicy(RateLimitFailurePolicy.FAIL_FAST);

        assertThatThrownBy(() -> entryRateLimiter.checkAllowed(request))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void shouldFallBackToLocalBucketWhenRedisUnavailable() {
        // Default LOCAL_BUCKET policy with burst capacity 1.
        // First acquire succeeds; second is rejected.
        assertThatCode(() -> entryRateLimiter.checkAllowed(request))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> entryRateLimiter.checkAllowed(request))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectWhenRedisReturnsZero() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        EntryRateLimiter redisBackedLimiter = new EntryRateLimiter(properties, metricsRecorder, identityResolver, provider);

        assertThatThrownBy(() -> redisBackedLimiter.checkAllowed(request))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAllowWhenRedisReturnsOne() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        EntryRateLimiter redisBackedLimiter = new EntryRateLimiter(properties, metricsRecorder, identityResolver, provider);

        assertThatCode(() -> redisBackedLimiter.checkAllowed(request))
                .doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPassBurstCapacityAndRefillRateAsDistinctRedisArgs() {
        // Regression: the Lua script's ARGV[1] is capacity (burst) and ARGV[2] is
        // refillPerSecond. They must NOT both be requestsPerSecond, otherwise the
        // Redis bucket's burst is wrong (e.g. burst=2 instead of configured 5).
        properties.getEntry().setRequestsPerSecond(1);
        properties.getEntry().setBurstCapacity(3);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        EntryRateLimiter redisBackedLimiter = new EntryRateLimiter(properties, metricsRecorder, identityResolver, provider);

        redisBackedLimiter.checkAllowed(request);

        org.mockito.ArgumentCaptor<String> argv = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(any(), anyList(), argv.capture(), argv.capture(), argv.capture(),
                argv.capture(), argv.capture());
        java.util.List<String> args = argv.getAllValues();
        // ARGV[1] = capacity (burst), ARGV[2] = refillPerSecond.
        assertThat(args.get(0)).isEqualTo("3"); // burstCapacity
        assertThat(args.get(1)).isEqualTo("1"); // requestsPerSecond (refill)
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFallBackToLocalWhenRedisThrows() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis connection refused"));
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        EntryRateLimiter redisBackedLimiter = new EntryRateLimiter(properties, metricsRecorder, identityResolver, provider);

        // First call falls back to local bucket (burst=1) and succeeds.
        assertThatCode(() -> redisBackedLimiter.checkAllowed(request))
                .doesNotThrowAnyException();
        // Second local acquire is rejected.
        assertThatThrownBy(() -> redisBackedLimiter.checkAllowed(request))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void identityResolverShouldPreferUserIdOverIp() {
        LoginUser user = LoginUser.builder().userId("user-42").build();
        UserContext.set(user);
        EntryRateLimitIdentityResolver realResolver = new EntryRateLimitIdentityResolver();
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);

        EntryRateLimitIdentityResolver.ResolvedIdentity identity = realResolver.resolve(mockRequest);

        assertThat(identity.scope()).isEqualTo("user");
        assertThat(identity.key()).isNotEqualTo("user-42");
        assertThat(identity.key()).hasSize(64); // SHA-256 hex digest
        // Same input must produce the same hash (stable key).
        assertThat(realResolver.resolve(mockRequest).key()).isEqualTo(identity.key());
        // Different user must produce a different hash.
        UserContext.clear();
        UserContext.set(LoginUser.builder().userId("user-99").build());
        assertThat(realResolver.resolve(mockRequest).key()).isNotEqualTo(identity.key());
    }

    @Test
    void identityResolverShouldUseIpWhenNoUser() {
        EntryRateLimitIdentityResolver realResolver = new EntryRateLimitIdentityResolver();
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        EntryRateLimitIdentityResolver.ResolvedIdentity identity = realResolver.resolve(mockRequest);

        assertThat(identity.scope()).isEqualTo("ip");
        assertThat(identity.key()).hasSize(64);
    }

    @Test
    void rateLimitedExceptionShouldCarryFixed429Message() {
        RateLimitedException ex = new RateLimitedException();

        assertThat(ex.getCode()).isEqualTo(429);
        assertThat(ex.getErrorMessage())
                .isEqualTo("Too many chat requests. Please wait a moment and try again.");
        assertThat(ex.getErrorCode().httpStatus().value()).isEqualTo(429);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecordEntryRedisFailureOnEntryLayerNotCapacityMetric() {
        // Regression: entry Redis failures must increment the entry counter, not
        // the capacity limiter counter (they were previously conflated).
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> regProvider = mock(ObjectProvider.class);
        when(regProvider.getIfAvailable()).thenReturn(registry);
        RateLimitMetricsRecorder realRecorder = new RateLimitMetricsRecorder(regProvider);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        EntryRateLimiter limiter = new EntryRateLimiter(properties, realRecorder, identityResolver, provider);

        // Local bucket fallback (burst=1) allows the first call through.
        assertThatCode(() -> limiter.checkAllowed(request)).doesNotThrowAnyException();

        // Entry Redis failure counter incremented; capacity counter NOT.
        io.micrometer.core.instrument.Counter entryCounter = registry.find("chatagent.rate_limit.entry.redis.failures").counter();
        io.micrometer.core.instrument.Counter capacityCounter = registry.find("chatagent.agent_run.capacity.redis.failures").counter();
        assertThat(entryCounter).isNotNull();
        assertThat(entryCounter.count()).isEqualTo(1.0);
        assertThat(capacityCounter).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBoundLocalFallbackCacheAndRecordPrivacySafeEvictions() {
        properties.getEntry().setLocalFallbackMaxIdentities(2);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> regProvider = mock(ObjectProvider.class);
        when(regProvider.getIfAvailable()).thenReturn(registry);
        RateLimitMetricsRecorder recorder = new RateLimitMetricsRecorder(regProvider);
        ObjectProvider<StringRedisTemplate> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        when(identityResolver.resolve(request)).thenReturn(
                new EntryRateLimitIdentityResolver.ResolvedIdentity("user", "hashed-user-1"),
                new EntryRateLimitIdentityResolver.ResolvedIdentity("user", "hashed-user-2"),
                new EntryRateLimitIdentityResolver.ResolvedIdentity("user", "hashed-user-3"));
        EntryRateLimiter limiter = new EntryRateLimiter(
                properties, recorder, identityResolver, emptyProvider, Ticker.systemTicker());

        limiter.checkAllowed(request);
        limiter.checkAllowed(request);
        limiter.checkAllowed(request);
        limiter.cleanUpLocalFallbackBuckets();

        assertThat(limiter.localFallbackBucketCount()).isLessThanOrEqualTo(2L);
        io.micrometer.core.instrument.Counter evictionCounter = registry.find(
                        "chatagent.rate_limit.entry.local_cache.evictions")
                .counter();
        assertThat(evictionCounter).isNotNull();
        assertThat(evictionCounter.count()).isGreaterThanOrEqualTo(1.0D);
        assertThat(registry.find("chatagent.rate_limit.entry.local_cache.size").gauge().value())
                .isLessThanOrEqualTo(2.0D);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("hashed-user"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExpireIdleBucketAndDocumentFreshBurstOnRecreation() {
        AtomicLong tickerNanos = new AtomicLong();
        ObjectProvider<StringRedisTemplate> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        EntryRateLimiter limiter = new EntryRateLimiter(
                properties, metricsRecorder, identityResolver, emptyProvider, tickerNanos::get);

        assertThatCode(() -> limiter.checkAllowed(request)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkAllowed(request)).isInstanceOf(RateLimitedException.class);

        tickerNanos.addAndGet(TimeUnit.SECONDS.toNanos(61));
        limiter.cleanUpLocalFallbackBuckets();
        assertThat(limiter.localFallbackBucketCount()).isZero();

        assertThatCode(() -> limiter.checkAllowed(request)).doesNotThrowAnyException();
        assertThat(limiter.localFallbackBucketCount()).isEqualTo(1L);
    }
}
