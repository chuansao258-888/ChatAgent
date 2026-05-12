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
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.AmqpAdmin;
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
 * MQ 运维后台服务。
 *
 * 这不是业务主链，而是给管理员排查/救援用：
 * 1. 查看 outbox 当前 pending/claimed/sent/failed 状态；
 * 2. 查看 retry queue / DLQ 深度；
 * 3. 从 DLQ 拉取消息并 replay 回原始 exchange/routingKey。
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class MqAdminFacadeServiceImpl implements MqAdminFacadeService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String QUEUE_MESSAGE_COUNT = "QUEUE_MESSAGE_COUNT";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AdminAccessService adminAccessService;
    private final OutboxRepository outboxRepository;
    private final AmqpAdmin amqpAdmin;
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
        // 支持按 eventId/idempotencyKey/status 过滤，方便从日志里的身份链反查 outbox。
        List<MqOutbox> records = outboxRepository.findRecent(
                trimToNull(eventId),
                trimToNull(idempotencyKey),
                trimToNull(status),
                normalizedLimit
        );
        return GetMqOutboxRetryResponse.builder()
                // outbox 状态是“投递 RabbitMQ 之前”的状态，不等同于 consumer 处理状态。
                .pendingCount(outboxRepository.countByStatus("PENDING"))
                .claimedCount(outboxRepository.countByStatus("CLAIMED"))
                .sentCount(outboxRepository.countByStatus("SENT"))
                .failedCount(outboxRepository.countByStatus("FAILED"))
                // 队列深度用于判断消息是否堆积：retry 队列深度高说明短期失败多，DLQ 高说明终局失败多。
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
        // 默认把 retryCount 清零，让 replay 后重新拥有完整 consumer retry 机会。
        boolean resetRetryCount = request == null || request.getResetRetryCount() == null
                || request.getResetRetryCount();

        Integer replayed = rabbitTemplate.execute(channel -> {
            DefaultMessagePropertiesConverter converter = new DefaultMessagePropertiesConverter();
            int count = 0;
            while (count < limit) {
                // basicGet 是管理端主动拉取 DLQ；autoAck=false，所以成功 replay 后必须手动 ack 原 DLQ 消息。
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
                    
                    Message replayMessage = buildReplayMessage(message, replayIdentity);
                    
                    // agent.run replay 前会强制标记 forceRollback：
                    // consumer 再处理时会先清理本 turn 已写出的旧 assistant/tool 消息，避免重复输出。
                    if (properties.getRoutingKeys().getAgentRun().equals(replayIdentity.originalRoutingKey())) {
                        AgentRunTaskPayload payload = objectMapper.readValue(message.getBody(), AgentRunTaskPayload.class);
                        AgentRunTaskPayload forcedPayload = payload.withForceRollback(true);
                        replayMessage = MessageBuilder.fromMessage(replayMessage)
                                .withBody(objectMapper.writeValueAsBytes(forcedPayload))
                                .build();
                    }

                    rabbitMqMessagePublisher.publish(
                            // originalExchange/originalRoutingKey 来自初始 identity，确保 replay 回到原主队列。
                            replayIdentity.originalExchange(),
                            replayIdentity.originalRoutingKey(),
                            replayMessage,
                            replayIdentity.eventId() + "-replay-" + (count + 1)
                    );
                    channel.basicAck(deliveryTag, false);
                    count++;
                    log.info("Replayed DLQ message: eventId={}, targetExchange={}, targetRoutingKey={}",
                            replayIdentity.eventId(),
                            replayIdentity.originalExchange(),
                            replayIdentity.originalRoutingKey());
                } catch (Exception e) {
                    // replay 失败时把 DLQ 原消息放回队列，避免运维操作导致消息丢失。
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
        // replay 不重新生成 eventId/idempotencyKey，只调整 retryCount。
        // 这样 Redis task lock 仍能识别它是同一个业务任务。
        return MessageBuilder.fromMessage(originalMessage)
                .setHeader(MqMessageHeaders.RETRY_COUNT, replayIdentity.retryCount())
                .build();
    }

    private GetMqOutboxRetryResponse.OutboxRecord toRecord(MqOutbox outbox) {
        Map<String, Object> headers = parseHeaders(outbox.getHeaders());
        // headers 里保存了真正的 MQ 身份链，outbox 表面字段只保存 exchange/routingKey/status 等投递信息。
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
        Properties queueProperties = amqpAdmin.getQueueProperties(queueName);
        if (queueProperties == null) {
            // 队列不存在或 RabbitMQ 不可见时，后台展示为 0，避免管理接口直接报错。
            return 0L;
        }
        Object value = queueProperties.get(QUEUE_MESSAGE_COUNT);
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
