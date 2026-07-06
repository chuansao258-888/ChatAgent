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
                2,
                null,
                null
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
                1,
                null,
                null
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
        // Capacity wait fields initialize to null on a fresh identity.
        assertThat(identity.capacityWaitStartedAt()).isNull();
        assertThat(identity.capacityWaitLastNotifiedAt()).isNull();
    }

    @Test
    void shouldDeserializeLegacyMessagesWithoutCapacityWaitHeaders() {
        // A message published before capacity-wait headers existed must still
        // deserialize, with both capacity wait fields defaulting to null.
        MessageProperties properties = new MessageProperties();
        properties.setHeader(MqMessageHeaders.EVENT_ID, "event-legacy");
        properties.setHeader(MqMessageHeaders.IDEMPOTENCY_KEY, "doc-legacy");
        properties.setHeader(MqMessageHeaders.TRACE_ID, "trace-legacy");
        properties.setHeader(MqMessageHeaders.TASK_TYPE, "agent.run");
        properties.setHeader(MqMessageHeaders.SESSION_ID, "session-1");
        properties.setHeader(MqMessageHeaders.ORIGINAL_EXCHANGE, "chat.direct");
        properties.setHeader(MqMessageHeaders.ORIGINAL_ROUTING_KEY, "agent.run");
        properties.setHeader(MqMessageHeaders.FIRST_PUBLISHED_AT, "2026-01-01T00:00:00Z");
        properties.setHeader(MqMessageHeaders.RETRY_COUNT, 0);

        MqMessageIdentity identity = MqMessageHeaders.read(properties);

        assertThat(identity.eventId()).isEqualTo("event-legacy");
        assertThat(identity.capacityWaitStartedAt()).isNull();
        assertThat(identity.capacityWaitLastNotifiedAt()).isNull();
    }

    @Test
    void shouldRoundTripCapacityWaitFieldsWhenPresent() {
        Instant startedAt = Instant.parse("2026-07-07T10:00:00Z");
        Instant lastNotifiedAt = Instant.parse("2026-07-07T10:00:05Z");
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-cw",
                "doc-cw",
                "trace-cw",
                "agent.run",
                "session-1",
                "chat.direct",
                "agent.run",
                Instant.parse("2026-07-07T09:00:00Z"),
                0,
                startedAt,
                lastNotifiedAt
        );

        MessageProperties properties = new MessageProperties();
        MqMessageHeaders.apply(properties, identity);

        MqMessageIdentity roundTripped = MqMessageHeaders.read(properties);
        assertThat(roundTripped).isEqualTo(identity);
        assertThat(roundTripped.capacityWaitStartedAt()).isEqualTo(startedAt);
        assertThat(roundTripped.capacityWaitLastNotifiedAt()).isEqualTo(lastNotifiedAt);
    }

    @Test
    void withCapacityWaitShouldPreserveStableFields() {
        MqMessageIdentity original = MqMessageIdentity.initial(
                "agent.run", "session-1:turn-1", "trace-1", "session-1", "chat.direct", "agent.run"
        );
        Instant startedAt = Instant.parse("2026-07-07T10:00:00Z");

        MqMessageIdentity updated = original.withCapacityWait(startedAt, null);

        // Stable fields preserved.
        assertThat(updated.eventId()).isEqualTo(original.eventId());
        assertThat(updated.idempotencyKey()).isEqualTo(original.idempotencyKey());
        assertThat(updated.traceId()).isEqualTo(original.traceId());
        assertThat(updated.retryCount()).isEqualTo(original.retryCount());
        assertThat(updated.firstPublishedAt()).isEqualTo(original.firstPublishedAt());
        // Capacity wait updated.
        assertThat(updated.capacityWaitStartedAt()).isEqualTo(startedAt);
        assertThat(updated.capacityWaitLastNotifiedAt()).isNull();
    }

    @Test
    void withRetryCountShouldPreserveCapacityWaitFields() {
        Instant startedAt = Instant.parse("2026-07-07T10:00:00Z");
        Instant lastNotifiedAt = Instant.parse("2026-07-07T10:00:05Z");
        MqMessageIdentity original = new MqMessageIdentity(
                "event-1", "doc-1", "trace-1", "agent.run", "session-1",
                "chat.direct", "agent.run", Instant.parse("2026-07-07T09:00:00Z"),
                0, startedAt, lastNotifiedAt
        );

        MqMessageIdentity retried = original.withRetryCount(1);

        assertThat(retried.retryCount()).isEqualTo(1);
        // Capacity wait fields survive a retry-count increment.
        assertThat(retried.capacityWaitStartedAt()).isEqualTo(startedAt);
        assertThat(retried.capacityWaitLastNotifiedAt()).isEqualTo(lastNotifiedAt);
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
