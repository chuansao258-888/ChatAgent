package com.yulong.chatagent.conversation.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRunCoordinatorTest {

    @Test
    void sameSessionWaitsAndLeaseCleanupIsOwnershipSafe() throws Exception {
        SessionRunProperties properties = properties(2_000);
        SessionRunCoordinator coordinator = new SessionRunCoordinator(properties);
        SessionRunCoordinator.RunLease first = coordinator.acquire("session-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SessionRunCoordinator.RunLease> waiting = executor.submit(() -> coordinator.acquire("session-1"));
            Thread.sleep(50);
            assertThat(waiting.isDone()).isFalse();
            first.close();
            first.close();
            try (SessionRunCoordinator.RunLease second = waiting.get(1, TimeUnit.SECONDS)) {
                assertThat(second).isNotNull();
            }
            assertThat(coordinator.activeSessionCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentSessionsRunConcurrently() throws Exception {
        SessionRunCoordinator coordinator = new SessionRunCoordinator(properties(1_000));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try {
            Future<?> first = executor.submit(() -> runHeld(coordinator, "session-1", entered, release, concurrent, maximum));
            Future<?> second = executor.submit(() -> runHeld(coordinator, "session-2", entered, release, concurrent, maximum));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(2);
            release.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertThat(coordinator.activeSessionCount()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutDoesNotLeakEntry() {
        SessionRunCoordinator coordinator = new SessionRunCoordinator(properties(10));
        try (SessionRunCoordinator.RunLease ignored = coordinator.acquire("session-1")) {
            assertThat(coordinator.acquire("session-1")).isNull();
            assertThat(coordinator.activeSessionCount()).isEqualTo(1);
        }
        assertThat(coordinator.activeSessionCount()).isZero();
    }

    @Test
    void exposesEveryConfiguredRedisFailurePolicy() {
        for (SessionRunProperties.RedisFailurePolicy policy : SessionRunProperties.RedisFailurePolicy.values()) {
            SessionRunProperties properties = properties(10);
            properties.setRedisFailurePolicy(policy);
            assertThat(new SessionRunCoordinator(properties).redisFailurePolicy()).isEqualTo(policy);
        }
    }

    @Test
    void interruptedWaitRestoresInterruptAndDoesNotLeak() throws Exception {
        SessionRunCoordinator coordinator = new SessionRunCoordinator(properties(2_000));
        SessionRunCoordinator.RunLease first = coordinator.acquire("session-1");
        AtomicReference<SessionRunCoordinator.RunLease> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread waiter = new Thread(() -> {
            result.set(coordinator.acquire("session-1"));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(1_000);
        assertThat(result.get()).isNull();
        assertThat(interrupted.get()).isTrue();
        first.close();
        assertThat(coordinator.activeSessionCount()).isZero();
    }

    @Test
    void recordsWaitTimeoutAndRedisPolicyMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SessionRunCoordinator coordinator = new SessionRunCoordinator(properties(10), registry);
        try (SessionRunCoordinator.RunLease ignored = coordinator.acquire("session-1")) {
            assertThat(coordinator.acquire("session-1")).isNull();
        }
        coordinator.recordRedisFailurePolicy(SessionRunProperties.RedisFailurePolicy.REJECT);
        assertThat(registry.get("chatagent.session.run.wait").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("chatagent.session.run.timeout").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("chatagent.session.run.redis.failure").tag("outcome", "reject").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void repeatedSameSessionHandoffsNeverOverlapOrLeak() throws Exception {
        SessionRunCoordinator coordinator = new SessionRunCoordinator(properties(2_000));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 200; index++) {
                futures.add(executor.submit(() -> {
                    try (SessionRunCoordinator.RunLease ignored = coordinator.acquire("session-1")) {
                        int current = active.incrementAndGet();
                        maximum.accumulateAndGet(current, Math::max);
                        Thread.yield();
                        active.decrementAndGet();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(3, TimeUnit.SECONDS);
            }
            assertThat(maximum).hasValue(1);
            assertThat(coordinator.activeSessionCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void runHeld(SessionRunCoordinator coordinator, String sessionId,
                                CountDownLatch entered, CountDownLatch release,
                                AtomicInteger concurrent, AtomicInteger maximum) {
        try (SessionRunCoordinator.RunLease ignored = coordinator.acquire(sessionId)) {
            int current = concurrent.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            release.await(1, TimeUnit.SECONDS);
            concurrent.decrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static SessionRunProperties properties(long timeoutMs) {
        SessionRunProperties properties = new SessionRunProperties();
        properties.setLocalAcquireTimeoutMs(timeoutMs);
        return properties;
    }
}
