package com.yulong.chatagent.mq.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Polls the transactional outbox and publishes claimed rows to RabbitMQ with confirm semantics.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class OutboxPollingPublisher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OutboxRecordService outboxRecordService;
    private final RabbitMqMessagePublisher rabbitMqMessagePublisher;
    private final ObjectMapper objectMapper;
    private final ChatAgentMqProperties properties;
    private final String claimedBy = ManagementFactory.getRuntimeMXBean().getName();

    public OutboxPollingPublisher(OutboxRecordService outboxRecordService,
                                  RabbitMqMessagePublisher rabbitMqMessagePublisher,
                                  ObjectMapper objectMapper,
                                  ChatAgentMqProperties properties) {
        this.outboxRecordService = outboxRecordService;
        this.rabbitMqMessagePublisher = rabbitMqMessagePublisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${chatagent.mq.outbox.poll-interval-ms:2000}")
    public void publishDueRows() {
        LocalDateTime now = LocalDateTime.now();
        for (MqOutbox outbox : outboxRecordService.claimBatch(claimedBy, now)) {
            try {
                rabbitMqMessagePublisher.publish(
                        outbox.getExchange(),
                        outbox.getRoutingKey(),
                        buildMessage(outbox),
                        outbox.getId()
                );
                outboxRecordService.markSent(outbox);
                log.info("Outbox row published successfully: id={}, exchange={}, routingKey={}",
                        outbox.getId(), outbox.getExchange(), outbox.getRoutingKey());
            } catch (Exception e) {
                log.warn("Outbox publish failed: id={}, exchange={}, routingKey={}, error={}",
                        outbox.getId(), outbox.getExchange(), outbox.getRoutingKey(), e.getMessage());
                outboxRecordService.markPublishFailure(outbox, abbreviateError(e), LocalDateTime.now());
            }
        }
    }

    @Scheduled(fixedDelayString = "${chatagent.mq.outbox.cleanup-interval-ms:86400000}")
    public void cleanupSentRows() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getOutbox().getCleanupRetentionDays());
        int deleted = outboxRecordService.cleanupOlderSentRows(cutoff);
        if (deleted > 0) {
            log.info("Outbox cleanup removed sent rows: count={}, cutoff={}", deleted, cutoff);
        }
    }

    private Message buildMessage(MqOutbox outbox) throws Exception {
        Map<String, Object> headers = objectMapper.readValue(outbox.getHeaders(), MAP_TYPE);
        // Validate the persisted header contract before we hand the payload back to RabbitMQ.
        MqMessageHeaders.fromMap(headers);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        headers.forEach(properties::setHeader);
        return MessageBuilder.withBody(outbox.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(properties)
                .build();
    }

    private String abbreviateError(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.isBlank()) {
            return e == null ? "Unknown publish error" : e.getClass().getSimpleName();
        }
        return message.length() <= 800 ? message : message.substring(0, 800);
    }
}
