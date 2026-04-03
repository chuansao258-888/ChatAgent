package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxEventPublisher;
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.trace.TraceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ-backed dispatch path that writes {@code agent.run} work into the transactional outbox.
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class MqChatEventDispatcher implements ChatEventDispatcher {

    private final OutboxEventPublisher outboxEventPublisher;
    private final ChatAgentMqProperties properties;

    public MqChatEventDispatcher(OutboxEventPublisher outboxEventPublisher, ChatAgentMqProperties properties) {
        this.outboxEventPublisher = outboxEventPublisher;
        this.properties = properties;
    }

    @Override
    public void dispatch(ChatEvent event) {
        MqMessageIdentity identity = MqMessageIdentity.initial(
                "agent.run",
                event.getTurnId(),
                TraceContext.getTraceId(),
                event.getSessionId(),
                properties.getExchanges().getChatDirect(),
                properties.getRoutingKeys().getAgentRun()
        );
        outboxEventPublisher.publish(
                "agent.run",
                properties.getExchanges().getChatDirect(),
                properties.getRoutingKeys().getAgentRun(),
                AgentRunTaskPayload.fromChatEvent(event),
                identity
        );
    }
}
