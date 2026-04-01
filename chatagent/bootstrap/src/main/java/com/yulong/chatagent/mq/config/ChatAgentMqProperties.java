package com.yulong.chatagent.mq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for RabbitMQ topology names and rollout-safe defaults.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.mq")
@Data
public class ChatAgentMqProperties {

    private boolean enabled = false;
    private final Exchanges exchanges = new Exchanges();
    private final RoutingKeys routingKeys = new RoutingKeys();
    private final Queues queues = new Queues();
    private final Retry retry = new Retry();
    private final Consumers consumers = new Consumers();
    private final Outbox outbox = new Outbox();
    private final Locks locks = new Locks();
    private final Dispatchers dispatchers = new Dispatchers();

    @Data
    public static class Exchanges {

        private String chatDirect = "chat.direct";
        private String retryDirect = "retry.direct";
        private String dlxDirect = "dlx.direct";
    }

    @Data
    public static class RoutingKeys {

        private String agentRun = "agent.run";
        private String ingestTask = "ingest.task";
        private String retryAgent = "retry.agent";
        private String retryIngest = "retry.ingest";
        private String deadLetter = "dead.letter";
    }

    @Data
    public static class Queues {

        private String chatAgentDispatch = "chat.agent.dispatch";
        private String knowledgeIngestTask = "knowledge.ingest.task";
        private String retryAgent10s = "retry.agent.10s";
        private String retryIngest30s = "retry.ingest.30s";
        private String chatDlq = "chat.dlq";
    }

    @Data
    public static class Retry {

        private int agentDelayMs = 10_000;
        private int ingestDelayMs = 30_000;
    }

    @Data
    public static class Outbox {

        private int pollIntervalMs = 2000;
        private int batchSize = 10;
        private int maxPublishAttempts = 5;
        private int publishRetryDelayMs = 10_000;
        private int claimTimeoutMs = 60_000;
        private int confirmTimeoutMs = 5_000;
        private int cleanupIntervalMs = 86_400_000;
        private int cleanupRetentionDays = 7;
    }

    @Data
    public static class Consumers {

        // Reserved for Stage 4A/4B listener container tuning once MQ consumers are introduced.
        private int agentPrefetch = 5;
        private int agentConcurrency = 5;
        private int ingestPrefetch = 1;
        private int ingestConcurrency = 2;
    }

    public enum RedisFailurePolicy {
        FAIL_FAST,
        FAIL_OPEN
    }

    @Data
    public static class Locks {

        private int runningTtlMs = 60_000;
        private int watchdogIntervalMs = 20_000;
        private int completedTtlMs = 86_400_000;
        private int failedTtlMs = 3_600_000;
        private RedisFailurePolicy agentRunPolicy = RedisFailurePolicy.FAIL_OPEN;
        private RedisFailurePolicy ingestTaskPolicy = RedisFailurePolicy.FAIL_FAST;

        public RedisFailurePolicy getPolicyForTask(String taskType) {
            if ("knowledge.ingest".equals(taskType)) {
                return ingestTaskPolicy;
            }
            if ("agent.run".equals(taskType)) {
                return agentRunPolicy;
            }
            return RedisFailurePolicy.FAIL_FAST;
        }
    }

    @Data
    public static class Dispatchers {

        private boolean agentRunEnabled = false;
    }
}
