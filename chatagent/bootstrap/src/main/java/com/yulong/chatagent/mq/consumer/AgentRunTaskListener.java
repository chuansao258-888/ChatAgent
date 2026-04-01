package com.yulong.chatagent.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.conversation.event.ChatEventProcessor;
import com.yulong.chatagent.exception.ClientException;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * MQ-backed consumer for the staged {@code agent.run} migration.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "chatagent.mq", name = {"enabled", "dispatchers.agent-run-enabled"}, havingValue = "true")
public class AgentRunTaskListener extends AbstractRetryingMqConsumer<AgentRunTaskPayload> {

    private final ObjectMapper objectMapper;
    private final ChatAgentMqProperties properties;
    private final ChatEventProcessor chatEventProcessor;

    public AgentRunTaskListener(ObjectMapper objectMapper,
                                ChatAgentMqProperties properties,
                                RabbitMqMessagePublisher rabbitMqMessagePublisher,
                                DistributedLockManager distributedLockManager,
                                LockWatchdog lockWatchdog,
                                ChatEventProcessor chatEventProcessor) {
        super(properties, rabbitMqMessagePublisher, distributedLockManager, lockWatchdog);
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatEventProcessor = chatEventProcessor;
    }

    @RabbitListener(
            queues = "${chatagent.mq.queues.chat-agent-dispatch:chat.agent.dispatch}",
            containerFactory = "agentRunListenerContainerFactory"
    )
    public void handle(Message message, Channel channel) throws IOException {
        consume(message, channel);
    }

    @Override
    protected String retryExchange() {
        return properties.getExchanges().getRetryDirect();
    }

    @Override
    protected String retryRoutingKey() {
        return properties.getRoutingKeys().getRetryAgent();
    }

    @Override
    protected int maxRetryCount() {
        return 3;
    }

    @Override
    protected boolean isRetryable(Exception exception) {
        return !(exception instanceof IllegalArgumentException || exception instanceof ClientException);
    }

    @Override
    protected AgentRunTaskPayload deserializePayload(Message message) throws Exception {
        return objectMapper.readValue(message.getBody(), AgentRunTaskPayload.class);
    }

    @Override
    protected void processTask(AgentRunTaskPayload payload, MqMessageIdentity identity) {
        ChatEvent event = payload.toChatEvent();
        
        // Rollback any partial output if this is a retry OR a forced rollback (e.g. from DLQ replay)
        if (identity.retryCount() > 0 || payload.forceRollback()) {
            chatEventProcessor.rollbackTurn(event.getSessionId(), event.getTurnId());
        }
        
        chatEventProcessor.process(event);
        log.info("Agent run task processed: eventId={}, turnId={}, sessionId={}",
                identity.eventId(), event.getTurnId(), event.getSessionId());
    }

    @Override
    protected void onRetriesExhausted(AgentRunTaskPayload payload, MqMessageIdentity identity, Exception exception) {
        publishFailureIfPossible(payload, identity, exception);
    }

    @Override
    protected void onTerminalFailure(AgentRunTaskPayload payload, MqMessageIdentity identity, Exception exception) {
        publishFailureIfPossible(payload, identity, exception);
    }

    private void publishFailureIfPossible(AgentRunTaskPayload payload,
                                          MqMessageIdentity identity,
                                          Exception exception) {
        if (payload == null) {
            log.warn("Skipping fallback assistant message because agent.run payload could not be deserialized: eventId={}",
                    identity.eventId());
            return;
        }
        chatEventProcessor.publishFailure(payload.toChatEvent(), exception);
    }
}
