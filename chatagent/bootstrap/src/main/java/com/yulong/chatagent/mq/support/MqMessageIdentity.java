package com.yulong.chatagent.mq.support;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * MQ 消息身份链。
 *
 * 这个 record 是理解幂等性的核心：
 * 1. eventId：一次投递事件的随机 id，用于追踪日志，不用于业务去重；
 * 2. idempotencyKey：稳定业务键，用于判断“是不是同一个任务”；
 * 3. taskType + idempotencyKey：组成 Redis task lock key；
 * 4. retryCount：消费者显式重试时递增，进入 retry queue / DLQ 时也会携带。
 */
public record MqMessageIdentity(
        String eventId,
        String idempotencyKey,
        String traceId,
        String taskType,
        String sessionId,
        String originalExchange,
        String originalRoutingKey,
        Instant firstPublishedAt,
        int retryCount
) {

    public MqMessageIdentity {
        // 构造时强校验，确保进入 MQ 的消息一定带完整幂等/追踪信息。
        eventId = requireText(eventId, MqMessageHeaders.EVENT_ID);
        idempotencyKey = requireText(idempotencyKey, MqMessageHeaders.IDEMPOTENCY_KEY);
        traceId = requireText(traceId, MqMessageHeaders.TRACE_ID);
        taskType = requireText(taskType, MqMessageHeaders.TASK_TYPE);
        sessionId = normalizeOptionalText(sessionId);
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
        return initial(taskType, idempotencyKey, traceId, null, originalExchange, originalRoutingKey);
    }

    public static MqMessageIdentity initial(String taskType,
                                            String idempotencyKey,
                                            String traceId,
                                            String sessionId,
                                            String originalExchange,
                                            String originalRoutingKey) {
        String resolvedTraceId = StringUtils.hasText(traceId) ? traceId.trim() : UUID.randomUUID().toString();
        return new MqMessageIdentity(
                // eventId 由应用侧生成，不是 RabbitMQ 自动生成；每次 initial 都会产生新的投递事件 id。
                UUID.randomUUID().toString(),
                // idempotencyKey 必须由业务传入，例如 agent.run 用 sessionId + ":" + turnId。
                idempotencyKey,
                resolvedTraceId,
                taskType,
                sessionId,
                originalExchange,
                originalRoutingKey,
                Instant.now(),
                0
        );
    }

    public MqMessageIdentity withRetryCount(int nextRetryCount) {
        // 重试只改变 retryCount，不改变 eventId/idempotencyKey/traceId。
        // 这样同一任务的所有重试仍能被识别为同一个业务任务。
        return new MqMessageIdentity(
                eventId,
                idempotencyKey,
                traceId,
                taskType,
                sessionId,
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

    private static String normalizeOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
