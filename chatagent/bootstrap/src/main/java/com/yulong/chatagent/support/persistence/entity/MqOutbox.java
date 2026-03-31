package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for the {@code t_mq_outbox} table.
 */
@Data
@Builder
public class MqOutbox {
    private String id;
    private String eventType;
    private String exchange;
    private String routingKey;
    private String payload;
    private String headers;
    private String status;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime claimedAt;
    private String claimedBy;
    private int retryCount;
    private int version;
    private LocalDateTime createdAt;
}
