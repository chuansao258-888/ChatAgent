package com.yulong.chatagent.eval;

import com.yulong.chatagent.rag.retrieve.RerankerCircuitBreaker;
import com.yulong.chatagent.rag.retrieve.RerankerProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark test for RerankerCircuitBreaker recovery behavior.
 * Measures timing and correctness of state transitions under controlled conditions.
 *
 * <p>No external infrastructure needed — pure unit test.
 * <br>Run: mvn test -pl bootstrap -Dgroups=eval-reranker -Dtest=CircuitBreakerRecoveryBenchmarkTest
 */
@Tag("eval-reranker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CircuitBreakerRecoveryBenchmarkTest {

    private static final double NANOS_TO_MS = 1_000_000.0;

    @Test
    void benchmarkFullRecoveryCycle() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();

        // Benchmark with different openStateMs values
        int[] openStateMsValues = {100, 500, 1000};
        int iterations = 10;

        for (int openStateMs : openStateMsValues) {
            List<Long> tripLatencies = new ArrayList<>();
            List<Long> halfOpenLatencies = new ArrayList<>();
            List<Long> recoveryLatencies = new ArrayList<>();

            for (int i = 0; i < iterations; i++) {
                RerankerProperties props = createProperties(openStateMs);
                RerankerCircuitBreaker cb = new RerankerCircuitBreaker("bench-provider", props);

                // Phase 1: Trip the breaker (CLOSED → OPEN)
                long tripStart = System.nanoTime();
                for (int f = 0; f < props.getMinimumRequestVolume(); f++) {
                    cb.recordFailure();
                }
                long tripEnd = System.nanoTime();
                assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.OPEN);
                tripLatencies.add(tripEnd - tripStart);

                // Phase 2: Wait for HALF_OPEN transition
                Thread.sleep(openStateMs + 50);
                long halfOpenStart = System.nanoTime();
                boolean allowed = cb.allowRequest();
                long halfOpenEnd = System.nanoTime();
                assertThat(allowed).isTrue();
                assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.HALF_OPEN);
                halfOpenLatencies.add(halfOpenEnd - halfOpenStart);

                // Phase 3: Recover (HALF_OPEN → CLOSED)
                long recoverStart = System.nanoTime();
                cb.recordSuccess();
                long recoverEnd = System.nanoTime();
                assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.CLOSED);
                recoveryLatencies.add(recoverEnd - recoverStart);

                // Verify window reset
                assertThat(cb.allowRequest()).isTrue();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("openStateMs", openStateMs);
            result.put("iterations", iterations);
            result.put("tripLatencyP50Ms", percentile(tripLatencies, 50));
            result.put("tripLatencyP99Ms", percentile(tripLatencies, 99));
            result.put("halfOpenTransitionLatencyP50Ms", percentile(halfOpenLatencies, 50));
            result.put("recoveryLatencyP50Ms", percentile(recoveryLatencies, 50));
            result.put("recoveryLatencyP99Ms", percentile(recoveryLatencies, 99));
            results.add(result);
        }

        // Print report
        System.out.println("\n=== Circuit Breaker Recovery Benchmark ===");
        System.out.printf("%-15s %-12s %-12s %-15s %-12s %-12s%n",
                "openStateMs", "tripP50(ms)", "tripP99(ms)", "halfOpenP50(ms)", "recovP50(ms)", "recovP99(ms)");
        for (Map<String, Object> r : results) {
            System.out.printf("%-15s %-12s %-12s %-15s %-12s %-12s%n",
                    r.get("openStateMs"),
                    r.get("tripLatencyP50Ms"),
                    r.get("tripLatencyP99Ms"),
                    r.get("halfOpenTransitionLatencyP50Ms"),
                    r.get("recoveryLatencyP50Ms"),
                    r.get("recoveryLatencyP99Ms"));
        }

        // Assertions: all latencies should be under reasonable thresholds
        for (Map<String, Object> r : results) {
            assertThat((double) r.get("tripLatencyP50Ms"))
                    .as("Trip latency P50 for openStateMs=" + r.get("openStateMs"))
                    .isLessThan(5.0);
            assertThat((double) r.get("recoveryLatencyP50Ms"))
                    .as("Recovery latency P50 for openStateMs=" + r.get("openStateMs"))
                    .isLessThan(1.0);
        }
    }

    @Test
    void benchmarkReTripFromHalfOpen() throws Exception {
        List<Long> reTripLatencies = new ArrayList<>();
        int iterations = 20;
        RerankerProperties props = createProperties(100);

        for (int i = 0; i < iterations; i++) {
            RerankerCircuitBreaker cb = new RerankerCircuitBreaker("bench-provider", props);

            // Trip → wait → enter HALF_OPEN
            tripCircuit(cb, props);
            Thread.sleep(150);
            cb.allowRequest();
            assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.HALF_OPEN);

            // Re-trip with a single failure
            long start = System.nanoTime();
            cb.recordFailure();
            long end = System.nanoTime();
            assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.OPEN);
            reTripLatencies.add(end - start);
        }

        double p50 = percentile(reTripLatencies, 50);
        double p99 = percentile(reTripLatencies, 99);

        System.out.println("\n=== Re-trip from HALF_OPEN Benchmark ===");
        System.out.println("Iterations: " + iterations);
        System.out.printf("Re-trip latency P50: %.4f ms%n", p50);
        System.out.printf("Re-trip latency P99: %.4f ms%n", p99);

        assertThat(p50).as("HALF_OPEN re-trip should be sub-ms").isLessThan(1.0);
    }

    @Test
    void benchmarkSlidingWindowDilution() {
        RerankerProperties props = createProperties(30000);
        props.setMinimumRequestVolume(10);
        props.setFailureThreshold(5);
        props.setFailureRateThresholdPercent(50);
        RerankerCircuitBreaker cb = new RerankerCircuitBreaker("bench-provider", props);

        List<Long> checkLatencies = new ArrayList<>();

        // Record 4 failures (below threshold)
        for (int i = 0; i < 4; i++) {
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.CLOSED);

        // Dilute with 6 successes (total 10 requests, 40% failure rate < 50% threshold)
        for (int i = 0; i < 6; i++) {
            long start = System.nanoTime();
            cb.recordSuccess();
            checkLatencies.add(System.nanoTime() - start);
        }
        assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.CLOSED);

        // Now add failures to push over threshold
        long tripStart = System.nanoTime();
        cb.recordFailure(); // 5th failure, 5/11 = 45.4%
        assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.CLOSED);
        cb.recordFailure(); // 6th failure, 6/12 = 50%
        assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.OPEN);
        long tripEnd = System.nanoTime();

        System.out.println("\n=== Sliding Window Dilution Benchmark ===");
        System.out.printf("Window check latency P50: %.4f ms%n", percentile(checkLatencies, 50));
        System.out.printf("Final trip latency: %.4f ms%n", nanosToMsDouble(tripEnd - tripStart));
        System.out.println("Final state: " + cb.getState());
    }

    @Test
    void benchmarkConcurrentHalfOpenContention() throws Exception {
        RerankerProperties props = createProperties(100);
        props.setHalfOpenProbeCount(1);
        int threadCount = 8;
        int iterations = 10;
        List<Double> allowedRatios = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            RerankerCircuitBreaker cb = new RerankerCircuitBreaker("bench-provider", props);
            tripCircuit(cb, props);
            Thread.sleep(150);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger allowedCount = new AtomicInteger();

            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threadCount; t++) {
                    futures.add(executor.submit(() -> {
                        try {
                            startLatch.await();
                            if (cb.allowRequest()) {
                                allowedCount.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                }

                startLatch.countDown();
                for (Future<?> f : futures) {
                    f.get(5, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(allowedCount.get()).isEqualTo(1);
            allowedRatios.add(allowedCount.get() / (double) threadCount);
        }

        System.out.println("\n=== Concurrent HALF_OPEN Contention Benchmark ===");
        System.out.println("Threads: " + threadCount + ", Iterations: " + iterations);
        System.out.printf("Allowed ratio (should always be 1/%d = %.3f): avg=%.3f%n",
                threadCount, 1.0 / threadCount,
                allowedRatios.stream().mapToDouble(d -> d).average().orElse(0));

        // Verify exactly 1 probe always allowed
        assertThat(allowedRatios).allSatisfy(r ->
                assertThat(r).as("Exactly 1 probe should be allowed per HALF_OPEN cycle").isEqualTo(1.0 / threadCount));
    }

    @Test
    void benchmarkRepeatedRecoveryCycles() throws Exception {
        RerankerProperties props = createProperties(80);
        RerankerCircuitBreaker cb = new RerankerCircuitBreaker("bench-provider", props);
        int cycles = 5;
        List<Long> fullCycleLatencies = new ArrayList<>();

        for (int c = 0; c < cycles; c++) {
            long cycleStart = System.nanoTime();

            // Trip
            tripCircuit(cb, props);
            assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.OPEN);

            // Wait and recover
            Thread.sleep(100);
            assertThat(cb.allowRequest()).isTrue();
            cb.recordSuccess();
            assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.CLOSED);

            long cycleEnd = System.nanoTime();
            fullCycleLatencies.add(cycleEnd - cycleStart);

            // Verify window is clean after recovery
            assertThat(cb.allowRequest()).isTrue();
        }

        System.out.println("\n=== Repeated Recovery Cycles Benchmark ===");
        System.out.println("Cycles: " + cycles);
        System.out.printf("Full cycle latency P50: %.2f ms%n", percentile(fullCycleLatencies, 50));
        System.out.printf("Full cycle latency P99: %.2f ms%n", percentile(fullCycleLatencies, 99));
        System.out.printf("Full cycle latency max: %.2f ms%n", max(fullCycleLatencies));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private RerankerProperties createProperties(int openStateMs) {
        RerankerProperties props = new RerankerProperties();
        props.setFailureThreshold(3);
        props.setMinimumRequestVolume(5);
        props.setFailureRateThresholdPercent(50);
        props.setOpenStateMs(openStateMs);
        props.setHalfOpenProbeCount(1);
        return props;
    }

    private void tripCircuit(RerankerCircuitBreaker cb, RerankerProperties props) {
        for (int i = 0; i < props.getMinimumRequestVolume(); i++) {
            cb.recordFailure();
        }
    }

    /** Convert nanoseconds to milliseconds (double precision). */
    private static double nanosToMsDouble(long nanos) {
        return nanos / NANOS_TO_MS;
    }

    private static double percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0.0;
        List<Long> copy = new ArrayList<>(sorted);
        copy.sort(Long::compareTo);
        int index = (int) Math.ceil(p / 100.0 * copy.size()) - 1;
        return copy.get(Math.max(0, index)) / NANOS_TO_MS;
    }

    private static double max(List<Long> values) {
        return values.stream().mapToLong(l -> l).max().orElse(0) / NANOS_TO_MS;
    }
}
