package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.GetResponse;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.config.RabbitMqTopologyConfiguration;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.MqSessionExecLockAcquisition;
import com.yulong.chatagent.mq.lock.MqSessionExecLockLease;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockLease;
import com.yulong.chatagent.mq.outbox.OutboxPollingPublisher;
import com.yulong.chatagent.mq.outbox.OutboxRecordService;
import com.yulong.chatagent.mq.outbox.OutboxRepository;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3c: live MQ/Redis reliability evaluation with Testcontainers.
 *
 * This validates infrastructure behavior that the fast in-memory outbox harness
 * cannot cover: PostgreSQL SKIP LOCKED, RabbitMQ confirms, TTL/DLQ routing, and
 * Redis Lua lock operations.
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-mq-live \
 *      -Dtest=LiveMqReliabilityEvalTest
 */
@Tag("eval-mq-live")
@Testcontainers(disabledWithoutDocker = true)
class LiveMqReliabilityEvalTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine")
    );

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    private static LiveHarness harness;

    @BeforeAll
    static void setUpLiveHarness() throws Exception {
        harness = new LiveHarness();
        harness.initialize();
    }

    @AfterAll
    static void tearDownLiveHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void evaluateLiveMqRedisReliability() throws Exception {
        List<ScenarioResult> results = List.of(
                runScenario(
                        "live-mq-001",
                        "postgres-rabbit-outbox",
                        "1000 outbox rows through PostgreSQL and RabbitMQ confirms",
                        "all rows become SENT and the RabbitMQ queue receives one message per event",
                        harness::runAtLeastOnceDelivery
                ),
                runScenario(
                        "live-mq-002",
                        "postgres-skip-locked",
                        "1000 outbox rows drained by four concurrent pollers",
                        "FOR UPDATE SKIP LOCKED prevents duplicate successful publishes",
                        harness::runSkipLockedMultiPoller
                ),
                runScenario(
                        "live-mq-003",
                        "rabbit-ttl-dlq",
                        "real RabbitMQ retry TTL and dead-letter routing",
                        "retry message returns to the primary queue near TTL and rejected primary message lands in DLQ",
                        harness::runRetryTtlAndDlq
                ),
                runScenario(
                        "live-mq-004",
                        "redis-idempotency",
                        "real Redis task/session locks and Lua token checks",
                        "one contender acquires, others wait; wrong-token release fails and correct-token release succeeds",
                        harness::runRedisLockSemantics
                ),
                runScenario(
                        "live-mq-005",
                        "rabbit-return-failure",
                        "unroutable mandatory publish through the outbox poller",
                        "RabbitMQ returned-message path drives outbox row to FAILED after max attempts",
                        harness::runUnroutablePublishFailure
                )
        );

        long passed = results.stream().filter(ScenarioResult::passed).count();
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("totalScenarios", results.size());
        overall.put("passedScenarios", passed);
        overall.put("passRate", round(rate(passed, results.size())));
        overall.put("postgresRowsSeeded", results.stream().mapToInt(result -> result.metrics().getInt("seededRows")).sum());
        overall.put("rabbitMessagesReceived", results.stream().mapToInt(result -> result.metrics().getInt("rabbitMessagesReceived")).sum());
        overall.put("duplicateRabbitEvents", results.stream().mapToInt(result -> result.metrics().getInt("duplicateRabbitEvents")).sum());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "mq-live-reliability-eval");
        report.put("mode", "testcontainers-postgres-rabbitmq-redis");
        report.put("runId", RUN_ID);
        report.put("containers", Map.of(
                "postgres", POSTGRES.getDockerImageName(),
                "rabbitmq", RABBIT.getDockerImageName(),
                "redis", REDIS.getDockerImageName()
        ));
        report.put("overall", overall);
        report.put("scenarios", results);

        Path reportPath = EvalReportWriter.writeReport("mq-live-reliability-eval", report);
        System.out.println("=== Live MQ/Redis Reliability Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println(OBJECT_MAPPER.writeValueAsString(overall));

        assertThat(results)
                .as("live MQ/Redis scenario results")
                .allMatch(ScenarioResult::passed);
    }

    private ScenarioResult runScenario(String id,
                                       String category,
                                       String workload,
                                       String expectedInvariant,
                                       ScenarioBody body) {
        long started = System.nanoTime();
        try {
            ScenarioMetrics metrics = body.run().withElapsedMs(elapsedMs(started));
            return new ScenarioResult(id, category, workload, expectedInvariant, true, null, metrics);
        } catch (Throwable t) {
            ScenarioMetrics metrics = new ScenarioMetrics()
                    .putMetric("elapsedMs", elapsedMs(started));
            return new ScenarioResult(
                    id,
                    category,
                    workload,
                    expectedInvariant,
                    false,
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    metrics
            );
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static double rate(double numerator, double denominator) {
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    @FunctionalInterface
    private interface ScenarioBody {
        ScenarioMetrics run() throws Exception;
    }

    private record ScenarioResult(String id,
                                  String category,
                                  String workload,
                                  String expectedInvariant,
                                  boolean passed,
                                  String failure,
                                  ScenarioMetrics metrics) {
    }

    private static final class ScenarioMetrics extends LinkedHashMap<String, Object> {

        ScenarioMetrics putMetric(String key, Object value) {
            put(key, value);
            return this;
        }

        ScenarioMetrics withElapsedMs(long elapsedMs) {
            put("elapsedMs", elapsedMs);
            return this;
        }

        int getInt(String key) {
            Object value = get(key);
            return value instanceof Number number ? number.intValue() : 0;
        }
    }

    private static final class LiveHarness implements AutoCloseable {

        private final ChatAgentMqProperties properties = new ChatAgentMqProperties();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private DriverManagerDataSource dataSource;
        private JdbcTemplate jdbcTemplate;
        private JdbcOutboxRepository outboxRepository;
        private PlatformTransactionManager transactionManager;
        private CachingConnectionFactory rabbitConnectionFactory;
        private RabbitTemplate rabbitTemplate;
        private RabbitMqMessagePublisher rabbitPublisher;
        private LettuceConnectionFactory redisConnectionFactory;
        private StringRedisTemplate redisTemplate;
        private DistributedLockManager lockManager;

        void initialize() throws Exception {
            configureProperties();
            initializePostgres();
            initializeRabbit();
            initializeRedis();
        }

        ScenarioMetrics runAtLeastOnceDelivery() throws Exception {
            resetPostgresAndRabbit();
            OutboxPollingPublisher poller = newPoller();
            seedPendingRows(1000, properties.getRoutingKeys().getIngestTask());

            long started = System.nanoTime();
            drainWithPoller(poller, 1000, Duration.ofSeconds(30));
            QueueDrain drain = drainQueue(properties.getQueues().getKnowledgeIngestTask(), 1000, Duration.ofSeconds(15));
            long deliveryElapsedMs = elapsedMs(started);

            assertThat(countByStatus("SENT")).isEqualTo(1000);
            assertThat(drain.messageCount()).isEqualTo(1000);
            assertThat(drain.duplicateEventCount()).isZero();

            return new ScenarioMetrics()
                    .putMetric("seededRows", 1000)
                    .putMetric("sentRows", countByStatus("SENT"))
                    .putMetric("failedRows", countByStatus("FAILED"))
                    .putMetric("rabbitMessagesReceived", drain.messageCount())
                    .putMetric("duplicateRabbitEvents", drain.duplicateEventCount())
                    .putMetric("deliveryElapsedMs", deliveryElapsedMs)
                    .putMetric("throughputMsgPerSec", round(1000.0 / Math.max(1.0, deliveryElapsedMs / 1000.0)));
        }

        ScenarioMetrics runSkipLockedMultiPoller() throws Exception {
            resetPostgresAndRabbit();
            seedPendingRows(1000, properties.getRoutingKeys().getIngestTask());
            List<OutboxPollingPublisher> pollers = List.of(newPoller(), newPoller(), newPoller(), newPoller());

            long started = System.nanoTime();
            ExecutorService executor = Executors.newFixedThreadPool(pollers.size());
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Callable<Void>> workers = pollers.stream()
                        .<Callable<Void>>map(poller -> () -> {
                            start.await(5, TimeUnit.SECONDS);
                            while (countByStatus("SENT") < 1000 && elapsedMs(started) < 30_000) {
                                poller.publishDueRows();
                            }
                            return null;
                        })
                        .toList();
                for (Callable<Void> worker : workers) {
                    executor.submit(worker);
                }
                start.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            QueueDrain drain = drainQueue(properties.getQueues().getKnowledgeIngestTask(), 1000, Duration.ofSeconds(15));
            long publishElapsedMs = elapsedMs(started);
            assertThat(countByStatus("SENT")).isEqualTo(1000);
            assertThat(drain.messageCount()).isEqualTo(1000);
            assertThat(drain.duplicateEventCount()).isZero();

            return new ScenarioMetrics()
                    .putMetric("seededRows", 1000)
                    .putMetric("sentRows", countByStatus("SENT"))
                    .putMetric("failedRows", countByStatus("FAILED"))
                    .putMetric("pollerCount", pollers.size())
                    .putMetric("rabbitMessagesReceived", drain.messageCount())
                    .putMetric("duplicateRabbitEvents", drain.duplicateEventCount())
                    .putMetric("publishElapsedMs", publishElapsedMs);
        }

        ScenarioMetrics runRetryTtlAndDlq() throws Exception {
            resetPostgresAndRabbit();
            Message retryMessage = messageForIdentity("retry-ttl-1", properties.getRoutingKeys().getIngestTask());
            long retryStarted = System.nanoTime();
            rabbitTemplate.send(properties.getExchanges().getRetryDirect(), properties.getRoutingKeys().getRetryIngest(), retryMessage);
            Message routedFromRetry = receiveWithDeadline(
                    properties.getQueues().getKnowledgeIngestTask(),
                    Duration.ofMillis(properties.getRetry().getIngestDelayMs() + 3_000L)
            );
            long retryElapsedMs = elapsedMs(retryStarted);
            assertThat(routedFromRetry).isNotNull();
            assertThat(retryElapsedMs)
                    .isGreaterThanOrEqualTo(properties.getRetry().getIngestDelayMs() - 250L)
                    .isLessThanOrEqualTo(properties.getRetry().getIngestDelayMs() + 2_000L);
            drainQueue(properties.getQueues().getKnowledgeIngestTask(), 1, Duration.ofMillis(200));

            Message dlqMessage = messageForIdentity("dlq-1", properties.getRoutingKeys().getIngestTask());
            rabbitTemplate.send(properties.getExchanges().getChatDirect(), properties.getRoutingKeys().getIngestTask(), dlqMessage);
            rejectOneMessage(properties.getQueues().getKnowledgeIngestTask());
            Message deadLettered = receiveWithDeadline(properties.getQueues().getChatDlq(), Duration.ofSeconds(3));
            assertThat(deadLettered).isNotNull();

            return new ScenarioMetrics()
                    .putMetric("retryTtlTargetMs", properties.getRetry().getIngestDelayMs())
                    .putMetric("retryObservedMs", retryElapsedMs)
                    .putMetric("dlqReceived", true)
                    .putMetric("rabbitMessagesReceived", 2)
                    .putMetric("duplicateRabbitEvents", 0);
        }

        ScenarioMetrics runRedisLockSemantics() throws Exception {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
            MqMessageIdentity identity = MqMessageIdentity.initial(
                    "knowledge.ingest",
                    "redis-lock-live",
                    "trace-redis-live",
                    properties.getExchanges().getChatDirect(),
                    properties.getRoutingKeys().getIngestTask()
            );

            int workers = 64;
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger acquired = new AtomicInteger();
            AtomicInteger waitRequired = new AtomicInteger();
            List<MqTaskLockLease> acquiredLeases = java.util.Collections.synchronizedList(new ArrayList<>());
            try {
                for (int i = 0; i < workers; i++) {
                    int workerId = i;
                    executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        MqTaskLockAcquisition acquisition = lockManager.tryAcquire(identity, "worker-" + workerId);
                        if (acquisition.outcome() == MqTaskLockAcquireOutcome.ACQUIRED) {
                            acquired.incrementAndGet();
                            acquiredLeases.add(acquisition.lease());
                        } else if (acquisition.outcome() == MqTaskLockAcquireOutcome.WAIT_REQUIRED) {
                            waitRequired.incrementAndGet();
                        }
                        return null;
                    });
                }
                start.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            assertThat(acquired.get()).isEqualTo(1);
            assertThat(waitRequired.get()).isEqualTo(workers - 1);
            MqTaskLockLease lease = acquiredLeases.get(0);
            boolean wrongTokenReleased = lockManager.releaseRunning(
                    new MqTaskLockLease(lease.key(), "wrong-token", lease.owner(), lease.identity())
            );
            boolean correctTokenReleased = lockManager.releaseRunning(lease);

            MqSessionExecLockAcquisition sessionLeaseOne = lockManager.acquireSessionExecLock("session-live-1", "owner-1");
            MqSessionExecLockAcquisition sessionLeaseTwo = lockManager.acquireSessionExecLock("session-live-1", "owner-2");
            assertThat(sessionLeaseOne.outcome()).isEqualTo(MqTaskLockAcquireOutcome.ACQUIRED);
            assertThat(sessionLeaseTwo.outcome()).isEqualTo(MqTaskLockAcquireOutcome.WAIT_REQUIRED);
            MqSessionExecLockLease sessionLease = sessionLeaseOne.lease();
            boolean wrongSessionRelease = lockManager.releaseSessionExecLock(
                    new MqSessionExecLockLease(sessionLease.key(), "wrong-token", sessionLease.owner(), sessionLease.sessionId())
            );
            boolean correctSessionRelease = lockManager.releaseSessionExecLock(sessionLease);

            assertThat(wrongTokenReleased).isFalse();
            assertThat(correctTokenReleased).isTrue();
            assertThat(wrongSessionRelease).isFalse();
            assertThat(correctSessionRelease).isTrue();

            return new ScenarioMetrics()
                    .putMetric("redisWorkers", workers)
                    .putMetric("taskLockAcquired", acquired.get())
                    .putMetric("taskLockWaitRequired", waitRequired.get())
                    .putMetric("wrongTokenReleased", wrongTokenReleased)
                    .putMetric("correctTokenReleased", correctTokenReleased)
                    .putMetric("wrongSessionRelease", wrongSessionRelease)
                    .putMetric("correctSessionRelease", correctSessionRelease);
        }

        ScenarioMetrics runUnroutablePublishFailure() throws Exception {
            resetPostgresAndRabbit();
            seedPendingRows(1, "missing.route." + RUN_ID);
            OutboxPollingPublisher poller = newPoller();
            for (int i = 0; i < properties.getOutbox().getMaxPublishAttempts(); i++) {
                poller.publishDueRows();
                Thread.sleep(25L);
            }

            MqOutbox row = jdbcTemplate.queryForObject(
                    "SELECT id, status, retry_count, last_error FROM t_mq_outbox LIMIT 1",
                    (rs, rowNum) -> MqOutbox.builder()
                            .id(rs.getString("id"))
                            .status(rs.getString("status"))
                            .retryCount(rs.getInt("retry_count"))
                            .lastError(rs.getString("last_error"))
                            .build()
            );
            assertThat(row).isNotNull();
            assertThat(row.getStatus()).isEqualTo("FAILED");
            assertThat(row.getRetryCount()).isEqualTo(properties.getOutbox().getMaxPublishAttempts());
            assertThat(row.getLastError()).containsIgnoringCase("returned");

            return new ScenarioMetrics()
                    .putMetric("seededRows", 1)
                    .putMetric("failedRows", countByStatus("FAILED"))
                    .putMetric("maxPublishAttempts", properties.getOutbox().getMaxPublishAttempts())
                    .putMetric("rabbitMessagesReceived", 0)
                    .putMetric("duplicateRabbitEvents", 0);
        }

        private void configureProperties() {
            properties.getOutbox().setBatchSize(100);
            properties.getOutbox().setClaimTimeoutMs(60_000);
            properties.getOutbox().setConfirmTimeoutMs(5_000);
            properties.getOutbox().setMaxPublishAttempts(3);
            properties.getOutbox().setPublishRetryDelayMs(1);
            properties.getRetry().setAgentDelayMs(1_000);
            properties.getRetry().setIngestDelayMs(1_000);

            properties.getExchanges().setChatDirect("chat.direct.live." + RUN_ID);
            properties.getExchanges().setRetryDirect("retry.direct.live." + RUN_ID);
            properties.getExchanges().setDlxDirect("dlx.direct.live." + RUN_ID);
            properties.getQueues().setChatAgentDispatch("chat.agent.dispatch.live." + RUN_ID);
            properties.getQueues().setKnowledgeIngestTask("knowledge.ingest.task.live." + RUN_ID);
            properties.getQueues().setRetryAgent10s("retry.agent.10s.live." + RUN_ID);
            properties.getQueues().setRetryIngest30s("retry.ingest.30s.live." + RUN_ID);
            properties.getQueues().setChatDlq("chat.dlq.live." + RUN_ID);
        }

        private void initializePostgres() throws Exception {
            dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(POSTGRES.getDriverClassName());
            dataSource.setUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            jdbcTemplate = new JdbcTemplate(dataSource);
            transactionManager = new DataSourceTransactionManager(dataSource);
            outboxRepository = new JdbcOutboxRepository(jdbcTemplate);

            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            executeSqlFile("db/migration/V7__phase5_mq_outbox.sql");
            executeSqlFile("db/migration/V9__phase5_mq_outbox_idempotency_index.sql");
        }

        private void initializeRabbit() {
            rabbitConnectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
            rabbitConnectionFactory.setUsername(RABBIT.getAdminUsername());
            rabbitConnectionFactory.setPassword(RABBIT.getAdminPassword());
            rabbitConnectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            rabbitConnectionFactory.setPublisherReturns(true);

            rabbitTemplate = new RabbitTemplate(rabbitConnectionFactory);
            rabbitTemplate.setMandatory(true);
            rabbitTemplate.setReceiveTimeout(250L);

            RabbitAdmin admin = new RabbitAdmin(rabbitConnectionFactory);
            for (Declarable declarable : new RabbitMqTopologyConfiguration()
                    .chatAgentMqTopology(properties)
                    .getDeclarables()) {
                if (declarable instanceof DirectExchange exchange) {
                    admin.declareExchange(exchange);
                } else if (declarable instanceof Queue queue) {
                    admin.declareQueue(queue);
                } else if (declarable instanceof Binding binding) {
                    admin.declareBinding(binding);
                }
            }
            rabbitPublisher = new RabbitMqMessagePublisher(rabbitTemplate, properties);
        }

        private void initializeRedis() {
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                    REDIS.getHost(),
                    REDIS.getMappedPort(6379)
            );
            redisConnectionFactory = new LettuceConnectionFactory(configuration);
            redisConnectionFactory.afterPropertiesSet();
            redisTemplate = new StringRedisTemplate(redisConnectionFactory);
            redisTemplate.afterPropertiesSet();
            lockManager = new DistributedLockManager(redisTemplate, objectMapper, properties);
        }

        private OutboxPollingPublisher newPoller() {
            OutboxRecordService target = new OutboxRecordService(outboxRepository, properties);
            ProxyFactory proxyFactory = new ProxyFactory(target);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(new TransactionInterceptor(
                    transactionManager,
                    new AnnotationTransactionAttributeSource()
            ));
            OutboxRecordService transactionalRecordService = (OutboxRecordService) proxyFactory.getProxy();
            return new OutboxPollingPublisher(transactionalRecordService, rabbitPublisher, objectMapper, properties);
        }

        private void resetPostgresAndRabbit() {
            jdbcTemplate.update("TRUNCATE TABLE t_mq_outbox");
            purge(properties.getQueues().getKnowledgeIngestTask());
            purge(properties.getQueues().getChatAgentDispatch());
            purge(properties.getQueues().getRetryIngest30s());
            purge(properties.getQueues().getRetryAgent10s());
            purge(properties.getQueues().getChatDlq());
        }

        private void purge(String queue) {
            rabbitTemplate.execute(channel -> {
                channel.queuePurge(queue);
                return null;
            });
        }

        private void seedPendingRows(int count, String routingKey) throws Exception {
            for (int i = 0; i < count; i++) {
                MqMessageIdentity identity = MqMessageIdentity.initial(
                        "knowledge.ingest",
                        "live-doc-" + RUN_ID + "-" + i,
                        "trace-" + RUN_ID,
                        properties.getExchanges().getChatDirect(),
                        routingKey
                );
                outboxRepository.insert(MqOutbox.builder()
                        .id(UUID.randomUUID().toString())
                        .eventType("knowledge.ingest")
                        .exchange(properties.getExchanges().getChatDirect())
                        .routingKey(routingKey)
                        .payload("{\"documentId\":\"" + identity.idempotencyKey() + "\"}")
                        .headers(objectMapper.writeValueAsString(MqMessageHeaders.toMap(identity)))
                        .status("PENDING")
                        .nextRetryAt(LocalDateTime.now().minusSeconds(1))
                        .retryCount(0)
                        .version(0)
                        .createdAt(LocalDateTime.now())
                        .build());
            }
        }

        private void drainWithPoller(OutboxPollingPublisher poller, int expectedSent, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && countByStatus("SENT") < expectedSent) {
                poller.publishDueRows();
            }
            assertThat(countByStatus("SENT")).isEqualTo(expectedSent);
        }

        private int countByStatus(String status) {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_mq_outbox WHERE status = ?",
                    Integer.class,
                    status
            );
        }

        private Message messageForIdentity(String key, String routingKey) {
            MqMessageIdentity identity = MqMessageIdentity.initial(
                    "knowledge.ingest",
                    key + "-" + RUN_ID,
                    "trace-" + key,
                    properties.getExchanges().getChatDirect(),
                    routingKey
            );
            MessageProperties messageProperties = new MessageProperties();
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            MqMessageHeaders.apply(messageProperties, identity);
            return MessageBuilder.withBody(("{\"id\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8))
                    .andProperties(messageProperties)
                    .build();
        }

        private Message receiveWithDeadline(String queue, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            Message message;
            do {
                message = rabbitTemplate.receive(queue, 100L);
                if (message != null) {
                    return message;
                }
            } while (System.nanoTime() < deadline);
            return null;
        }

        private void rejectOneMessage(String queue) {
            rabbitTemplate.execute(channel -> {
                GetResponse response = null;
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (System.nanoTime() < deadline && response == null) {
                    response = channel.basicGet(queue, false);
                    if (response == null) {
                        Thread.sleep(50L);
                    }
                }
                assertThat(response).isNotNull();
                channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
                return null;
            });
        }

        private QueueDrain drainQueue(String queue, int expected, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            Set<String> eventIds = new HashSet<>();
            int duplicateEvents = 0;
            int messages = 0;
            while (System.nanoTime() < deadline && messages < expected) {
                Message message = rabbitTemplate.receive(queue, 100L);
                if (message == null) {
                    continue;
                }
                messages++;
                Object eventId = message.getMessageProperties().getHeaders().get(MqMessageHeaders.EVENT_ID);
                if (eventId != null && !eventIds.add(String.valueOf(eventId))) {
                    duplicateEvents++;
                }
            }
            return new QueueDrain(messages, duplicateEvents);
        }

        private void executeSqlFile(String classpathResource) throws Exception {
            String rawSql = new String(
                    java.util.Objects.requireNonNull(LiveMqReliabilityEvalTest.class.getClassLoader()
                                    .getResourceAsStream(classpathResource))
                            .readAllBytes(),
                    StandardCharsets.UTF_8
            );
            StringBuilder executableSql = new StringBuilder();
            for (String line : rawSql.split("\\R")) {
                String trimmedLine = line.trim();
                if (!trimmedLine.startsWith("--") && !trimmedLine.isEmpty()) {
                    executableSql.append(line).append('\n');
                }
            }
            for (String statement : executableSql.toString().split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    jdbcTemplate.execute(trimmed);
                }
            }
        }

        @Override
        public void close() {
            if (redisConnectionFactory != null) {
                redisConnectionFactory.destroy();
            }
            if (rabbitConnectionFactory != null) {
                rabbitConnectionFactory.destroy();
            }
        }
    }

    private record QueueDrain(int messageCount, int duplicateEventCount) {
    }

    private static final class JdbcOutboxRepository implements OutboxRepository {

        private final JdbcTemplate jdbcTemplate;

        private JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public int insert(MqOutbox outbox) {
            return jdbcTemplate.update("""
                            INSERT INTO t_mq_outbox
                            (id, event_type, exchange, routing_key, payload, headers, status, next_retry_at,
                             last_error, claimed_at, claimed_by, retry_count, version, created_at)
                            VALUES (CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (id) DO NOTHING
                            """,
                    outbox.getId(),
                    outbox.getEventType(),
                    outbox.getExchange(),
                    outbox.getRoutingKey(),
                    outbox.getPayload(),
                    outbox.getHeaders(),
                    outbox.getStatus(),
                    timestamp(outbox.getNextRetryAt()),
                    outbox.getLastError(),
                    timestamp(outbox.getClaimedAt()),
                    outbox.getClaimedBy(),
                    outbox.getRetryCount(),
                    outbox.getVersion(),
                    timestamp(outbox.getCreatedAt()));
        }

        @Override
        public MqOutbox findById(String id) {
            List<MqOutbox> rows = jdbcTemplate.query("""
                            SELECT id, event_type, exchange, routing_key, payload::text AS payload, headers::text AS headers,
                                   status, next_retry_at, last_error, claimed_at, claimed_by, retry_count, version, created_at
                            FROM t_mq_outbox
                            WHERE id = CAST(? AS uuid)
                            """,
                    (rs, rowNum) -> mapRow(rs),
                    id);
            return rows.isEmpty() ? null : rows.get(0);
        }

        @Override
        public List<MqOutbox> selectClaimableBatch(int limit,
                                                   LocalDateTime now,
                                                   LocalDateTime staleClaimBefore,
                                                   int maxAttempts) {
            return jdbcTemplate.query("""
                            SELECT id, event_type, exchange, routing_key, payload::text AS payload, headers::text AS headers,
                                   status, next_retry_at, last_error, claimed_at, claimed_by, retry_count, version, created_at
                            FROM t_mq_outbox
                            WHERE retry_count < ?
                              AND (
                                (status = 'PENDING' AND next_retry_at <= ?)
                                OR (status = 'CLAIMED' AND claimed_at IS NOT NULL AND claimed_at <= ?)
                              )
                            ORDER BY COALESCE(next_retry_at, claimed_at) ASC
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """,
                    (rs, rowNum) -> mapRow(rs),
                    maxAttempts,
                    timestamp(now),
                    timestamp(staleClaimBefore),
                    limit);
        }

        @Override
        public boolean markClaimed(String id, String claimedBy, LocalDateTime claimedAt, int expectedVersion) {
            return jdbcTemplate.update("""
                            UPDATE t_mq_outbox
                            SET status = 'CLAIMED', claimed_by = ?, claimed_at = ?, version = version + 1
                            WHERE id = CAST(? AS uuid) AND version = ?
                            """,
                    claimedBy,
                    timestamp(claimedAt),
                    id,
                    expectedVersion) > 0;
        }

        @Override
        public boolean markSent(String id, int expectedVersion) {
            return jdbcTemplate.update("""
                            UPDATE t_mq_outbox
                            SET status = 'SENT', claimed_at = NULL, claimed_by = NULL, last_error = NULL, version = version + 1
                            WHERE id = CAST(? AS uuid) AND version = ?
                            """,
                    id,
                    expectedVersion) > 0;
        }

        @Override
        public boolean markDiscarded(String id, String lastError, int expectedVersion) {
            return jdbcTemplate.update("""
                            UPDATE t_mq_outbox
                            SET status = 'DISCARDED', claimed_at = NULL, claimed_by = NULL, last_error = ?, version = version + 1
                            WHERE id = CAST(? AS uuid) AND version = ? AND status IN ('PENDING', 'FAILED', 'CLAIMED')
                            """,
                    lastError,
                    id,
                    expectedVersion) > 0;
        }

        @Override
        public boolean markFailed(String id,
                                  String lastError,
                                  LocalDateTime nextRetryAt,
                                  int newRetryCount,
                                  int expectedVersion) {
            return jdbcTemplate.update("""
                            UPDATE t_mq_outbox
                            SET status = 'PENDING', last_error = ?, next_retry_at = ?, retry_count = ?,
                                claimed_at = NULL, claimed_by = NULL, version = version + 1
                            WHERE id = CAST(? AS uuid) AND version = ?
                            """,
                    lastError,
                    timestamp(nextRetryAt),
                    newRetryCount,
                    id,
                    expectedVersion) > 0;
        }

        @Override
        public boolean markPermanentlyFailed(String id, String lastError, int newRetryCount, int expectedVersion) {
            return jdbcTemplate.update("""
                            UPDATE t_mq_outbox
                            SET status = 'FAILED', last_error = ?, retry_count = ?,
                                claimed_at = NULL, claimed_by = NULL, version = version + 1
                            WHERE id = CAST(? AS uuid) AND version = ?
                            """,
                    lastError,
                    newRetryCount,
                    id,
                    expectedVersion) > 0;
        }

        @Override
        public int deleteOlderSentRows(LocalDateTime cutoff) {
            return jdbcTemplate.update(
                    "DELETE FROM t_mq_outbox WHERE status = 'SENT' AND created_at < ?",
                    timestamp(cutoff)
            );
        }

        @Override
        public int countByStatus(String status) {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_mq_outbox WHERE status = ?",
                    Integer.class,
                    status
            );
        }

        @Override
        public List<MqOutbox> findRecent(String eventId, String idempotencyKey, String status, int limit) {
            return jdbcTemplate.query("""
                            SELECT id, event_type, exchange, routing_key, payload::text AS payload, headers::text AS headers,
                                   status, next_retry_at, last_error, claimed_at, claimed_by, retry_count, version, created_at
                            FROM t_mq_outbox
                            WHERE (? IS NULL OR headers ->> 'x-event-id' = ?)
                              AND (? IS NULL OR headers ->> 'x-idempotency-key' = ?)
                              AND (? IS NULL OR status = ?)
                            ORDER BY created_at DESC
                            LIMIT ?
                            """,
                    (rs, rowNum) -> mapRow(rs),
                    eventId,
                    eventId,
                    idempotencyKey,
                    idempotencyKey,
                    status,
                    status,
                    limit);
        }

        private MqOutbox mapRow(ResultSet rs) throws java.sql.SQLException {
            return MqOutbox.builder()
                    .id(rs.getString("id"))
                    .eventType(rs.getString("event_type"))
                    .exchange(rs.getString("exchange"))
                    .routingKey(rs.getString("routing_key"))
                    .payload(rs.getString("payload"))
                    .headers(rs.getString("headers"))
                    .status(rs.getString("status"))
                    .nextRetryAt(localDateTime(rs, "next_retry_at"))
                    .lastError(rs.getString("last_error"))
                    .claimedAt(localDateTime(rs, "claimed_at"))
                    .claimedBy(rs.getString("claimed_by"))
                    .retryCount(rs.getInt("retry_count"))
                    .version(rs.getInt("version"))
                    .createdAt(localDateTime(rs, "created_at"))
                    .build();
        }

        private LocalDateTime localDateTime(ResultSet rs, String column) throws java.sql.SQLException {
            Timestamp timestamp = rs.getTimestamp(column);
            return timestamp == null ? null : timestamp.toLocalDateTime();
        }

        private static Timestamp timestamp(LocalDateTime value) {
            return value == null ? null : Timestamp.valueOf(value);
        }
    }
}
