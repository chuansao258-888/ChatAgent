package com.yulong.chatagent.admin.model.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** MQ outbox retry diagnostics: queue depths plus a sample of recent outbox records. */
@Data
@Builder
public class GetMqOutboxRetryResponse {
    private long pendingCount;
    private long claimedCount;
    private long sentCount;
    private long failedCount;
    private long retryAgentQueueDepth;
    private long retryIngestQueueDepth;
    private long dlqQueueDepth;
    private List<OutboxRecord> records;

    /** One outbox record surfaced for admin retry inspection. */
    @Data
    @Builder
    public static class OutboxRecord {
        private String id;
        private String eventType;
        private String exchange;
        private String routingKey;
        private String status;
        private String eventId;
        private String idempotencyKey;
        private int retryCount;
        private LocalDateTime nextRetryAt;
        private String lastError;
        private LocalDateTime createdAt;
    }
}
