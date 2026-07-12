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
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        AgentRunCapacityLimiter limiter = newLimiter(redis, new Semaphore(1));

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
    }

    @Test
    void shouldWaitInQueueWhenRedisDenies() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
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
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString()))
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
    void shouldNotRecordWaitRequeuedCounterBeforeMqRequeueSucceeds() {
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
        assertThat(waitCounter).isNull();
    }

    @Test
    void scriptsShouldUseRedisTimeAndOwnershipSafeRenewal() {
        assertThat(AgentRunCapacityLimiter.ACQUIRE_SCRIPT.getScriptAsString())
                .contains("redis.call('TIME')")
                .contains("local ttlMs = tonumber(ARGV[2])")
                .contains("local permitId = ARGV[3]")
                .doesNotContain("local now = tonumber(ARGV");
        assertThat(AgentRunCapacityLimiter.RENEW_SCRIPT.getScriptAsString())
                .contains("redis.call('TIME')")
                .contains("redis.call('zscore', key, permitId)")
                .contains("redis.call('zadd', key, 'XX'")
                .doesNotContain("redis.call('zadd', key, now + ttlMs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCompensateRedisGrantWhenWatchdogCannotStart() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(AgentRunCapacityLimiter.ACQUIRE_SCRIPT), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(redis.execute(eq(AgentRunCapacityLimiter.RELEASE_SCRIPT), anyList(), anyString()))
                .thenReturn(1L);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("stopped"));
        Semaphore semaphore = new Semaphore(1);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        AgentRunCapacityLimiter limiter = new AgentRunCapacityLimiter(
                properties, metricsRecorder, provider, scheduler, semaphore);

        CapacityGateResult result = limiter.tryAcquire(context);

        assertThat(result).isInstanceOf(CapacityGateResult.Proceed.class);
        verify(redis).execute(eq(AgentRunCapacityLimiter.RELEASE_SCRIPT), anyList(), eq(context.member()));
        assertThat(semaphore.availablePermits()).isZero();
        ((CapacityGateResult.Proceed) result).permit().close();
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisPermitCloseShouldBeFinalAndIdempotent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(AgentRunCapacityLimiter.ACQUIRE_SCRIPT), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(redis.execute(eq(AgentRunCapacityLimiter.RELEASE_SCRIPT), anyList(), anyString()))
                .thenReturn(1L);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewalFuture = mock(ScheduledFuture.class);
        org.mockito.ArgumentCaptor<Runnable> renewal = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        doReturn(renewalFuture).when(scheduler)
                .scheduleWithFixedDelay(renewal.capture(), anyLong(), anyLong(), any());
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        AgentRunCapacityLimiter limiter = new AgentRunCapacityLimiter(
                properties, metricsRecorder, provider, scheduler, new Semaphore(1));

        CapacityGateResult result = limiter.tryAcquire(context);
        Permit permit = ((CapacityGateResult.Proceed) result).permit();
        permit.close();
        permit.close();
        renewal.getValue().run();

        verify(renewalFuture, times(1)).cancel(false);
        verify(redis, times(1)).execute(
                eq(AgentRunCapacityLimiter.RELEASE_SCRIPT), anyList(), eq(context.member()));
        verify(redis, never()).execute(eq(AgentRunCapacityLimiter.RENEW_SCRIPT),
                anyList(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldStopWatchdogAfterLeaseIsReportedLost() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(AgentRunCapacityLimiter.ACQUIRE_SCRIPT), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(redis.execute(eq(AgentRunCapacityLimiter.RENEW_SCRIPT), anyList(),
                anyString(), anyString())).thenReturn(0L);
        when(redis.execute(eq(AgentRunCapacityLimiter.RELEASE_SCRIPT), anyList(), anyString()))
                .thenReturn(0L);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewalFuture = mock(ScheduledFuture.class);
        org.mockito.ArgumentCaptor<Runnable> renewal = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        doReturn(renewalFuture).when(scheduler)
                .scheduleWithFixedDelay(renewal.capture(), anyLong(), anyLong(), any());
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        AgentRunCapacityLimiter limiter = new AgentRunCapacityLimiter(
                properties, metricsRecorder, provider, scheduler, new Semaphore(1));

        CapacityGateResult result = limiter.tryAcquire(context);
        renewal.getValue().run();
        renewal.getValue().run();

        verify(renewalFuture, times(1)).cancel(false);
        verify(redis, times(1)).execute(eq(AgentRunCapacityLimiter.RENEW_SCRIPT),
                anyList(), anyString(), eq(context.member()));
        ((CapacityGateResult.Proceed) result).permit().close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseFailureShouldNotMaskResultOrAllowLaterRenewal() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> regProvider = mock(ObjectProvider.class);
        when(regProvider.getIfAvailable()).thenReturn(registry);
        RateLimitMetricsRecorder recorder = new RateLimitMetricsRecorder(regProvider);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(eq(AgentRunCapacityLimiter.ACQUIRE_SCRIPT), anyList(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        when(redis.execute(eq(AgentRunCapacityLimiter.RELEASE_SCRIPT), anyList(), anyString()))
                .thenThrow(new RuntimeException("release unavailable"));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewalFuture = mock(ScheduledFuture.class);
        org.mockito.ArgumentCaptor<Runnable> renewal = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        doReturn(renewalFuture).when(scheduler)
                .scheduleWithFixedDelay(renewal.capture(), anyLong(), anyLong(), any());
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        AgentRunCapacityLimiter limiter = new AgentRunCapacityLimiter(
                properties, recorder, provider, scheduler, new Semaphore(1));
        Permit permit = ((CapacityGateResult.Proceed) limiter.tryAcquire(context)).permit();

        assertThatCode(permit::close).doesNotThrowAnyException();
        permit.close();
        renewal.getValue().run();

        verify(renewalFuture, times(1)).cancel(false);
        verify(redis, times(1)).execute(
                eq(AgentRunCapacityLimiter.RELEASE_SCRIPT), anyList(), eq(context.member()));
        verify(redis, never()).execute(eq(AgentRunCapacityLimiter.RENEW_SCRIPT),
                anyList(), anyString(), anyString());
        assertThat(registry.find("chatagent.agent_run.capacity.permit.releases")
                .tag("outcome", "failed").counter().count()).isEqualTo(1.0D);
        assertThat(registry.find("chatagent.agent_run.capacity.redis.failures")
                .counter().count()).isEqualTo(1.0D);
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
