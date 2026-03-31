package com.yulong.chatagent.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RabbitMQ exchanges, queues, and bindings required for the staged MQ rollout.
 */
@Configuration
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class RabbitMqTopologyConfiguration {

    @Bean
    public Declarables chatAgentMqTopology(ChatAgentMqProperties properties) {
        DirectExchange chatDirectExchange = new DirectExchange(properties.getExchanges().getChatDirect(), true, false);
        DirectExchange retryDirectExchange = new DirectExchange(properties.getExchanges().getRetryDirect(), true, false);
        DirectExchange dlxDirectExchange = new DirectExchange(properties.getExchanges().getDlxDirect(), true, false);

        Queue chatAgentDispatchQueue = buildPrimaryQueue(
                properties.getQueues().getChatAgentDispatch(),
                properties.getExchanges().getDlxDirect(),
                properties.getRoutingKeys().getDeadLetter()
        );
        Queue knowledgeIngestTaskQueue = buildPrimaryQueue(
                properties.getQueues().getKnowledgeIngestTask(),
                properties.getExchanges().getDlxDirect(),
                properties.getRoutingKeys().getDeadLetter()
        );
        Queue retryAgentQueue = buildRetryQueue(
                properties.getQueues().getRetryAgent10s(),
                properties.getRetry().getAgentDelayMs(),
                properties.getExchanges().getChatDirect(),
                properties.getRoutingKeys().getAgentRun()
        );
        Queue retryIngestQueue = buildRetryQueue(
                properties.getQueues().getRetryIngest30s(),
                properties.getRetry().getIngestDelayMs(),
                properties.getExchanges().getChatDirect(),
                properties.getRoutingKeys().getIngestTask()
        );
        Queue chatDlqQueue = QueueBuilder.durable(properties.getQueues().getChatDlq()).build();

        return new Declarables(
                chatDirectExchange,
                retryDirectExchange,
                dlxDirectExchange,
                chatAgentDispatchQueue,
                knowledgeIngestTaskQueue,
                retryAgentQueue,
                retryIngestQueue,
                chatDlqQueue,
                bind(chatAgentDispatchQueue, chatDirectExchange, properties.getRoutingKeys().getAgentRun()),
                bind(knowledgeIngestTaskQueue, chatDirectExchange, properties.getRoutingKeys().getIngestTask()),
                bind(retryAgentQueue, retryDirectExchange, properties.getRoutingKeys().getRetryAgent()),
                bind(retryIngestQueue, retryDirectExchange, properties.getRoutingKeys().getRetryIngest()),
                bind(chatDlqQueue, dlxDirectExchange, properties.getRoutingKeys().getDeadLetter())
        );
    }

    private Queue buildPrimaryQueue(String name, String deadLetterExchange, String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    private Queue buildRetryQueue(String name, int ttlMs, String deadLetterExchange, String deadLetterRoutingKey) {
        return QueueBuilder.durable(name)
                .ttl(ttlMs)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    private Binding bind(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
