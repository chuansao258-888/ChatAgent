package com.yulong.chatagent.ratelimit.capacity;

import com.yulong.chatagent.ratelimit.RateLimitFailurePolicy;
import com.yulong.chatagent.ratelimit.RateLimitMetricsRecorder;
import com.yulong.chatagent.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in local fault test; the owning runner is responsible for exclusive Docker access. */
class AgentRunCapacityLimiterRedisFailureIT {

    @Test
    @SuppressWarnings("unchecked")
    void shouldApplyBothFailurePoliciesAndReturnToRedisAfterRecovery() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("CHATAGENT_RUN_REDIS_FAILURE_IT")));
        Path evidencePath = Path.of(System.getenv("CHATAGENT_REDIS_FAILURE_EVIDENCE"));
        ensureContainer("start");

        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300)).shutdownTimeout(Duration.ZERO).build();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 6379), client);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.delete(AgentRunCapacityLimiter.ACTIVE_PERMITS_KEY);

        RateLimitProperties properties = new RateLimitProperties();
        properties.getAgentRun().setEnabled(true);
        properties.getAgentRun().setMaxConcurrency(3);
        properties.getAgentRun().setLocalCapacityOnRedisFailure(1);
        properties.getAgentRun().setPermitTtlMs(5_000L);
        properties.getAgentRun().setPermitRenewIntervalMs(1_000L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(registry);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        AgentRunCapacityLimiter limiter = new AgentRunCapacityLimiter(
                properties, new RateLimitMetricsRecorder(meterProvider), redisProvider,
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(), new Semaphore(1));

        Permit localPermit = null;
        Permit recoveredPermit = null;
        try {
            Permit healthyPermit = proceed(limiter.tryAcquire(context("healthy")));
            healthyPermit.close();
            assertThat(redis.opsForZSet().zCard(AgentRunCapacityLimiter.ACTIVE_PERMITS_KEY)).isZero();

            ensureContainer("stop");
            localPermit = proceed(limiter.tryAcquire(context("local")));
            properties.getAgentRun().setRedisFailurePolicy(RateLimitFailurePolicy.FAIL_FAST);
            assertThat(limiter.tryAcquire(context("fail-fast")))
                    .isInstanceOf(CapacityGateResult.FailFast.class);

            ensureContainer("start");
            waitForRedis(redis);
            properties.getAgentRun().setRedisFailurePolicy(RateLimitFailurePolicy.LOCAL_CAP);
            for (int attempt = 0; attempt < 20 && recoveredPermit == null; attempt++) {
                CapacityGateResult result = limiter.tryAcquire(context("recovered-" + attempt));
                if (result instanceof CapacityGateResult.Proceed proceed && redisAllowed(registry) >= 2.0D) {
                    recoveredPermit = proceed.permit();
                } else {
                    Thread.sleep(200L);
                }
            }
            assertThat(recoveredPermit).isNotNull();
            recoveredPermit.close();
            recoveredPermit = null;
            assertThat(registry.find("chatagent.agent_run.capacity.acquire")
                    .tag("outcome", "fallback").tag("policy", "local_cap")
                    .counter().count()).isEqualTo(1.0D);
            assertThat(registry.find("chatagent.agent_run.capacity.acquire")
                    .tag("outcome", "denied").tag("policy", "fail_fast")
                    .counter().count()).isEqualTo(1.0D);

            Files.createDirectories(evidencePath.getParent());
            Files.writeString(evidencePath, """
                    {"schemaVersion":1,"healthyRedisAcquire":true,"localCapAcquire":true,"failFastDenied":true,"recoveredRedisAcquire":true,"existingLocalPermitHeldAcrossRecovery":true}
                    """);
        } finally {
            if (recoveredPermit != null) recoveredPermit.close();
            if (localPermit != null) localPermit.close();
            ensureContainer("start");
            waitForRedis(redis);
            redis.delete(AgentRunCapacityLimiter.ACTIVE_PERMITS_KEY);
            limiter.shutdown();
            connectionFactory.destroy();
        }
    }

    private static double redisAllowed(SimpleMeterRegistry registry) {
        var counter = registry.find("chatagent.agent_run.capacity.acquire")
                .tag("outcome", "allowed").tag("policy", "redis").counter();
        return counter == null ? 0.0D : counter.count();
    }

    private static AgentRunCapacityLimiter.PermitContext context(String suffix) {
        return AgentRunCapacityLimiter.PermitContext.forTask(
                "redis-failure-it", "event-" + suffix, "turn-" + suffix);
    }

    private static Permit proceed(CapacityGateResult result) {
        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
        return ((CapacityGateResult.Proceed) result).permit();
    }

    private static void ensureContainer(String action) throws Exception {
        Process process = new ProcessBuilder("docker", action, "chatagent-redis")
                .redirectErrorStream(true).start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertThat(process.waitFor()).isZero();
    }

    private static void waitForRedis(StringRedisTemplate redis) throws Exception {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                redis.getConnectionFactory().getConnection().ping();
                return;
            } catch (RuntimeException exception) {
                last = exception;
                Thread.sleep(200L);
            }
        }
        throw new IllegalStateException("Redis did not recover", last);
    }
}
