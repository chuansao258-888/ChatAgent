package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SwitchingChatEventDispatcherTest {

    @Test
    void shouldUseLocalDispatcherWhenAgentRunSwitchIsDisabled() {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        properties.setEnabled(true);
        properties.getDispatchers().setAgentRunEnabled(false);
        LocalChatEventDispatcher localDispatcher = mock(LocalChatEventDispatcher.class);
        MqChatEventDispatcher mqDispatcher = mock(MqChatEventDispatcher.class);
        ChatEvent event = sampleEvent();

        SwitchingChatEventDispatcher dispatcher = new SwitchingChatEventDispatcher(
                properties,
                localDispatcher,
                providerOf(mqDispatcher)
        );

        dispatcher.dispatch(event);

        verify(localDispatcher).dispatch(event);
        verify(mqDispatcher, never()).dispatch(event);
    }

    @Test
    void shouldUseMqDispatcherWhenMqAndAgentRunSwitchAreEnabled() {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        properties.setEnabled(true);
        properties.getDispatchers().setAgentRunEnabled(true);
        LocalChatEventDispatcher localDispatcher = mock(LocalChatEventDispatcher.class);
        MqChatEventDispatcher mqDispatcher = mock(MqChatEventDispatcher.class);
        ChatEvent event = sampleEvent();

        SwitchingChatEventDispatcher dispatcher = new SwitchingChatEventDispatcher(
                properties,
                localDispatcher,
                providerOf(mqDispatcher)
        );

        dispatcher.dispatch(event);

        verify(mqDispatcher).dispatch(event);
        verify(localDispatcher, never()).dispatch(event);
    }

    private ObjectProvider<MqChatEventDispatcher> providerOf(MqChatEventDispatcher mqDispatcher) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("mqChatEventDispatcher", mqDispatcher));
        return beanFactory.getBeanProvider(MqChatEventDispatcher.class);
    }

    private ChatEvent sampleEvent() {
        return new ChatEvent("agent-1", "session-1", "turn-1", "msg-1", "hello", 3, null, "hello");
    }
}
