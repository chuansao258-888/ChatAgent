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
 * RabbitMQ 拓扑声明。
 *
 * 这里集中创建 exchange、queue、binding：
 * 1. chat.direct：主业务交换机，agent.run / knowledge.ingest 都从这里进入主队列；
 * 2. chat.retry.direct：重试交换机，消息先进入带 TTL 的 retry queue；
 * 3. chat.dlx.direct：死信交换机，终局失败或 reject(false) 后进入 DLQ。
 */
@Configuration
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class RabbitMqTopologyConfiguration {

    @Bean
    public Declarables chatAgentMqTopology(ChatAgentMqProperties properties) {
        // direct exchange 的特点是：routingKey 必须精确匹配 binding key，消息才会进对应队列。
        DirectExchange chatDirectExchange = new DirectExchange(properties.getExchanges().getChatDirect(), true, false);
        DirectExchange retryDirectExchange = new DirectExchange(properties.getExchanges().getRetryDirect(), true, false);
        DirectExchange dlxDirectExchange = new DirectExchange(properties.getExchanges().getDlxDirect(), true, false);

        // 主队列绑定 DLX：消费端 basicReject(..., false) 或队列级死信条件触发时，会去死信交换机。
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
        // 重试队列不被业务 consumer 直接消费；消息在这里等待 ttl 到期后自动死信回主 exchange/routingKey。
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
        // durable 表示 RabbitMQ 重启后队列元数据仍存在。
        return QueueBuilder.durable(name)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    private Queue buildRetryQueue(String name, int ttlMs, String deadLetterExchange, String deadLetterRoutingKey) {
        // retry queue 的核心是 ttl + deadLetterExchange：
        // 消息过期后不是丢弃，而是重新投递到主业务 exchange，实现延迟重试。
        return QueueBuilder.durable(name)
                .ttl(ttlMs)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)
                .build();
    }

    private Binding bind(Queue queue, DirectExchange exchange, String routingKey) {
        // binding 决定 exchange 收到某个 routingKey 时，应该把消息路由到哪个 queue。
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
