package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxEventPublisher;
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqChatEventDispatcherTest {

    @Test
    void shouldUseSessionAndTurnAsAgentRunIdempotencyKey() {
        OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        MqChatEventDispatcher dispatcher = new MqChatEventDispatcher(outboxEventPublisher, properties);
        ChatEvent event = new ChatEvent(
                "agent-1",
                "session-1",
                "turn-1",
                "msg-1",
                "hello",
                3,
                null,
                "hello",
                "user-1"
        );

        dispatcher.dispatch(event);

        ArgumentCaptor<AgentRunTaskPayload> payloadCaptor = ArgumentCaptor.forClass(AgentRunTaskPayload.class);
        ArgumentCaptor<MqMessageIdentity> identityCaptor = ArgumentCaptor.forClass(MqMessageIdentity.class);
        verify(outboxEventPublisher).publish(
                eq("agent.run"),
                eq(properties.getExchanges().getChatDirect()),
                eq(properties.getRoutingKeys().getAgentRun()),
                payloadCaptor.capture(),
                identityCaptor.capture()
        );
        assertThat(identityCaptor.getValue().idempotencyKey()).isEqualTo("session-1:turn-1");
        assertThat(identityCaptor.getValue().sessionId()).isEqualTo("session-1");
        assertThat(payloadCaptor.getValue().turnId()).isEqualTo("turn-1");
    }
}
