package com.yulong.chatagent.ratelimit.capacity;

import com.yulong.chatagent.ratelimit.RateLimitFailurePolicy;
import com.yulong.chatagent.ratelimit.RateLimitMetricsRecorder;
import com.yulong.chatagent.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunCapacityLimiterTest {

    private RateLimitProperties properties;
    private RateLimitMetricsRecorder metricsRecorder;
    private AgentRunCapacityLimiter.PermitContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new RateLimitProperties();
        properties.getAgentRun().setMaxConcurrency(2);
        properties.getAgentRun().setLocalCapacityOnRedisFailure(1);
        ObjectProvider<MeterRegistry> nullProvider = mock(ObjectProvider.class);
        when(nullProvider.getIfAvailable()).thenReturn(null);
        metricsRecorder = new RateLimitMetricsRecorder(nullProvider);
        context = AgentRunCapacityLimiter.PermitContext.forTask("owner-1", "event-1", "turn-1");
    }

    @Test
    void shouldReturnProceedWhenLimiterDisabled() {
        properties.getAgentRun().setEnabled(false);
        Semaphore semaphore = new Semaphore(1);
        AgentRunCapacityLimiter limiter = newLimiter(null, semaphore);

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
    }

    @Test
    void shouldAllowWhenRedisGrants() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        AgentRunCapacityLimiter limiter = newLimiter(redis, new Semaphore(1));

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
    }

    @Test
    void shouldWaitInQueueWhenRedisDenies() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        AgentRunCapacityLimiter limiter = newLimiter(redis, new Semaphore(1));

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.WaitInQueue.class);
    }

    @Test
    void shouldFallBackToLocalCapWhenRedisUnavailable() {
        // No Redis provider -> LOCAL_CAP path (default policy).
        Semaphore semaphore = new Semaphore(1);
        AgentRunCapacityLimiter limiter = newLimiter(null, semaphore);

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
        // Acquiring consumed the single local permit.
        assertThat(semaphore.availablePermits()).isZero();
        // Closing the permit returns it to the local semaphore.
        ((CapacityGateResult.Proceed) result).permit().close();
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void shouldWaitInQueueWhenLocalCapExhaustedAndRedisDown() {
        // Semaphore with 0 permits -> local cap exhausted.
        AgentRunCapacityLimiter limiter = newLimiter(null, new Semaphore(0));

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.WaitInQueue.class);
    }

    @Test
    void shouldFailFastWhenRedisDownAndPolicyFailFast() {
        properties.getAgentRun().setRedisFailurePolicy(RateLimitFailurePolicy.FAIL_FAST);
        AgentRunCapacityLimiter limiter = newLimiter(null, new Semaphore(1));

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.FailFast.class);
    }

    @Test
    void shouldFallBackToLocalCapWhenRedisThrows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));
        AgentRunCapacityLimiter limiter = newLimiter(redis, new Semaphore(1));

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
    }

    @Test
    void localCapOnlyShouldNotTouchRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Semaphore semaphore = new Semaphore(1);
        AgentRunCapacityLimiter limiter = newLimiter(redis, semaphore);

        CapacityGateResult result = limiter.tryAcquireLocalCapOnly();

        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
        // Local cap must not call Redis at all.
        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    @Test
    void permitContextShouldProduceUniqueMembers() {
        AgentRunCapacityLimiter.PermitContext c1 =
                AgentRunCapacityLimiter.PermitContext.forTask("o", "e", "t");
        AgentRunCapacityLimiter.PermitContext c2 =
                AgentRunCapacityLimiter.PermitContext.forTask("o", "e", "t");

        // UUID suffix makes each permit member unique even for the same task.
        assertThat(c1.member()).isNotEqualTo(c2.member());
        assertThat(c1.member()).startsWith("o:e:t:");
    }

    @Test
    void shouldRecordPermitHoldDurationOnLocalCapPermitClose() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> regProvider = mock(ObjectProvider.class);
        when(regProvider.getIfAvailable()).thenReturn(registry);
        RateLimitMetricsRecorder realRecorder = new RateLimitMetricsRecorder(regProvider);

        Semaphore semaphore = new Semaphore(1);
        AgentRunCapacityLimiter limiter = newLimiterWithRecorder(null, semaphore, realRecorder);

        CapacityGateResult result = limiter.tryAcquire(context);
        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
        // Hold the permit briefly so the recorded duration is positive.
        Thread.sleep(10);
        ((CapacityGateResult.Proceed) result).permit().close();

        io.micrometer.core.instrument.Timer holdTimer = registry.find("chatagent.agent_run.capacity.permit.hold.duration").timer();
        assertThat(holdTimer).isNotNull();
        assertThat(holdTimer.count()).isEqualTo(1L);
    }

    @Test
    void shouldRecordWaitRequeuedCounterWhenCapacityDenied() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> regProvider = mock(ObjectProvider.class);
        when(regProvider.getIfAvailable()).thenReturn(registry);
        RateLimitMetricsRecorder realRecorder = new RateLimitMetricsRecorder(regProvider);

        AgentRunCapacityLimiter limiter = newLimiterWithRecorder(null, new Semaphore(0), realRecorder);

        CapacityGateResult result = limiter.tryAcquire(context);
        assertThat(result).isInstanceOf(CapacityGateResult.WaitInQueue.class);

        io.micrometer.core.instrument.Counter waitCounter = registry.find("chatagent.agent_run.capacity.waits")
                .tag("outcome", "requeued").counter();
        assertThat(waitCounter).isNotNull();
        assertThat(waitCounter.count()).isEqualTo(1.0);
    }

    private AgentRunCapacityLimiter newLimiter(StringRedisTemplate redisTemplate, Semaphore semaphore) {
        return newLimiterWithRecorder(redisTemplate, semaphore, metricsRecorder);
    }

    private AgentRunCapacityLimiter newLimiterWithRecorder(StringRedisTemplate redisTemplate,
                                                            Semaphore semaphore,
                                                            RateLimitMetricsRecorder recorder) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        return new AgentRunCapacityLimiter(properties, recorder, provider, scheduler, semaphore);
    }
}
