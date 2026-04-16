package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.Channel;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.consumer.KnowledgeIngestTaskListener;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.lock.MqSessionExecLockAcquisition;
import com.yulong.chatagent.mq.lock.MqSessionExecLockLease;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockLease;
import com.yulong.chatagent.mq.lock.MqTaskLockState;
import com.yulong.chatagent.mq.outbox.event.KnowledgeIngestTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
import com.yulong.chatagent.rag.ingestion.RetryableKnowledgeDocumentIngestionException;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2c: MQ chaos evaluation for knowledge-ingest consumer failure handling.
 *
 * This is intentionally a pure mocked-channel harness: it validates the consumer's
 * ack/nack/reject, retry-publish, lock-release, and DLQ decisions without requiring
 * a live RabbitMQ or Redis instance.
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-mq-chaos \
 *      -Dtest=MqChaosEvalTest
 */
@Tag("eval-mq-chaos")
class MqChaosEvalTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void evaluateMqChaosPaths() throws Exception {
        List<ScenarioResult> results = List.of(
                runScenario(
                        "mq-chaos-001",
                        "retry-handoff",
                        "retryable ingestion exception on first attempt",
                        "release RUNNING lock, publish retry with retryCount=1, ack original",
                        harness -> {
                            MqMessageIdentity identity = harness.identity(0, null);
                            harness.givenAcquired(identity);
                            harness.givenDocument();
                            harness.givenRetryableIngestionFailure();

                            harness.listener().handle(harness.message(identity, true), harness.channel);

                            harness.verifyRetryPublishedWithRetryCount(1);
                            verify(harness.distributedLockManager).releaseRunning(any());
                            verify(harness.channel).basicAck(7L, false);
                            verify(harness.distributedLockManager, never()).markFailed(any(), anyString());
                        }
                ),
                runScenario(
                        "mq-chaos-002",
                        "retry-handoff",
                        "retry publish confirm failure",
                        "release RUNNING lock, nack original with requeue=true",
                        harness -> {
                            MqMessageIdentity identity = harness.identity(0, null);
                            harness.givenAcquired(identity);
                            harness.givenDocument();
                            harness.givenRetryableIngestionFailure();
                            doThrow(new AmqpException("confirm timeout") {
                            }).when(harness.rabbitMqMessagePublisher)
                                    .publish(anyString(), anyString(), any(), anyString());

                            harness.listener().handle(harness.message(identity, false), harness.channel);

                            verify(harness.distributedLockManager).releaseRunning(any());
                            verify(harness.channel).basicNack(7L, false, true);
                        }
                ),
                runScenario(
                        "mq-chaos-003",
                        "dlq",
                        "retryable ingestion exception after max retries",
                        "mark lock FAILED and reject without requeue",
                        harness -> {
                            MqMessageIdentity identity = harness.identity(3, null);
                            harness.givenAcquired(identity);
                            harness.givenDocument();
                            harness.givenRetryableIngestionFailure();

                            harness.listener().handle(harness.message(identity, false), harness.channel);

                            verify(harness.distributedLockManager).markFailed(any(), anyString());
                            verify(harness.channel).basicReject(7L, false);
                            verify(harness.rabbitMqMessagePublisher, never())
                                    .publish(anyString(), anyString(), any(), anyString());
                        }
                ),
                runScenario(
                        "mq-chaos-004",
                        "dlq",
                        "terminal missing document",
                        "mark lock FAILED and reject without requeue",
                        harness -> {
                            MqMessageIdentity identity = harness.identity(0, null);
                            harness.givenAcquired(identity);
                            when(harness.knowledgeDocumentRepository.findById("doc-1")).thenReturn(null);

                            harness.listener().handle(harness.message(identity, false), harness.channel);

                            verify(harness.distributedLockManager).markFailed(any(), anyString());
                            verify(harness.channel).basicReject(7L, false);
                            verify(harness.knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
                        }
                ),
                runScenario(
                        "mq-chaos-005",
                        "dedupe",
                        "duplicate completed task delivery",
                        "ack duplicate and skip ingestion",
                        harness -> {
                            when(harness.distributedLockManager.tryAcquire(any(), anyString()))
                                    .thenReturn(new MqTaskLockAcquisition(
                                            MqTaskLockAcquireOutcome.DUPLICATE,
                                            null,
                                            MqTaskLockState.COMPLETED
                                    ));

                            harness.listener().handle(harness.message(harness.identity(0, null), false), harness.channel);

                            verify(harness.channel).basicAck(7L, false);
                            verify(harness.knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
                        }
                ),
                runScenario(
                        "mq-chaos-006",
                        "lock-wait",
                        "task lock already RUNNING",
                        "publish delayed requeue and ack original",
                        harness -> {
                            when(harness.distributedLockManager.tryAcquire(any(), anyString()))
                                    .thenReturn(new MqTaskLockAcquisition(
                                            MqTaskLockAcquireOutcome.WAIT_REQUIRED,
                                            null,
                                            MqTaskLockState.RUNNING
                                    ));

                            harness.listener().handle(harness.message(harness.identity(0, null), false), harness.channel);

                            verify(harness.rabbitMqMessagePublisher)
                                    .publish(eq("retry.direct"), eq("retry.ingest"), any(), anyString());
                            verify(harness.channel).basicAck(7L, false);
                            verify(harness.knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
                        }
                ),
                runScenario(
                        "mq-chaos-007",
                        "lock-wait",
                        "delayed requeue publish failure",
                        "nack original with requeue=true",
                        harness -> {
                            when(harness.distributedLockManager.tryAcquire(any(), anyString()))
                                    .thenReturn(new MqTaskLockAcquisition(
                                            MqTaskLockAcquireOutcome.WAIT_REQUIRED,
                                            null,
                                            MqTaskLockState.RUNNING
                                    ));
                            doThrow(new AmqpException("retry exchange unavailable") {
                            }).when(harness.rabbitMqMessagePublisher)
                                    .publish(anyString(), anyString(), any(), anyString());

                            harness.listener().handle(harness.message(harness.identity(0, null), false), harness.channel);

                            verify(harness.channel).basicNack(7L, false, true);
                            verify(harness.knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
                        }
                ),
                runScenario(
                        "mq-chaos-008",
                        "redis-policy",
                        "Redis tryAcquire failure under FAIL_FAST",
                        "nack original with requeue=true and skip ingestion",
                        harness -> {
                            harness.properties.getLocks()
                                    .setIngestTaskPolicy(ChatAgentMqProperties.RedisFailurePolicy.FAIL_FAST);
                            when(harness.distributedLockManager.tryAcquire(any(), anyString()))
                                    .thenThrow(new RuntimeException("redis down"));

                            harness.listener().handle(harness.message(harness.identity(0, null), false), harness.channel);

                            verify(harness.channel).basicNack(7L, false, true);
                            verify(harness.knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
                        }
                ),
                runScenario(
                        "mq-chaos-009",
                        "redis-policy",
                        "Redis tryAcquire failure under FAIL_OPEN",
                        "process without idempotency lock and ack",
                        harness -> {
                            harness.properties.getLocks()
                                    .setIngestTaskPolicy(ChatAgentMqProperties.RedisFailurePolicy.FAIL_OPEN);
                            when(harness.distributedLockManager.tryAcquire(any(), anyString()))
                                    .thenThrow(new RuntimeException("redis down"));
                            harness.givenDocument();

                            harness.listener().handle(harness.message(harness.identity(0, null), false), harness.channel);

                            verify(harness.knowledgeDocumentIngestionService).ingestSync(eq("kb-1"), any());
                            verify(harness.channel).basicAck(7L, false);
                            verify(harness.distributedLockManager, never()).markCompleted(any());
                        }
                ),
                runScenario(
                        "mq-chaos-010",
                        "session-serialization",
                        "session execution lock busy for same session",
                        "release task lock, publish delayed requeue, ack original",
                        harness -> {
                            MqMessageIdentity identity = harness.identity(0, "session-1");
                            harness.givenAcquired(identity);
                            when(harness.distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString()))
                                    .thenReturn(new MqSessionExecLockAcquisition(
                                            MqTaskLockAcquireOutcome.WAIT_REQUIRED,
                                            null
                                    ));
                            when(harness.distributedLockManager.releaseRunning(any())).thenReturn(true);

                            harness.listener().handle(harness.message(identity, false), harness.channel);

                            verify(harness.distributedLockManager).releaseRunning(any());
                            verify(harness.rabbitMqMessagePublisher)
                                    .publish(eq("retry.direct"), eq("retry.ingest"), any(), anyString());
                            verify(harness.channel).basicAck(7L, false);
                            verify(harness.knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
                        }
                )
        );

        Map<String, CategoryMetrics> byCategory = byCategory(results);
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("totalScenarios", results.size());
        overall.put("passedScenarios", results.stream().filter(ScenarioResult::passed).count());
        overall.put("passRate", round(rate(results.stream().filter(ScenarioResult::passed).count(), results.size())));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "mq-chaos-eval");
        report.put("mode", "mocked-channel-chaos");
        report.put("overall", overall);
        report.put("byCategory", byCategory);
        report.put("scenarios", results);

        Path reportPath = EvalReportWriter.writeReport("mq-chaos-eval", report);
        System.out.println("=== MQ Chaos Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Overall:");
        System.out.println(OBJECT_MAPPER.writeValueAsString(overall));

        List<ScenarioResult> failed = results.stream()
                .filter(result -> !result.passed())
                .toList();
        assertThat(failed).as("MQ chaos scenario failures").isEmpty();
    }

    private ScenarioResult runScenario(String id,
                                       String category,
                                       String fault,
                                       String expectedOutcome,
                                       ScenarioBody body) {
        ScenarioHarness harness = new ScenarioHarness();
        try {
            body.run(harness);
            return new ScenarioResult(
                    id,
                    category,
                    fault,
                    expectedOutcome,
                    harness.observedActions(),
                    true,
                    null
            );
        } catch (Throwable t) {
            return new ScenarioResult(
                    id,
                    category,
                    fault,
                    expectedOutcome,
                    harness.observedActions(),
                    false,
                    t.getClass().getSimpleName() + ": " + t.getMessage()
            );
        }
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

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    @FunctionalInterface
    private interface ScenarioBody {
        void run(ScenarioHarness harness) throws Exception;
    }

    private record ScenarioResult(
            String id,
            String category,
            String fault,
            String expectedOutcome,
            List<String> observedActions,
            boolean passed,
            String error
    ) {
    }

    private record CategoryMetrics(int scenarios, long passed, double passRate) {
    }

    private static final class ScenarioHarness {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final ChatAgentMqProperties properties = new ChatAgentMqProperties();
        private final RabbitMqMessagePublisher rabbitMqMessagePublisher = mock(RabbitMqMessagePublisher.class);
        private final DistributedLockManager distributedLockManager = mock(DistributedLockManager.class);
        private final LockWatchdog lockWatchdog = mock(LockWatchdog.class);
        private final KnowledgeDocumentRepository knowledgeDocumentRepository = mock(KnowledgeDocumentRepository.class);
        private final KnowledgeChunkRepository knowledgeChunkRepository = mock(KnowledgeChunkRepository.class);
        private final KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer = mock(KnowledgeBaseMilvusIndexer.class);
        private final KnowledgeDocumentIngestionService knowledgeDocumentIngestionService =
                mock(KnowledgeDocumentIngestionService.class);
        private final Channel channel = mock(Channel.class);

        private ScenarioHarness() {
            when(lockWatchdog.watch(any(MqTaskLockLease.class), nullable(MqSessionExecLockLease.class)))
                    .thenReturn(() -> {
                    });
        }

        private KnowledgeIngestTaskListener listener() {
            return new KnowledgeIngestTaskListener(
                    objectMapper,
                    properties,
                    rabbitMqMessagePublisher,
                    distributedLockManager,
                    lockWatchdog,
                    knowledgeDocumentRepository,
                    knowledgeChunkRepository,
                    knowledgeBaseMilvusIndexer,
                    knowledgeDocumentIngestionService
            );
        }

        private void givenAcquired(MqMessageIdentity identity) {
            when(distributedLockManager.tryAcquire(any(), anyString()))
                    .thenReturn(new MqTaskLockAcquisition(
                            MqTaskLockAcquireOutcome.ACQUIRED,
                            taskLease(identity),
                            null
                    ));
        }

        private void givenDocument() {
            KnowledgeDocumentDTO document = KnowledgeDocumentDTO.builder()
                    .id("doc-1")
                    .knowledgeBaseId("kb-1")
                    .deleted(false)
                    .build();
            when(knowledgeDocumentRepository.findById("doc-1")).thenReturn(document);
        }

        private void givenRetryableIngestionFailure() {
            doThrow(new RetryableKnowledgeDocumentIngestionException(
                    "transient parser outage",
                    new RuntimeException("mineru timeout")
            )).when(knowledgeDocumentIngestionService).ingestSync(eq("kb-1"), any());
            when(distributedLockManager.releaseRunning(any())).thenReturn(true);
        }

        private void verifyRetryPublishedWithRetryCount(int retryCount) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(rabbitMqMessagePublisher)
                    .publish(eq("retry.direct"), eq("retry.ingest"), messageCaptor.capture(), anyString());
            assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
                    .containsEntry(MqMessageHeaders.RETRY_COUNT, retryCount);
        }

        private Message message(MqMessageIdentity identity, boolean clearExistingContentFirst) throws Exception {
            KnowledgeIngestTaskPayload payload = new KnowledgeIngestTaskPayload(
                    "kb-1",
                    "doc-1",
                    clearExistingContentFirst
            );
            MessageProperties messageProperties = new MessageProperties();
            messageProperties.setDeliveryTag(7L);
            MqMessageHeaders.apply(messageProperties, identity);
            return MessageBuilder.withBody(objectMapper.writeValueAsBytes(payload))
                    .andProperties(messageProperties)
                    .build();
        }

        private MqMessageIdentity identity(int retryCount, String sessionId) {
            return new MqMessageIdentity(
                    "event-" + retryCount + "-" + (sessionId == null ? "none" : sessionId),
                    "doc-1",
                    "trace-1",
                    "knowledge.ingest",
                    sessionId,
                    "chat.direct",
                    "ingest.task",
                    Instant.parse("2026-03-30T00:00:00Z"),
                    retryCount
            );
        }

        private MqTaskLockLease taskLease(MqMessageIdentity identity) {
            return new MqTaskLockLease(
                    "chatagent:mq:task-lock:" + identity.taskType() + ":" + identity.idempotencyKey(),
                    "token-1",
                    "MqChaosEvalTest:node-a",
                    identity
            );
        }

        private List<String> observedActions() {
            List<String> actions = new ArrayList<>();
            addIfInvoked(actions, channel, "basicAck", "ack");
            addIfInvoked(actions, channel, "basicNack", "nack_requeue");
            addIfInvoked(actions, channel, "basicReject", "reject_dlq");
            addIfInvoked(actions, rabbitMqMessagePublisher, "publish", "publish_retry_or_requeue");
            addIfInvoked(actions, knowledgeDocumentIngestionService, "ingestSync", "ingest_invoked");
            addIfInvoked(actions, distributedLockManager, "markCompleted", "lock_completed");
            addIfInvoked(actions, distributedLockManager, "markFailed", "lock_failed");
            addIfInvoked(actions, distributedLockManager, "releaseRunning", "lock_released");
            addIfInvoked(actions, distributedLockManager, "releaseSessionExecLock", "session_lock_released");
            return actions;
        }

        private void addIfInvoked(List<String> actions, Object mock, String methodName, String action) {
            boolean invoked = mockingDetails(mock).getInvocations().stream()
                    .anyMatch(invocation -> invocation.getMethod().getName().equals(methodName));
            if (invoked) {
                actions.add(action.toLowerCase(Locale.ROOT));
            }
        }
    }
}
