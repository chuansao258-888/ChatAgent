package com.yulong.chatagent.mq.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Writes an outbox row inside the caller's existing transaction,
 * guaranteeing atomicity between the business write and the MQ intent.
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class OutboxEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void publish(String eventType, String exchange, String routingKey,
                        Object payload, MqMessageIdentity identity) {
        String payloadJson = serialize(payload, "payload");
        String headersJson = serializeHeaders(identity);

        MqOutbox outbox = MqOutbox.builder()
                .id(UUID.randomUUID().toString())
                .eventType(eventType)
                .exchange(exchange)
                .routingKey(routingKey)
                .payload(payloadJson)
                .headers(headersJson)
                .status("PENDING")
                .nextRetryAt(LocalDateTime.now())
                .retryCount(0)
                .version(0)
                .createdAt(LocalDateTime.now())
                .build();

        outboxRepository.insert(outbox);
    }

    private String serializeHeaders(MqMessageIdentity identity) {
        return serialize(MqMessageHeaders.toMap(identity), "headers");
    }

    private String serialize(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox " + label, e);
        }
    }
}
