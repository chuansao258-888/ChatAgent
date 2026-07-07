package com.yulong.chatagent.mq.support;

import org.springframework.amqp.core.MessageProperties;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQ header 契约。
 *
 * outbox.headers、RabbitMQ MessageProperties.headers、consumer 读取 identity，
 * 都统一走这里，避免各处手写 header 名称导致字段缺失或拼写不一致。
 */
public final class MqMessageHeaders {

    public static final String EVENT_ID = "x-event-id";
    public static final String IDEMPOTENCY_KEY = "x-idempotency-key";
    public static final String TRACE_ID = "x-trace-id";
    public static final String TASK_TYPE = "x-task-type";
    public static final String SESSION_ID = "x-session-id";
    public static final String ORIGINAL_EXCHANGE = "x-original-exchange";
    public static final String ORIGINAL_ROUTING_KEY = "x-original-routing-key";
    public static final String FIRST_PUBLISHED_AT = "x-first-published-at";
    public static final String RETRY_COUNT = "x-retry-count";
    // 容量等待时间 header：对旧消息是可选的，缺失时反序列化为 null。
    public static final String CAPACITY_WAIT_STARTED_AT = "x-agent-capacity-wait-started-at";
    public static final String CAPACITY_WAIT_LAST_NOTIFIED_AT = "x-agent-capacity-wait-last-notified-at";

    // 旧 header 改写保护入口已停用：当前 MQ 重试路径直接使用 MqMessageIdentity
    // 重建规范 header，不再逐个判断 headerName 是否不可变。
    // private static final Set<String> IMMUTABLE_HEADERS = Set.of(
    //         EVENT_ID,
    //         IDEMPOTENCY_KEY,
    //         TRACE_ID,
    //         TASK_TYPE,
    //         ORIGINAL_EXCHANGE,
    //         ORIGINAL_ROUTING_KEY,
    //         FIRST_PUBLISHED_AT
    // );

    private MqMessageHeaders() {
    }

    public static void apply(MessageProperties properties, MqMessageIdentity identity) {
        if (properties == null) {
            throw new IllegalArgumentException("MessageProperties must not be null");
        }
        if (identity == null) {
            throw new IllegalArgumentException("MqMessageIdentity must not be null");
        }
        // apply 用于直接构建 MQ Message；outbox 场景会先 toMap 再序列化到数据库。
        toMap(identity).forEach(properties::setHeader);
    }

    public static Map<String, Object> toMap(MqMessageIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("MqMessageIdentity must not be null");
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        // eventId/traceId 用来串日志；idempotencyKey/taskType 用来做幂等锁。
        headers.put(EVENT_ID, identity.eventId());
        headers.put(IDEMPOTENCY_KEY, identity.idempotencyKey());
        headers.put(TRACE_ID, identity.traceId());
        headers.put(TASK_TYPE, identity.taskType());
        if (StringUtils.hasText(identity.sessionId())) {
            headers.put(SESSION_ID, identity.sessionId());
        }
        headers.put(ORIGINAL_EXCHANGE, identity.originalExchange());
        headers.put(ORIGINAL_ROUTING_KEY, identity.originalRoutingKey());
        // firstPublishedAt 保留第一次发布的时间，不随 retry 改变，方便排查消息年龄。
        headers.put(FIRST_PUBLISHED_AT, identity.firstPublishedAt().toString());
        headers.put(RETRY_COUNT, identity.retryCount());
        // 容量等待时间只在非空时写出；旧消息没有这些值，缺失即为 null。
        if (identity.capacityWaitStartedAt() != null) {
            headers.put(CAPACITY_WAIT_STARTED_AT, identity.capacityWaitStartedAt().toString());
        }
        if (identity.capacityWaitLastNotifiedAt() != null) {
            headers.put(CAPACITY_WAIT_LAST_NOTIFIED_AT, identity.capacityWaitLastNotifiedAt().toString());
        }
        return headers;
    }

    public static MqMessageIdentity read(MessageProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("MessageProperties must not be null");
        }
        return fromMap(properties.getHeaders());
    }

    public static MqMessageIdentity fromMap(Map<String, ?> headers) {
        if (headers == null) {
            throw new IllegalArgumentException("MQ headers must not be null");
        }
        // 这里会做完整校验，坏 header 会尽早失败，而不是进入业务处理后才出现隐性问题。
        return new MqMessageIdentity(
                getRequiredText(headers, EVENT_ID),
                getRequiredText(headers, IDEMPOTENCY_KEY),
                getRequiredText(headers, TRACE_ID),
                getRequiredText(headers, TASK_TYPE),
                getOptionalText(headers, SESSION_ID),
                getRequiredText(headers, ORIGINAL_EXCHANGE),
                getRequiredText(headers, ORIGINAL_ROUTING_KEY),
                Instant.parse(getRequiredText(headers, FIRST_PUBLISHED_AT)),
                getNonNegativeInt(headers, RETRY_COUNT),
                // 容量等待字段对旧消息可选：缺失时返回 null，不破坏既有 MQ 消息反序列化。
                getOptionalInstant(headers, CAPACITY_WAIT_STARTED_AT),
                getOptionalInstant(headers, CAPACITY_WAIT_LAST_NOTIFIED_AT)
        );
    }

    // public static boolean isImmutable(String headerName) {
    //     return IMMUTABLE_HEADERS.contains(headerName);
    // }

    private static String getRequiredText(Map<String, ?> headers, String headerName) {
        Object value = headers.get(headerName);
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return stringValue.trim();
        }
        throw new IllegalArgumentException("Missing required MQ header: " + headerName);
    }

    private static String getOptionalText(Map<String, ?> headers, String headerName) {
        Object value = headers.get(headerName);
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return stringValue.trim();
        }
        return null;
    }

    private static Instant getOptionalInstant(Map<String, ?> headers, String headerName) {
        Object value = headers.get(headerName);
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return Instant.parse(stringValue.trim());
        }
        // 旧 MQ 消息没有这些容量等待 header；缺失即为 null。
        return null;
    }

    private static int getNonNegativeInt(Map<String, ?> headers, String headerName) {
        Object value = headers.get(headerName);
        if (value instanceof Number numberValue) {
            int intValue = numberValue.intValue();
            if (intValue >= 0) {
                return intValue;
            }
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            // RabbitMQ header 经过不同客户端/序列化路径后，数字可能变成 String，这里兼容两种形态。
            int parsed = Integer.parseInt(stringValue.trim());
            if (parsed >= 0) {
                return parsed;
            }
        }
        throw new IllegalArgumentException("Invalid MQ header value: " + headerName);
    }
}
