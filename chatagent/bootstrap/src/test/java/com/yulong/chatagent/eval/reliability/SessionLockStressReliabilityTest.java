package com.yulong.chatagent.eval.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.eval.support.ReportArtifactWriter;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.MqSessionExecLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2d: Session execution lock stress evaluation.
 *
 * Uses the real {@link DistributedLockManager#acquireSessionExecLock(String, String)}
 * algorithm with a concurrent in-memory mock of Redis SETNX/GET. This keeps the test
 * deterministic and fast while exercising actual lock classification under contention.
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=reliability,stress \
 *      -Dtest=SessionLockStressReliabilityTest
 */
@Tag("reliability")
@Tag("stress")
class SessionLockStressReliabilityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void evaluateSessionLockContention() throws Exception {
        List<StressScenarioResult> results = List.of(
                runSingleHotSessionContention(),
                runMultiSessionIsolation(),
                runSequentialWaveRecovery()
        );

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("scenarios", results.size());
        overall.put("totalAttempts", results.stream().mapToInt(StressScenarioResult::attempts).sum());
        overall.put("passedScenarios", results.stream().filter(StressScenarioResult::passed).count());
        overall.put("passRate", round(rate(results.stream().filter(StressScenarioResult::passed).count(), results.size())));
        overall.put("totalAcquired", results.stream().mapToInt(StressScenarioResult::acquired).sum());
        overall.put("totalWaitRequired", results.stream().mapToInt(StressScenarioResult::waitRequired).sum());
        overall.put("totalErrors", results.stream().mapToInt(StressScenarioResult::errors).sum());
        overall.put("maxP95LatencyMs", results.stream()
                .mapToDouble(StressScenarioResult::p95LatencyMs)
                .max()
                .orElse(0.0));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "session-lock-stress-eval");
        report.put("mode", "real-lock-manager-in-memory-redis");
        report.put("overall", overall);
        report.put("scenarios", results);

        Path reportPath = ReportArtifactWriter.writeReport("session-lock-stress-reliability", report);
        System.out.println("=== Session Lock Stress Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Overall:");
        System.out.println(OBJECT_MAPPER.writeValueAsString(overall));

        List<StressScenarioResult> failed = results.stream()
                .filter(result -> !result.passed())
                .toList();
        assertThat(failed).as("session lock stress failures").isEmpty();
    }

    private StressScenarioResult runSingleHotSessionContention() throws Exception {
        int threads = Integer.getInteger("eval.sessionLock.hotThreads", 64);
        InMemoryRedisHarness harness = new InMemoryRedisHarness();
        List<AttemptResult> attempts = race(threads, index ->
                harness.lockManager.acquireSessionExecLock("session-hot", "owner-" + index));

        int acquired = count(attempts, MqTaskLockAcquireOutcome.ACQUIRED);
        int waitRequired = count(attempts, MqTaskLockAcquireOutcome.WAIT_REQUIRED);
        int errors = errors(attempts);
        boolean passed = acquired == 1 && waitRequired == threads - 1 && errors == 0 && harness.keyCount() == 1;

        return summarize(
                "session-lock-001",
                "single-hot-session",
                "64 concurrent workers contend for one session execution lock",
                "exactly one ACQUIRED and all other workers WAIT_REQUIRED",
                attempts,
                acquired,
                waitRequired,
                errors,
                passed,
                Map.of("redisKeys", harness.keyCount())
        );
    }

    private StressScenarioResult runMultiSessionIsolation() throws Exception {
        int threads = Integer.getInteger("eval.sessionLock.multiThreads", 64);
        int sessions = Integer.getInteger("eval.sessionLock.multiSessions", 8);
        InMemoryRedisHarness harness = new InMemoryRedisHarness();
        List<AttemptResult> attempts = race(threads, index -> {
            String sessionId = "session-" + (index % sessions);
            return harness.lockManager.acquireSessionExecLock(sessionId, "owner-" + index);
        });

        int acquired = count(attempts, MqTaskLockAcquireOutcome.ACQUIRED);
        int waitRequired = count(attempts, MqTaskLockAcquireOutcome.WAIT_REQUIRED);
        int errors = errors(attempts);
        long acquiredSessions = attempts.stream()
                .filter(attempt -> attempt.outcome() == MqTaskLockAcquireOutcome.ACQUIRED)
                .map(AttemptResult::sessionId)
                .distinct()
                .count();
        boolean passed = acquired == sessions
                && acquiredSessions == sessions
                && waitRequired == threads - sessions
                && errors == 0
                && harness.keyCount() == sessions;

        return summarize(
                "session-lock-002",
                "multi-session-isolation",
                "64 workers contend across 8 independent sessions",
                "one ACQUIRED per session and contention isolated by session key",
                attempts,
                acquired,
                waitRequired,
                errors,
                passed,
                Map.of("configuredSessions", sessions, "acquiredSessions", acquiredSessions, "redisKeys", harness.keyCount())
        );
    }

    private StressScenarioResult runSequentialWaveRecovery() throws Exception {
        int threadsPerWave = Integer.getInteger("eval.sessionLock.waveThreads", 32);
        InMemoryRedisHarness harness = new InMemoryRedisHarness();
        List<AttemptResult> waveOne = race(threadsPerWave, index ->
                harness.lockManager.acquireSessionExecLock("session-wave", "wave-1-owner-" + index));
        harness.clear();
        List<AttemptResult> waveTwo = race(threadsPerWave, index ->
                harness.lockManager.acquireSessionExecLock("session-wave", "wave-2-owner-" + index));

        List<AttemptResult> attempts = new ArrayList<>();
        attempts.addAll(waveOne);
        attempts.addAll(waveTwo);

        int acquired = count(attempts, MqTaskLockAcquireOutcome.ACQUIRED);
        int waitRequired = count(attempts, MqTaskLockAcquireOutcome.WAIT_REQUIRED);
        int errors = errors(attempts);
        boolean passed = count(waveOne, MqTaskLockAcquireOutcome.ACQUIRED) == 1
                && count(waveTwo, MqTaskLockAcquireOutcome.ACQUIRED) == 1
                && acquired == 2
                && waitRequired == threadsPerWave * 2 - 2
                && errors == 0
                && harness.keyCount() == 1;

        return summarize(
                "session-lock-003",
                "sequential-wave-recovery",
                "two contention waves after clearing the previous session lock",
                "each wave admits one owner and blocks the remaining contenders",
                attempts,
                acquired,
                waitRequired,
                errors,
                passed,
                Map.of("waves", 2, "threadsPerWave", threadsPerWave, "redisKeysAfterWaveTwo", harness.keyCount())
        );
    }

    private List<AttemptResult> race(int threads, LockAttempt attempt) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttemptResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                String sessionId = inferSessionId(index, threads);
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    return AttemptResult.error(index, sessionId, "Timed out waiting for race start");
                }
                long startNs = System.nanoTime();
                try {
                    MqSessionExecLockAcquisition acquisition = attempt.acquire(index);
                    long latencyNs = System.nanoTime() - startNs;
                    String acquiredSessionId = acquisition.lease() == null
                            ? sessionId
                            : acquisition.lease().sessionId();
                    return new AttemptResult(index, acquiredSessionId, acquisition.outcome(), latencyNs, null);
                } catch (Exception e) {
                    long latencyNs = System.nanoTime() - startNs;
                    return new AttemptResult(index, sessionId, null, latencyNs, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<AttemptResult> results = new ArrayList<>();
        for (Future<AttemptResult> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    private String inferSessionId(int index, int threadCount) {
        return "attempt-" + index + "-of-" + threadCount;
    }

    private StressScenarioResult summarize(String id,
                                           String category,
                                           String loadShape,
                                           String expectedInvariant,
                                           List<AttemptResult> attempts,
                                           int acquired,
                                           int waitRequired,
                                           int errors,
                                           boolean passed,
                                           Map<String, Object> details) {
        List<Long> latencies = attempts.stream()
                .map(AttemptResult::latencyNs)
                .sorted()
                .toList();
        return new StressScenarioResult(
                id,
                category,
                loadShape,
                expectedInvariant,
                attempts.size(),
                acquired,
                waitRequired,
                errors,
                round(nsToMs(avg(latencies))),
                round(nsToMs(percentile(latencies, 0.95))),
                round(nsToMs(latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1))),
                passed,
                details,
                attempts.stream()
                        .filter(attempt -> attempt.error() != null)
                        .map(AttemptResult::error)
                        .toList()
        );
    }

    private int count(List<AttemptResult> attempts, MqTaskLockAcquireOutcome outcome) {
        return (int) attempts.stream()
                .filter(attempt -> attempt.outcome() == outcome)
                .count();
    }

    private int errors(List<AttemptResult> attempts) {
        return (int) attempts.stream()
                .filter(attempt -> attempt.error() != null)
                .count();
    }

    private long avg(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return (long) values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private double nsToMs(long ns) {
        return ns / 1_000_000.0;
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    @FunctionalInterface
    private interface LockAttempt {
        MqSessionExecLockAcquisition acquire(int index);
    }

    private record StressScenarioResult(
            String id,
            String category,
            String loadShape,
            String expectedInvariant,
            int attempts,
            int acquired,
            int waitRequired,
            int errors,
            double avgLatencyMs,
            double p95LatencyMs,
            double maxLatencyMs,
            boolean passed,
            Map<String, Object> details,
            List<String> errorMessages
    ) {
    }

    private record AttemptResult(
            int index,
            String sessionId,
            MqTaskLockAcquireOutcome outcome,
            long latencyNs,
            String error
    ) {
        private static AttemptResult error(int index, String sessionId, String error) {
            return new AttemptResult(index, sessionId, null, 0L, error);
        }
    }

    private static final class InMemoryRedisHarness {
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final DistributedLockManager lockManager;

        @SuppressWarnings("unchecked")
        private InMemoryRedisHarness() {
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        String value = invocation.getArgument(1);
                        return store.putIfAbsent(key, value) == null;
                    });
            when(valueOperations.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
            lockManager = new DistributedLockManager(redisTemplate, new ObjectMapper(), new ChatAgentMqProperties());
        }

        private void clear() {
            store.clear();
        }

        private int keyCount() {
            return store.size();
        }
    }
}
