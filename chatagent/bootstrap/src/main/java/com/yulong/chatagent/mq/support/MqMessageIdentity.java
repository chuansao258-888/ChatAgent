package com.yulong.chatagent.mq.support;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable identity chain that must stay stable across publish, retry, dead-lettering, and replay.
 */
public record MqMessageIdentity(
        String eventId,
        String idempotencyKey,
        String traceId,
        String taskType,
        String originalExchange,
        String originalRoutingKey,
        Instant firstPublishedAt,
        int retryCount
) {

    public MqMessageIdentity {
        eventId = requireText(eventId, MqMessageHeaders.EVENT_ID);
        idempotencyKey = requireText(idempotencyKey, MqMessageHeaders.IDEMPOTENCY_KEY);
        traceId = requireText(traceId, MqMessageHeaders.TRACE_ID);
        taskType = requireText(taskType, MqMessageHeaders.TASK_TYPE);
        originalExchange = requireText(originalExchange, MqMessageHeaders.ORIGINAL_EXCHANGE);
        originalRoutingKey = requireText(originalRoutingKey, MqMessageHeaders.ORIGINAL_ROUTING_KEY);
        if (firstPublishedAt == null) {
            throw new IllegalArgumentException(MqMessageHeaders.FIRST_PUBLISHED_AT + " must not be null");
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException(MqMessageHeaders.RETRY_COUNT + " must not be negative");
        }
    }

    public static MqMessageIdentity initial(String taskType,
                                            String idempotencyKey,
                                            String traceId,
                                            String originalExchange,
                                            String originalRoutingKey) {
        String resolvedTraceId = StringUtils.hasText(traceId) ? traceId.trim() : UUID.randomUUID().toString();
        return new MqMessageIdentity(
                UUID.randomUUID().toString(),
                idempotencyKey,
                resolvedTraceId,
                taskType,
                originalExchange,
                originalRoutingKey,
                Instant.now(),
                0
        );
    }

    public MqMessageIdentity withRetryCount(int nextRetryCount) {
        return new MqMessageIdentity(
                eventId,
                idempotencyKey,
                traceId,
                taskType,
                originalExchange,
                originalRoutingKey,
                firstPublishedAt,
                nextRetryCount
        );
    }

    private static String requireText(String value, String headerName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(headerName + " must not be blank");
        }
        return value.trim();
    }
}
