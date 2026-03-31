package com.yulong.chatagent.mq.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the MQ runtime infrastructure only when the staged RabbitMQ rollout is active.
 */
@Configuration
@EnableRabbit
@EnableScheduling
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class MqRuntimeConfiguration {

    @Bean
    public SimpleRabbitListenerContainerFactory knowledgeIngestListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ChatAgentMqProperties properties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(properties.getConsumers().getIngestConcurrency());
        factory.setMaxConcurrentConsumers(properties.getConsumers().getIngestConcurrency());
        factory.setPrefetchCount(properties.getConsumers().getIngestPrefetch());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory agentRunListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ChatAgentMqProperties properties
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(properties.getConsumers().getAgentConcurrency());
        factory.setMaxConcurrentConsumers(properties.getConsumers().getAgentConcurrency());
        factory.setPrefetchCount(properties.getConsumers().getAgentPrefetch());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
