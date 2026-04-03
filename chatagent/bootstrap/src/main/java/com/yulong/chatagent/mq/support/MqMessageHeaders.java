package com.yulong.chatagent.mq.support;

import org.springframework.amqp.core.MessageProperties;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Canonical RabbitMQ header names plus helper methods for writing and validating the identity chain.
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

    private static final Set<String> IMMUTABLE_HEADERS = Set.of(
            EVENT_ID,
            IDEMPOTENCY_KEY,
            TRACE_ID,
            TASK_TYPE,
            ORIGINAL_EXCHANGE,
            ORIGINAL_ROUTING_KEY,
            FIRST_PUBLISHED_AT
    );

    private MqMessageHeaders() {
    }

    public static void apply(MessageProperties properties, MqMessageIdentity identity) {
        if (properties == null) {
            throw new IllegalArgumentException("MessageProperties must not be null");
        }
        if (identity == null) {
            throw new IllegalArgumentException("MqMessageIdentity must not be null");
        }
        toMap(identity).forEach(properties::setHeader);
    }

    public static Map<String, Object> toMap(MqMessageIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("MqMessageIdentity must not be null");
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(EVENT_ID, identity.eventId());
        headers.put(IDEMPOTENCY_KEY, identity.idempotencyKey());
        headers.put(TRACE_ID, identity.traceId());
        headers.put(TASK_TYPE, identity.taskType());
        if (StringUtils.hasText(identity.sessionId())) {
            headers.put(SESSION_ID, identity.sessionId());
        }
        headers.put(ORIGINAL_EXCHANGE, identity.originalExchange());
        headers.put(ORIGINAL_ROUTING_KEY, identity.originalRoutingKey());
        headers.put(FIRST_PUBLISHED_AT, identity.firstPublishedAt().toString());
        headers.put(RETRY_COUNT, identity.retryCount());
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
        return new MqMessageIdentity(
                getRequiredText(headers, EVENT_ID),
                getRequiredText(headers, IDEMPOTENCY_KEY),
                getRequiredText(headers, TRACE_ID),
                getRequiredText(headers, TASK_TYPE),
                getOptionalText(headers, SESSION_ID),
                getRequiredText(headers, ORIGINAL_EXCHANGE),
                getRequiredText(headers, ORIGINAL_ROUTING_KEY),
                Instant.parse(getRequiredText(headers, FIRST_PUBLISHED_AT)),
                getNonNegativeInt(headers, RETRY_COUNT)
        );
    }

    public static boolean isImmutable(String headerName) {
        return IMMUTABLE_HEADERS.contains(headerName);
    }

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

    private static int getNonNegativeInt(Map<String, ?> headers, String headerName) {
        Object value = headers.get(headerName);
        if (value instanceof Number numberValue) {
            int intValue = numberValue.intValue();
            if (intValue >= 0) {
                return intValue;
            }
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            int parsed = Integer.parseInt(stringValue.trim());
            if (parsed >= 0) {
                return parsed;
            }
        }
        throw new IllegalArgumentException("Invalid MQ header value: " + headerName);
    }
}
