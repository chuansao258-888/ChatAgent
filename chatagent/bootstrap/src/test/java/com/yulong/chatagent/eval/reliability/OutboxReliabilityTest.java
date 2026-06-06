package com.yulong.chatagent.eval.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.eval.support.ReportArtifactWriter;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxPollingPublisher;
import com.yulong.chatagent.mq.outbox.OutboxRecordService;
import com.yulong.chatagent.mq.outbox.OutboxRepository;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3b: transactional outbox reliability evaluation.
 *
 * This harness drives the real OutboxRecordService + OutboxPollingPublisher
 * state machine with a thread-safe in-memory repository and fake publisher.
 * It is not a broker/container test; it gives fast regression coverage for
 * delivery, retry, terminal failure, stale-claim recovery, and multi-publisher
 * duplicate suppression before the slower live RabbitMQ/PostgreSQL benchmark.
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=reliability \
 *      -Dtest=OutboxReliabilityTest
 */
@Tag("reliability")
class OutboxReliabilityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void evaluateOutboxReliabilityPaths() throws Exception {
        List<ScenarioResult> results = List.of(
                runScenario(
                        "outbox-001",
                        "at-least-once",
                        "1000 pending rows, one poller, broker always confirms",
                        "all rows reach SENT, no failed rows, one successful publish per row",
                        harness -> {
                            harness.seedPendingRows(1000);
                            harness.drainUntilTerminal(1000, 30, Duration.ZERO);
                            return harness.result(1000);
                        }
                ),
                runScenario(
                        "outbox-002",
                        "retry-timing",
                        "30 pending rows, every row fails twice before confirm",
                        "rows remain retryable, then reach SENT before max attempts",
                        harness -> {
                            harness.publisher.failFirstAttemptsForAll(2);
                            harness.seedPendingRows(30);
                            harness.drainUntilTerminal(30, 120, Duration.ofMillis(2));
                            return harness.result(30);
                        }
                ),
                runScenario(
                        "outbox-003",
                        "terminal-failure",
                        "12 pending rows, broker never confirms",
                        "rows move to FAILED after max publish attempts",
                        harness -> {
                            harness.publisher.failAlways();
                            harness.seedPendingRows(12);
                            harness.drainUntilTerminal(12, 120, Duration.ofMillis(2));
                            return harness.result(12);
                        }
                ),
                runScenario(
                        "outbox-004",
                        "multi-instance",
                        "1000 pending rows, four concurrent pollers share one repository",
                        "all rows reach SENT with no duplicate successful publishes",
                        harness -> {
                            harness.seedPendingRows(1000);
                            harness.runConcurrentPollers(4, 1000, Duration.ofSeconds(10));
                            return harness.result(1000);
                        }
                ),
                runScenario(
                        "outbox-005",
                        "stale-claim-recovery",
                        "25 stale CLAIMED rows older than claim timeout",
                        "stale rows are reclaimed and published to SENT",
                        harness -> {
                            harness.seedClaimedRows(25, LocalDateTime.now().minusMinutes(5));
                            harness.drainUntilTerminal(25, 20, Duration.ZERO);
                            return harness.result(25);
                        }
                ),
                runScenario(
                        "outbox-006",
                        "cleanup",
                        "20 old SENT rows plus 5 recent SENT rows",
                        "cleanup removes only old sent rows beyond retention cutoff",
                        harness -> {
                            harness.seedSentRows(20, LocalDateTime.now().minusDays(10));
                            harness.seedSentRows(5, LocalDateTime.now());
                            int deleted = harness.recordService.cleanupOlderSentRows(LocalDateTime.now().minusDays(7));
                            ScenarioStats stats = harness.result(25);
                            return stats.withCleanupDeleted(deleted);
                        }
                )
        );

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("totalScenarios", results.size());
        overall.put("passedScenarios", results.stream().filter(ScenarioResult::passed).count());
        overall.put("passRate", round(rate(results.stream().filter(ScenarioResult::passed).count(), results.size())));
        overall.put("totalSeededRows", results.stream().mapToInt(result -> result.stats().seededRows()).sum());
        overall.put("totalPublishAttempts", results.stream().mapToInt(result -> result.stats().publishAttempts()).sum());
        overall.put("totalSuccessfulPublishes", results.stream().mapToInt(result -> result.stats().successfulPublishes()).sum());
        overall.put("totalDuplicateSuccessfulPublishes", results.stream()
                .mapToInt(result -> result.stats().duplicateSuccessfulPublishes())
                .sum());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "mq-outbox-reliability-eval");
        report.put("mode", "in-memory-outbox-real-poller");
        report.put("overall", overall);
        report.put("byCategory", byCategory(results));
        report.put("scenarios", results);

        Path reportPath = ReportArtifactWriter.writeReport("mq-outbox-reliability", report);
        System.out.println("=== MQ Outbox Reliability Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Overall:");
        System.out.println(OBJECT_MAPPER.writeValueAsString(overall));

        List<ScenarioResult> failed = results.stream()
                .filter(result -> !result.passed())
                .toList();
        assertThat(failed).as("outbox reliability scenario failures").isEmpty();
    }

    private ScenarioResult runScenario(String id,
                                       String category,
                                       String workload,
                                       String expectedInvariant,
                                       ScenarioBody body) {
        ScenarioHarness harness = new ScenarioHarness();
        long started = System.nanoTime();
        try {
            ScenarioStats stats = body.run(harness)
                    .withElapsedMs(elapsedMs(started));
            boolean passed = invariantPassed(id, stats);
            return new ScenarioResult(id, category, workload, expectedInvariant, stats, passed, null);
        } catch (Throwable t) {
            ScenarioStats stats = harness.result(harness.seededRows()).withElapsedMs(elapsedMs(started));
            return new ScenarioResult(
                    id,
                    category,
                    workload,
                    expectedInvariant,
                    stats,
                    false,
                    t.getClass().getSimpleName() + ": " + t.getMessage()
            );
        }
    }

    private boolean invariantPassed(String id, ScenarioStats stats) {
        return switch (id) {
            case "outbox-001" -> stats.sentRows() == 1000
                    && stats.failedRows() == 0
                    && stats.duplicateSuccessfulPublishes() == 0
                    && stats.successfulPublishes() == 1000;
            case "outbox-002" -> stats.sentRows() == 30
                    && stats.failedRows() == 0
                    && stats.maxRetryCount() == 2
                    && stats.successfulPublishes() == 30;
            case "outbox-003" -> stats.failedRows() == 12
                    && stats.sentRows() == 0
                    && stats.maxRetryCount() == 3
                    && stats.successfulPublishes() == 0;
            case "outbox-004" -> stats.sentRows() == 1000
                    && stats.failedRows() == 0
                    && stats.duplicateSuccessfulPublishes() == 0
                    && stats.successfulPublishes() == 1000;
            case "outbox-005" -> stats.sentRows() == 25
                    && stats.failedRows() == 0
                    && stats.successfulPublishes() == 25;
            case "outbox-006" -> stats.cleanupDeleted() == 20
                    && stats.sentRows() == 5;
            default -> false;
        };
    }

    private Map<String, CategoryMetrics> byCategory(List<ScenarioResult> results) {
        Map<String, List<ScenarioResult>> grouped = new LinkedHashMap<>();
        for (ScenarioResult result : results) {
            grouped.computeIfAbsent(result.category(), ignored -> new ArrayList<>()).add(result);
        }
        Map<String, CategoryMetrics> metrics = new LinkedHashMap<>();
        grouped.forEach((category, categoryResults) -> {
            long passed = categoryResults.stream().filter(ScenarioResult::passed).count();
            metrics.put(category, new CategoryMetrics(
                    categoryResults.size(),
                    passed,
                    round(rate(passed, categoryResults.size()))
            ));
        });
        return metrics;
    }

    private static ChatAgentMqProperties properties() {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        properties.getOutbox().setBatchSize(100);
        properties.getOutbox().setMaxPublishAttempts(3);
        properties.getOutbox().setPublishRetryDelayMs(1);
        properties.getOutbox().setClaimTimeoutMs(60_000);
        return properties;
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    @FunctionalInterface
    private interface ScenarioBody {
        ScenarioStats run(ScenarioHarness harness) throws Exception;
    }

    private record ScenarioResult(
            String id,
            String category,
            String workload,
            String expectedInvariant,
            ScenarioStats stats,
            boolean passed,
            String error
    ) {
    }

    private record CategoryMetrics(int scenarios, long passed, double passRate) {
    }

    private record ScenarioStats(
            int seededRows,
            int sentRows,
            int pendingRows,
            int claimedRows,
            int failedRows,
            int discardedRows,
            int publishAttempts,
            int successfulPublishes,
            int duplicateSuccessfulPublishes,
            int maxRetryCount,
            int cleanupDeleted,
            long elapsedMs
    ) {
        private ScenarioStats withElapsedMs(long elapsedMs) {
            return new ScenarioStats(
                    seededRows,
                    sentRows,
                    pendingRows,
                    claimedRows,
                    failedRows,
                    discardedRows,
                    publishAttempts,
                    successfulPublishes,
                    duplicateSuccessfulPublishes,
                    maxRetryCount,
                    cleanupDeleted,
                    elapsedMs
            );
        }

        private ScenarioStats withCleanupDeleted(int cleanupDeleted) {
            return new ScenarioStats(
                    seededRows,
                    sentRows,
                    pendingRows,
                    claimedRows,
                    failedRows,
                    discardedRows,
                    publishAttempts,
                    successfulPublishes,
                    duplicateSuccessfulPublishes,
                    maxRetryCount,
                    cleanupDeleted,
                    elapsedMs
            );
        }
    }

    private static final class ScenarioHarness {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ChatAgentMqProperties properties = properties();
        private final InMemoryOutboxRepository repository = new InMemoryOutboxRepository();
        private final OutboxRecordService recordService = new OutboxRecordService(repository, properties);
        private final CapturingPublisher publisher = new CapturingPublisher();
        private final OutboxPollingPublisher poller = new OutboxPollingPublisher(
                recordService,
                publisher,
                objectMapper,
                properties
        );

        private void seedPendingRows(int count) throws Exception {
            for (int i = 0; i < count; i++) {
                repository.insert(newOutbox("PENDING", LocalDateTime.now(), 0));
            }
        }

        private void seedClaimedRows(int count, LocalDateTime claimedAt) throws Exception {
            for (int i = 0; i < count; i++) {
                MqOutbox outbox = newOutbox("CLAIMED", LocalDateTime.now(), 1);
                outbox.setClaimedAt(claimedAt);
                outbox.setClaimedBy("dead-worker");
                repository.insert(outbox);
            }
        }

        private void seedSentRows(int count, LocalDateTime createdAt) throws Exception {
            for (int i = 0; i < count; i++) {
                repository.insert(newOutbox("SENT", createdAt, 1));
            }
        }

        private int seededRows() {
            return repository.size();
        }

        private void drainUntilTerminal(int expectedTerminalRows, int maxIterations, Duration pause) throws Exception {
            for (int i = 0; i < maxIterations; i++) {
                poller.publishDueRows();
                if (repository.terminalRows() >= expectedTerminalRows) {
                    return;
                }
                if (!pause.isZero()) {
                    Thread.sleep(Math.max(1L, pause.toMillis()));
                }
            }
        }

        private void runConcurrentPollers(int workers, int expectedTerminalRows, Duration timeout) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            AtomicBoolean stop = new AtomicBoolean(false);
            List<OutboxPollingPublisher> pollers = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                pollers.add(new OutboxPollingPublisher(
                        new OutboxRecordService(repository, properties),
                        publisher,
                        objectMapper,
                        properties
                ));
            }
            for (OutboxPollingPublisher concurrentPoller : pollers) {
                executor.submit(() -> {
                    while (!stop.get() && repository.terminalRows() < expectedTerminalRows) {
                        concurrentPoller.publishDueRows();
                    }
                });
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && repository.terminalRows() < expectedTerminalRows) {
                Thread.sleep(1L);
            }
            stop.set(true);
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }

        private ScenarioStats result(int expectedSeededRows) {
            return new ScenarioStats(
                    expectedSeededRows,
                    repository.countByStatus("SENT"),
                    repository.countByStatus("PENDING"),
                    repository.countByStatus("CLAIMED"),
                    repository.countByStatus("FAILED"),
                    repository.countByStatus("DISCARDED"),
                    publisher.publishAttempts(),
                    publisher.successfulPublishes(),
                    publisher.duplicateSuccessfulPublishes(),
                    repository.maxRetryCount(),
                    0,
                    0
            );
        }

        private MqOutbox newOutbox(String status, LocalDateTime createdAt, int retryCount) throws Exception {
            String id = UUID.randomUUID().toString();
            MqMessageIdentity identity = MqMessageIdentity.initial(
                    "agent.run",
                    "idem-" + id,
                    "trace-" + id,
                    "session-" + id,
                    "chat.direct",
                    "agent.run"
            );
            return MqOutbox.builder()
                    .id(id)
                    .eventType("agent.run")
                    .exchange("chat.direct")
                    .routingKey("agent.run")
                    .payload("{\"sessionId\":\"session-" + id + "\",\"turnId\":\"turn-" + id + "\"}")
                    .headers(objectMapper.writeValueAsString(MqMessageHeaders.toMap(identity)))
                    .status(status)
                    .nextRetryAt(createdAt)
                    .retryCount(retryCount)
                    .version(0)
                    .createdAt(createdAt)
                    .build();
        }
    }

    private static final class CapturingPublisher extends RabbitMqMessagePublisher {

        private final Map<String, AtomicInteger> attemptsById = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> successesById = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> failuresRemainingById = new ConcurrentHashMap<>();
        private volatile int failFirstAttemptsForAll;
        private volatile boolean failAlways;

        private CapturingPublisher() {
            super(null, new ChatAgentMqProperties());
        }

        @Override
        public void publish(String exchange, String routingKey, Message message, String correlationId) {
            attemptsById.computeIfAbsent(correlationId, ignored -> new AtomicInteger()).incrementAndGet();
            if (failAlways) {
                throw new AmqpException("planned permanent failure") {
                };
            }
            AtomicInteger remaining = failuresRemainingById.computeIfAbsent(
                    correlationId,
                    ignored -> new AtomicInteger(failFirstAttemptsForAll)
            );
            if (remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new AmqpException("planned transient failure") {
                };
            }
            successesById.computeIfAbsent(correlationId, ignored -> new AtomicInteger()).incrementAndGet();
        }

        private void failFirstAttemptsForAll(int attempts) {
            this.failFirstAttemptsForAll = attempts;
        }

        private void failAlways() {
            this.failAlways = true;
        }

        private int publishAttempts() {
            return attemptsById.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        private int successfulPublishes() {
            return successesById.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        private int duplicateSuccessfulPublishes() {
            return successesById.values().stream()
                    .mapToInt(counter -> Math.max(0, counter.get() - 1))
                    .sum();
        }
    }

    private static final class InMemoryOutboxRepository implements OutboxRepository {

        private final Map<String, MqOutbox> rows = new LinkedHashMap<>();

        @Override
        public synchronized int insert(MqOutbox outbox) {
            rows.put(outbox.getId(), copy(outbox));
            return 1;
        }

        @Override
        public synchronized MqOutbox findById(String id) {
            return copy(rows.get(id));
        }

        @Override
        public synchronized List<MqOutbox> selectClaimableBatch(int limit,
                                                                LocalDateTime now,
                                                                LocalDateTime staleClaimBefore,
                                                                int maxAttempts) {
            return rows.values().stream()
                    .filter(row -> row.getRetryCount() < maxAttempts)
                    .filter(row -> isClaimable(row, now, staleClaimBefore))
                    .sorted(Comparator
                            .comparing(this::claimableAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(MqOutbox::getId))
                    .limit(limit)
                    .map(InMemoryOutboxRepository::copy)
                    .toList();
        }

        @Override
        public synchronized boolean markClaimed(String id,
                                                String claimedBy,
                                                LocalDateTime claimedAt,
                                                int expectedVersion) {
            MqOutbox row = rows.get(id);
            if (row == null || row.getVersion() != expectedVersion) {
                return false;
            }
            row.setStatus("CLAIMED");
            row.setClaimedBy(claimedBy);
            row.setClaimedAt(claimedAt);
            row.setVersion(row.getVersion() + 1);
            return true;
        }

        @Override
        public synchronized boolean markSent(String id, int expectedVersion) {
            MqOutbox row = rows.get(id);
            if (row == null || row.getVersion() != expectedVersion) {
                return false;
            }
            row.setStatus("SENT");
            row.setClaimedAt(null);
            row.setClaimedBy(null);
            row.setLastError(null);
            row.setVersion(row.getVersion() + 1);
            return true;
        }

        @Override
        public synchronized boolean markDiscarded(String id, String lastError, int expectedVersion) {
            MqOutbox row = rows.get(id);
            if (row == null || row.getVersion() != expectedVersion) {
                return false;
            }
            row.setStatus("DISCARDED");
            row.setClaimedAt(null);
            row.setClaimedBy(null);
            row.setLastError(lastError);
            row.setVersion(row.getVersion() + 1);
            return true;
        }

        @Override
        public synchronized boolean markFailed(String id,
                                               String lastError,
                                               LocalDateTime nextRetryAt,
                                               int newRetryCount,
                                               int expectedVersion) {
            MqOutbox row = rows.get(id);
            if (row == null || row.getVersion() != expectedVersion) {
                return false;
            }
            row.setStatus("PENDING");
            row.setLastError(lastError);
            row.setNextRetryAt(nextRetryAt);
            row.setRetryCount(newRetryCount);
            row.setClaimedAt(null);
            row.setClaimedBy(null);
            row.setVersion(row.getVersion() + 1);
            return true;
        }

        @Override
        public synchronized boolean markPermanentlyFailed(String id,
                                                          String lastError,
                                                          int newRetryCount,
                                                          int expectedVersion) {
            MqOutbox row = rows.get(id);
            if (row == null || row.getVersion() != expectedVersion) {
                return false;
            }
            row.setStatus("FAILED");
            row.setLastError(lastError);
            row.setRetryCount(newRetryCount);
            row.setClaimedAt(null);
            row.setClaimedBy(null);
            row.setVersion(row.getVersion() + 1);
            return true;
        }

        @Override
        public synchronized int deleteOlderSentRows(LocalDateTime cutoff) {
            List<String> ids = rows.values().stream()
                    .filter(row -> "SENT".equals(row.getStatus()))
                    .filter(row -> row.getCreatedAt() != null && row.getCreatedAt().isBefore(cutoff))
                    .map(MqOutbox::getId)
                    .toList();
            ids.forEach(rows::remove);
            return ids.size();
        }

        @Override
        public synchronized int countByStatus(String status) {
            return (int) rows.values().stream()
                    .filter(row -> status.equals(row.getStatus()))
                    .count();
        }

        @Override
        public synchronized List<MqOutbox> findRecent(String eventId,
                                                      String idempotencyKey,
                                                      String status,
                                                      int limit) {
            return rows.values().stream()
                    .filter(row -> status == null || status.equals(row.getStatus()))
                    .limit(limit)
                    .map(InMemoryOutboxRepository::copy)
                    .toList();
        }

        private synchronized int size() {
            return rows.size();
        }

        private synchronized int terminalRows() {
            return countByStatus("SENT") + countByStatus("FAILED") + countByStatus("DISCARDED");
        }

        private synchronized int maxRetryCount() {
            return rows.values().stream().mapToInt(MqOutbox::getRetryCount).max().orElse(0);
        }

        private boolean isClaimable(MqOutbox row, LocalDateTime now, LocalDateTime staleClaimBefore) {
            if ("PENDING".equals(row.getStatus())) {
                return row.getNextRetryAt() == null || !row.getNextRetryAt().isAfter(now);
            }
            return "CLAIMED".equals(row.getStatus())
                    && row.getClaimedAt() != null
                    && !row.getClaimedAt().isAfter(staleClaimBefore);
        }

        private LocalDateTime claimableAt(MqOutbox row) {
            return row.getNextRetryAt() != null ? row.getNextRetryAt() : row.getClaimedAt();
        }

        private static MqOutbox copy(MqOutbox row) {
            if (row == null) {
                return null;
            }
            return MqOutbox.builder()
                    .id(row.getId())
                    .eventType(row.getEventType())
                    .exchange(row.getExchange())
                    .routingKey(row.getRoutingKey())
                    .payload(row.getPayload())
                    .headers(row.getHeaders())
                    .status(row.getStatus())
                    .nextRetryAt(row.getNextRetryAt())
                    .lastError(row.getLastError())
                    .claimedAt(row.getClaimedAt())
                    .claimedBy(row.getClaimedBy())
                    .retryCount(row.getRetryCount())
                    .version(row.getVersion())
                    .createdAt(row.getCreatedAt())
                    .build();
        }
    }
}
