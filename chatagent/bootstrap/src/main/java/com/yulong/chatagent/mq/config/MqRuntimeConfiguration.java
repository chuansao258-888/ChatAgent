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
 * MQ 运行时配置。
 *
 * 这里的 containerFactory 会被 @RabbitListener(containerFactory = "...") 引用，
 * 用来控制每类消费者的 ack 模式、并发线程数、prefetch 等消费行为。
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
        // MANUAL ack：业务代码明确 basicAck/basicNack/basicReject，避免异常时框架自动 ack 掉消息。
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // ingestion 和 agent.run 分开配置并发，避免文档入库任务挤占聊天任务消费线程。
        factory.setConcurrentConsumers(properties.getConsumers().getIngestConcurrency());
        factory.setMaxConcurrentConsumers(properties.getConsumers().getIngestConcurrency());
        // prefetch 控制每个 consumer 线程最多预取多少条未 ack 消息。
        factory.setPrefetchCount(properties.getConsumers().getIngestPrefetch());
        // 代码自己决定何时重试/进 DLQ，不让容器对 reject 的消息默认 requeue。
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
        // agent.run 可能耗时更长，必须手动 ack，确保 Agent 真正完成后才确认消息。
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(properties.getConsumers().getAgentConcurrency());
        factory.setMaxConcurrentConsumers(properties.getConsumers().getAgentConcurrency());
        factory.setPrefetchCount(properties.getConsumers().getAgentPrefetch());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
