package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ModelHealthStoreTest {

    @Test
    void shouldIgnoreOrdinaryCallbacksWhileHalfOpenProbeOwnsState() throws Exception {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.getHealth().setFailureThreshold(1);
        properties.getHealth().setOpenDurationMs(300_000L);
        ModelHealthStore store = new ModelHealthStore(properties);

        store.markFailure("glm-4");
        setLongField(store, "glm-4", "openUntil", 0L);
        ModelHealthStore.CallPermit probe = store.tryAcquire("glm-4");
        ModelHealthStore.HealthSnapshot before = snapshotFor(store, "glm-4");

        store.markSuccess("glm-4");
        store.markFailure("glm-4");

        ModelHealthStore.HealthSnapshot after = snapshotFor(store, "glm-4");
        assertThat(probe.generation()).isPositive();
        assertThat(after.state()).isEqualTo("HALF_OPEN");
        assertThat(after.probeGeneration()).isEqualTo(before.probeGeneration());
        assertThat(after.halfOpenStartMs()).isEqualTo(before.halfOpenStartMs());
        assertThat(store.tryAcquire("glm-4").allowed()).isFalse();
    }

    @Test
    void shouldIgnoreOrdinaryCallbacksWhileOpen() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.getHealth().setFailureThreshold(1);
        properties.getHealth().setOpenDurationMs(300_000L);
        ModelHealthStore store = new ModelHealthStore(properties);

        store.markFailure("glm-4");
        ModelHealthStore.HealthSnapshot before = snapshotFor(store, "glm-4");

        store.markSuccess("glm-4");
        store.markFailure("glm-4");

        ModelHealthStore.HealthSnapshot after = snapshotFor(store, "glm-4");
        assertThat(after.state()).isEqualTo("OPEN");
        assertThat(after.probeGeneration()).isEqualTo(before.probeGeneration());
        assertThat(after.consecutiveFailures()).isZero();
        assertThat(store.tryAcquire("glm-4").allowed()).isFalse();
    }

    @Test
    void shouldIgnoreStaleHalfOpenFailureAfterNewerProbeClosesCircuit() throws Exception {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.getHealth().setFailureThreshold(1);
        properties.getHealth().setOpenDurationMs(300_000L);
        ModelHealthStore store = new ModelHealthStore(properties);

        store.markFailure("glm-4");
        setLongField(store, "glm-4", "openUntil", 0L);

        ModelHealthStore.CallPermit staleProbe = store.tryAcquire("glm-4");
        assertThat(staleProbe.allowed()).isTrue();
        assertThat(staleProbe.generation()).isPositive();

        setLongField(store, "glm-4", "halfOpenStartMs", System.currentTimeMillis() - 121_000L);
        ModelHealthStore.CallPermit newerProbe = store.tryAcquire("glm-4");
        assertThat(newerProbe.allowed()).isTrue();
        assertThat(newerProbe.generation()).isGreaterThan(staleProbe.generation());

        store.markSuccess("glm-4", newerProbe.generation());
        store.markFailure("glm-4", staleProbe.generation());

        ModelHealthStore.CallPermit afterStaleFailure = store.tryAcquire("glm-4");
        assertThat(afterStaleFailure.allowed()).isTrue();
        assertThat(afterStaleFailure.generation()).isZero();
    }

    @Test
    void shouldIgnoreStaleHalfOpenSuccessAfterNewerProbeReopensCircuit() throws Exception {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.getHealth().setFailureThreshold(1);
        properties.getHealth().setOpenDurationMs(300_000L);
        ModelHealthStore store = new ModelHealthStore(properties);

        store.markFailure("glm-4");
        setLongField(store, "glm-4", "openUntil", 0L);

        ModelHealthStore.CallPermit staleProbe = store.tryAcquire("glm-4");
        setLongField(store, "glm-4", "halfOpenStartMs", System.currentTimeMillis() - 121_000L);
        ModelHealthStore.CallPermit newerProbe = store.tryAcquire("glm-4");

        store.markFailure("glm-4", newerProbe.generation());
        store.markSuccess("glm-4", staleProbe.generation());

        assertThat(store.tryAcquire("glm-4").allowed()).isFalse();
    }

    @Test
    void shouldStayClosedWhenConcurrentStaleFailuresRaceWithNewerSuccessfulProbe() throws Exception {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.getHealth().setFailureThreshold(1);
        properties.getHealth().setOpenDurationMs(300_000L);
        properties.getHealth().setHalfOpenFlightTimeoutMs(5L);

        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            for (int iteration = 0; iteration < 40; iteration++) {
                ModelHealthStore store = new ModelHealthStore(properties);
                ModelHealthStore.CallPermit staleProbe = prepareStaleAndNewerProbe(store);
                long newerGeneration = snapshotFor(store, "glm-4").probeGeneration();

                runConcurrentCallbacks(
                        executor,
                        12,
                        () -> store.markSuccess("glm-4", newerGeneration),
                        () -> store.markFailure("glm-4", staleProbe.generation())
                );

                ModelHealthStore.HealthSnapshot snapshot = snapshotFor(store, "glm-4");
                assertThat(snapshot.state()).isEqualTo("CLOSED");
                assertThat(snapshot.consecutiveFailures()).isZero();
                assertThat(snapshot.probeGeneration()).isEqualTo(newerGeneration);

                ModelHealthStore.CallPermit permitAfterRace = store.tryAcquire("glm-4");
                assertThat(permitAfterRace.allowed()).isTrue();
                assertThat(permitAfterRace.generation()).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldStayOpenWhenConcurrentStaleSuccessesRaceWithNewerFailedProbe() throws Exception {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.getHealth().setFailureThreshold(1);
        properties.getHealth().setOpenDurationMs(300_000L);
        properties.getHealth().setHalfOpenFlightTimeoutMs(5L);

        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            for (int iteration = 0; iteration < 40; iteration++) {
                ModelHealthStore store = new ModelHealthStore(properties);
                ModelHealthStore.CallPermit staleProbe = prepareStaleAndNewerProbe(store);
                long newerGeneration = snapshotFor(store, "glm-4").probeGeneration();

                runConcurrentCallbacks(
                        executor,
                        12,
                        () -> store.markFailure("glm-4", newerGeneration),
                        () -> store.markSuccess("glm-4", staleProbe.generation())
                );

                ModelHealthStore.HealthSnapshot snapshot = snapshotFor(store, "glm-4");
                assertThat(snapshot.state()).isEqualTo("OPEN");
                assertThat(snapshot.reopenInMs()).isPositive();
                assertThat(snapshot.probeGeneration()).isEqualTo(newerGeneration);
                assertThat(store.tryAcquire("glm-4").allowed()).isFalse();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static ModelHealthStore.CallPermit prepareStaleAndNewerProbe(ModelHealthStore store) throws Exception {
        store.markFailure("glm-4");
        setLongField(store, "glm-4", "openUntil", 0L);

        ModelHealthStore.CallPermit staleProbe = store.tryAcquire("glm-4");
        assertThat(staleProbe.allowed()).isTrue();
        setLongField(store, "glm-4", "halfOpenStartMs", System.currentTimeMillis() - 100L);

        ModelHealthStore.CallPermit newerProbe = store.tryAcquire("glm-4");
        assertThat(newerProbe.allowed()).isTrue();
        assertThat(newerProbe.generation()).isGreaterThan(staleProbe.generation());
        return staleProbe;
    }

    private static void runConcurrentCallbacks(ExecutorService executor,
                                               int staleCallbackCount,
                                               Runnable latestCallback,
                                               Runnable staleCallback) throws Exception {
        CountDownLatch ready = new CountDownLatch(staleCallbackCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> {
            ready.countDown();
            await(start);
            latestCallback.run();
            return null;
        }));

        for (int i = 0; i < staleCallbackCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                staleCallback.run();
                return null;
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent callback start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent callback start", e);
        }
    }

    private static void setLongField(ModelHealthStore store, String modelId, String fieldName, long value) throws Exception {
        Object health = healthFor(store, modelId);
        Field field = health.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(health, value);
    }

    private static Object healthFor(ModelHealthStore store, String modelId) throws Exception {
        Field field = ModelHealthStore.class.getDeclaredField("healthById");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> healthById = (Map<String, Object>) field.get(store);
        return healthById.get(modelId);
    }

    private static ModelHealthStore.HealthSnapshot snapshotFor(ModelHealthStore store, String modelId) {
        return store.snapshot().get(modelId);
    }
}
