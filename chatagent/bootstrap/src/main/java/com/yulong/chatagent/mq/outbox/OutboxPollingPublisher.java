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
 * Outbox 扫描发布器。
 *
 * 它是“数据库 outbox”和“RabbitMQ”之间的桥：
 * 1. 定时 claim 一批到期的 PENDING/过期 CLAIMED 记录；
 * 2. 根据 outbox.payload + outbox.headers 还原 MQ Message；
 * 3. 使用 publisher confirm 确认 RabbitMQ 真正接收；
 * 4. 成功标记 SENT，失败则更新 retryCount/nextRetryAt。
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
        // claimBatch 会把记录从 PENDING 改成 CLAIMED，并写 claimedBy，避免多个实例重复发布同一行。
        for (MqOutbox outbox : outboxRecordService.claimBatch(claimedBy, now)) {
            try {
                rabbitMqMessagePublisher.publish(
                        outbox.getExchange(),
                        outbox.getRoutingKey(),
                        buildMessage(outbox),
                        outbox.getId()
                );
                if (!outboxRecordService.markSent(outbox)) {
                    // publish 已经成功，但 markSent 发生乐观锁冲突。
                    // 此时不能简单重试发布，否则可能重复投递；所以异步把该行标记为 DISCARDED。
                    log.error("Outbox markSent conflict detected after successful publish, scheduling discard: id={}", outbox.getId());
                    outboxRecordService.scheduleDiscardedConflict(outbox);
                    continue;
                }
                log.info("Outbox row published successfully: id={}, exchange={}, routingKey={}",
                        outbox.getId(), outbox.getExchange(), outbox.getRoutingKey());
            } catch (Exception e) {
                // 只有“没确认投递成功”的异常才走 outbox publish retry。
                // 如果 RabbitMQ 已经 ack，就不应该把同一条 outbox 再发布一次。
                log.warn("Outbox publish failed: id={}, exchange={}, routingKey={}, error={}",
                        outbox.getId(), outbox.getExchange(), outbox.getRoutingKey(), e.getMessage());
                outboxRecordService.markPublishFailure(outbox, abbreviateError(e), LocalDateTime.now());
            }
        }
    }

    @Scheduled(fixedDelayString = "${chatagent.mq.outbox.cleanup-interval-ms:86400000}")
    public void cleanupSentRows() {
        // SENT 行只用于审计/排查，保留一段时间后可以清理，避免 outbox 表无限增长。
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getOutbox().getCleanupRetentionDays());
        int deleted = outboxRecordService.cleanupOlderSentRows(cutoff);
        if (deleted > 0) {
            log.info("Outbox cleanup removed sent rows: count={}, cutoff={}", deleted, cutoff);
        }
    }

    private Message buildMessage(MqOutbox outbox) throws Exception {
        Map<String, Object> headers = objectMapper.readValue(outbox.getHeaders(), MAP_TYPE);
        // 投递前校验 header 契约，提前发现坏数据；否则 consumer 端才报错会更难追。
        MqMessageHeaders.fromMap(headers);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        // headers 会成为 RabbitMQ MessageProperties 的 header，consumer 通过它恢复 MqMessageIdentity。
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
