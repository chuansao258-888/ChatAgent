package com.yulong.chatagent.mq.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事务型 outbox 写入器。
 *
 * 业务代码不要直接 publish RabbitMQ，而是在本地事务里写一条 outbox：
 * 1. 业务数据写入成功 + outbox 写入成功，事务一起提交；
 * 2. 后台 OutboxPollingPublisher 再异步扫描 PENDING 行投递 MQ；
 * 3. 这样能避免“数据库提交了但 MQ 没发出去”或“MQ 发了但数据库回滚”的不一致。
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class OutboxEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final UUID outboxIdNamespace;

    public OutboxEventPublisher(OutboxRepository outboxRepository,
                                ObjectMapper objectMapper,
                                ChatAgentMqProperties properties) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.outboxIdNamespace = UUID.fromString(properties.getOutbox().getIdNamespace());
    }

    public void publish(String eventType, String exchange, String routingKey,
                        Object payload, MqMessageIdentity identity) {
        // payload 是业务参数，headers 是幂等/追踪/重试身份链；两者都要持久化，扫描器才能还原 MQ 消息。
        String payloadJson = serialize(payload, "payload");
        String headersJson = serializeHeaders(identity);

        MqOutbox outbox = MqOutbox.builder()
                // outbox id 用 UUIDv5(eventType + idempotencyKey) 生成，是确定性的。
                // 同一个业务任务重复写 outbox 会得到同一个 id，数据库唯一键即可防重复。
                .id(UuidV5Generator.generate(outboxIdNamespace, eventType + ":" + identity.idempotencyKey()).toString())
                .eventType(eventType)
                .exchange(exchange)
                .routingKey(routingKey)
                .payload(payloadJson)
                .headers(headersJson)
                // PENDING 表示等待扫描发布；CLAIMED/SENT/FAILED 等状态由 OutboxRecordService 管。
                .status("PENDING")
                .nextRetryAt(LocalDateTime.now())
                .retryCount(0)
                .version(0)
                .createdAt(LocalDateTime.now())
                .build();

        int inserted = outboxRepository.insert(outbox);
        if (inserted == 0) {
            // insert = 0 通常说明唯一键冲突：同一个 eventType + idempotencyKey 已经写过 outbox。
            throwDuplicateInsertWarning(eventType, identity);
        }
    }

    private void throwDuplicateInsertWarning(String eventType, MqMessageIdentity identity) {
        org.slf4j.LoggerFactory.getLogger(OutboxEventPublisher.class).warn(
                "Outbox duplicate insert skipped: eventType={}, idempotencyKey={}, eventId={}",
                eventType,
                identity.idempotencyKey(),
                identity.eventId()
        );
    }

    private String serializeHeaders(MqMessageIdentity identity) {
        // header 以对象形式序列化进一列，便于 MQ header 契约扩展，不需要频繁改 outbox 表结构。
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
