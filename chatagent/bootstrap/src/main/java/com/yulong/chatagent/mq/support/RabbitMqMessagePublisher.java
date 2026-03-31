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
 * Publishes messages with publisher confirms enabled so callers can distinguish acked sends from lost sends.
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
            CorrelationData correlationData = new CorrelationData(correlationId);
            rabbitTemplate.send(exchange, routingKey, message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(properties.getOutbox().getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("RabbitMQ publish not acknowledged: " + confirm.getReason()) {
                };
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
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
