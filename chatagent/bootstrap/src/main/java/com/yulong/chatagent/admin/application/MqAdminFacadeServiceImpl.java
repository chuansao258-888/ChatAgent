package com.yulong.chatagent.admin.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;
import com.yulong.chatagent.admin.model.request.ReplayDlqMessagesRequest;
import com.yulong.chatagent.admin.model.response.GetMqOutboxRetryResponse;
import com.yulong.chatagent.admin.model.response.ReplayDlqMessagesResponse;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxRepository;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Small operational surface for MQ rollout support until broader observability tooling lands.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class MqAdminFacadeServiceImpl implements MqAdminFacadeService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AdminAccessService adminAccessService;
    private final OutboxRepository outboxRepository;
    private final RabbitAdmin rabbitAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqMessagePublisher rabbitMqMessagePublisher;
    private final ObjectMapper objectMapper;
    private final ChatAgentMqProperties properties;

    @Override
    public GetMqOutboxRetryResponse getOutboxRetryState(String eventId,
                                                        String idempotencyKey,
                                                        String status,
                                                        Integer limit) {
        adminAccessService.requireAdmin();
        int normalizedLimit = normalizeLimit(limit, DEFAULT_LIMIT);
        List<MqOutbox> records = outboxRepository.findRecent(
                trimToNull(eventId),
                trimToNull(idempotencyKey),
                trimToNull(status),
                normalizedLimit
        );
        return GetMqOutboxRetryResponse.builder()
                .pendingCount(outboxRepository.countByStatus("PENDING"))
                .claimedCount(outboxRepository.countByStatus("CLAIMED"))
                .sentCount(outboxRepository.countByStatus("SENT"))
                .failedCount(outboxRepository.countByStatus("FAILED"))
                .retryAgentQueueDepth(queueDepth(properties.getQueues().getRetryAgent10s()))
                .retryIngestQueueDepth(queueDepth(properties.getQueues().getRetryIngest30s()))
                .dlqQueueDepth(queueDepth(properties.getQueues().getChatDlq()))
                .records(records.stream().map(this::toRecord).toList())
                .build();
    }

    @Override
    public ReplayDlqMessagesResponse replayDlqMessages(ReplayDlqMessagesRequest request) {
        adminAccessService.requireAdmin();
        int limit = normalizeLimit(request == null ? null : request.getLimit(), 10);
        boolean resetRetryCount = request == null || request.getResetRetryCount() == null
                || request.getResetRetryCount();

        Integer replayed = rabbitTemplate.execute(channel -> {
            DefaultMessagePropertiesConverter converter = new DefaultMessagePropertiesConverter();
            int count = 0;
            while (count < limit) {
                GetResponse response = channel.basicGet(properties.getQueues().getChatDlq(), false);
                if (response == null) {
                    break;
                }
                Message message = new Message(
                        response.getBody(),
                        converter.toMessageProperties(
                                response.getProps(),
                                response.getEnvelope(),
                                StandardCharsets.UTF_8.name()
                        )
                );
                long deliveryTag = response.getEnvelope().getDeliveryTag();
                try {
                    MqMessageIdentity identity = MqMessageHeaders.read(message.getMessageProperties());
                    MqMessageIdentity replayIdentity = resetRetryCount ? identity.withRetryCount(0) : identity;
                    rabbitMqMessagePublisher.publish(
                            replayIdentity.originalExchange(),
                            replayIdentity.originalRoutingKey(),
                            buildReplayMessage(message, replayIdentity),
                            replayIdentity.eventId() + "-replay-" + (count + 1)
                    );
                    channel.basicAck(deliveryTag, false);
                    count++;
                    log.info("Replayed DLQ message: eventId={}, targetExchange={}, targetRoutingKey={}",
                            replayIdentity.eventId(),
                            replayIdentity.originalExchange(),
                            replayIdentity.originalRoutingKey());
                } catch (Exception e) {
                    channel.basicNack(deliveryTag, false, true);
                    throw new BizException("DLQ replay failed after " + count + " messages: " + e.getMessage());
                }
            }
            return count;
        });

        return ReplayDlqMessagesResponse.builder()
                .replayedCount(replayed == null ? 0 : replayed)
                .remainingDlqDepth(queueDepth(properties.getQueues().getChatDlq()))
                .resetRetryCount(resetRetryCount)
                .build();
    }

    private Message buildReplayMessage(Message originalMessage, MqMessageIdentity replayIdentity) {
        return MessageBuilder.fromMessage(originalMessage)
                .setHeader(MqMessageHeaders.RETRY_COUNT, replayIdentity.retryCount())
                .build();
    }

    private GetMqOutboxRetryResponse.OutboxRecord toRecord(MqOutbox outbox) {
        Map<String, Object> headers = parseHeaders(outbox.getHeaders());
        MqMessageIdentity identity = MqMessageHeaders.fromMap(headers);
        return GetMqOutboxRetryResponse.OutboxRecord.builder()
                .id(outbox.getId())
                .eventType(outbox.getEventType())
                .exchange(outbox.getExchange())
                .routingKey(outbox.getRoutingKey())
                .status(outbox.getStatus())
                .eventId(identity.eventId())
                .idempotencyKey(identity.idempotencyKey())
                .retryCount(outbox.getRetryCount())
                .nextRetryAt(outbox.getNextRetryAt())
                .lastError(outbox.getLastError())
                .createdAt(outbox.getCreatedAt())
                .build();
    }

    private Map<String, Object> parseHeaders(String headersJson) {
        try {
            return objectMapper.readValue(headersJson, MAP_TYPE);
        } catch (IOException e) {
            throw new BizException("Failed to parse MQ outbox headers");
        }
    }

    private long queueDepth(String queueName) {
        Properties queueProperties = rabbitAdmin.getQueueProperties(queueName);
        if (queueProperties == null) {
            return 0L;
        }
        Object value = queueProperties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private int normalizeLimit(Integer value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(1, Math.min(value, MAX_LIMIT));
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
