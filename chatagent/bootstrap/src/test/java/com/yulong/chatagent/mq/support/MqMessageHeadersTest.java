package com.yulong.chatagent.mq.support;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqMessageHeadersTest {

    @Test
    void shouldRoundTripRequiredHeaders() {
        MessageProperties properties = new MessageProperties();
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-1",
                "doc-1",
                "trace-1",
                "knowledge.ingest",
                null,
                "chat.direct",
                "ingest.task",
                Instant.parse("2026-03-30T00:00:00Z"),
                2
        );

        MqMessageHeaders.apply(properties, identity);

        assertThat(MqMessageHeaders.read(properties)).isEqualTo(identity);
        // isImmutable 是旧 header 改写保护入口，生产代码不再调用，主类中已停用。
    }

    @Test
    void shouldRoundTripIdentityThroughMapRepresentation() {
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-9",
                "doc-9",
                "trace-9",
                "knowledge.ingest",
                null,
                "chat.direct",
                "ingest.task",
                Instant.parse("2026-03-30T00:00:00Z"),
                1
        );

        Map<String, Object> headers = MqMessageHeaders.toMap(identity);

        assertThat(headers).containsEntry(MqMessageHeaders.EVENT_ID, "event-9");
        assertThat(MqMessageHeaders.fromMap(headers)).isEqualTo(identity);
    }

    @Test
    void shouldRejectMissingRequiredHeader() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(MqMessageHeaders.EVENT_ID, "event-1");

        assertThatThrownBy(() -> MqMessageHeaders.read(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MqMessageHeaders.IDEMPOTENCY_KEY);
    }

    @Test
    void shouldCreateInitialIdentityWithRetryCountZero() {
        MqMessageIdentity identity = MqMessageIdentity.initial(
                "knowledge.ingest",
                "doc-2",
                null,
                "chat.direct",
                "ingest.task"
        );

        assertThat(identity.retryCount()).isZero();
        assertThat(identity.eventId()).isNotBlank();
        assertThat(identity.traceId()).isNotBlank();
        assertThat(identity.firstPublishedAt()).isNotNull();
    }

    @Test
    void shouldParseRetryCountFromStringHeader() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(MqMessageHeaders.EVENT_ID, "event-2");
        properties.setHeader(MqMessageHeaders.IDEMPOTENCY_KEY, "doc-3");
        properties.setHeader(MqMessageHeaders.TRACE_ID, "trace-2");
        properties.setHeader(MqMessageHeaders.TASK_TYPE, "knowledge.ingest");
        properties.setHeader(MqMessageHeaders.ORIGINAL_EXCHANGE, "chat.direct");
        properties.setHeader(MqMessageHeaders.ORIGINAL_ROUTING_KEY, "ingest.task");
        properties.setHeader(MqMessageHeaders.FIRST_PUBLISHED_AT, "2026-03-30T00:00:00Z");
        properties.setHeader(MqMessageHeaders.RETRY_COUNT, "3");

        MqMessageIdentity identity = MqMessageHeaders.read(properties);

        assertThat(identity.retryCount()).isEqualTo(3);
    }
}
