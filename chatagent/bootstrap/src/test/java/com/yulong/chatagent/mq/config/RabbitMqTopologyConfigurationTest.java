package com.yulong.chatagent.mq.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqTopologyConfigurationTest {

    private final RabbitMqTopologyConfiguration configuration = new RabbitMqTopologyConfiguration();

    @Test
    void shouldDeclareExpectedStage4a0Topology() {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();

        Declarables declarables = configuration.chatAgentMqTopology(properties);

        assertThat(findDirectExchange(declarables, properties.getExchanges().getChatDirect())).isNotNull();
        assertThat(findDirectExchange(declarables, properties.getExchanges().getRetryDirect())).isNotNull();
        assertThat(findDirectExchange(declarables, properties.getExchanges().getDlxDirect())).isNotNull();

        Queue ingestQueue = findQueue(declarables, properties.getQueues().getKnowledgeIngestTask());
        assertThat(ingestQueue.getArguments())
                .containsEntry("x-dead-letter-exchange", properties.getExchanges().getDlxDirect())
                .containsEntry("x-dead-letter-routing-key", properties.getRoutingKeys().getDeadLetter());

        Queue retryIngestQueue = findQueue(declarables, properties.getQueues().getRetryIngest30s());
        assertThat(retryIngestQueue.getArguments())
                .containsEntry("x-message-ttl", properties.getRetry().getIngestDelayMs())
                .containsEntry("x-dead-letter-exchange", properties.getExchanges().getChatDirect())
                .containsEntry("x-dead-letter-routing-key", properties.getRoutingKeys().getIngestTask());

        Queue retryAgentQueue = findQueue(declarables, properties.getQueues().getRetryAgent10s());
        assertThat(retryAgentQueue.getArguments())
                .containsEntry("x-message-ttl", properties.getRetry().getAgentDelayMs())
                .containsEntry("x-dead-letter-exchange", properties.getExchanges().getChatDirect())
                .containsEntry("x-dead-letter-routing-key", properties.getRoutingKeys().getAgentRun());

        Queue dlq = findQueue(declarables, properties.getQueues().getChatDlq());
        assertThat(dlq.getArguments()).isEmpty();

        Binding ingestBinding = findBinding(declarables, properties.getQueues().getKnowledgeIngestTask());
        assertThat(ingestBinding.getExchange()).isEqualTo(properties.getExchanges().getChatDirect());
        assertThat(ingestBinding.getRoutingKey()).isEqualTo(properties.getRoutingKeys().getIngestTask());
    }

    private DirectExchange findDirectExchange(Declarables declarables, String name) {
        return declarables.getDeclarables().stream()
                .filter(DirectExchange.class::isInstance)
                .map(DirectExchange.class::cast)
                .filter(exchange -> exchange.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Queue findQueue(Declarables declarables, String name) {
        return declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(queue -> queue.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Binding findBinding(Declarables declarables, String destination) {
        return declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filter(binding -> binding.getDestination().equals(destination))
                .findFirst()
                .orElseThrow();
    }
}
