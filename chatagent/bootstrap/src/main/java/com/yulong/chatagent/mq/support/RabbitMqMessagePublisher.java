package com.yulong.chatagent.mq.support;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 发布器封装。
 *
 * 这里不只是 rabbitTemplate.send，而是强制等待 publisher confirm：
 * 1. broker ack：认为消息已经被 RabbitMQ 接收，可以把 outbox 标记 SENT；
 * 2. broker nack / returned / timeout：认为投递不可靠，交给 outbox retry。
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class RabbitMqMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ChatAgentMqProperties properties;

    public RabbitMqMessagePublisher(RabbitTemplate rabbitTemplate, ChatAgentMqProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public void publish(String exchange, String routingKey, Message message, String correlationId) {
        try {
            // correlationId 用于 publisher confirm 回调关联一次具体投递；这里通常传 outboxId 或 retry correlation。
            CorrelationData correlationData = new CorrelationData(correlationId);
            rabbitTemplate.send(exchange, routingKey, message, correlationData);
            // 阻塞等待 confirm，换取发布语义更清晰：调用返回即代表 broker 已确认。
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(properties.getOutbox().getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("RabbitMQ publish not acknowledged: " + confirm.getReason()) {
                };
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                // returned 表示 exchange 收到了，但根据 routingKey 找不到可路由队列，也不能算成功。
                throw new AmqpException("RabbitMQ message returned: " + returned.getReplyText()) {
                };
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for RabbitMQ confirm", e) {
            };
        } catch (ExecutionException e) {
            throw new AmqpException("RabbitMQ publish failed", e.getCause()) {
            };
        } catch (TimeoutException e) {
            throw new AmqpException("Timed out waiting for RabbitMQ confirm", e) {
            };
        }
    }
}
