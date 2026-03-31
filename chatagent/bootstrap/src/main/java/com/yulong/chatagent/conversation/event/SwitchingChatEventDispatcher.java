package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Chooses the chat dispatch path based on the staged MQ rollout switch for {@code agent.run}.
 */
@Component
@Primary
public class SwitchingChatEventDispatcher implements ChatEventDispatcher {

    private final ChatAgentMqProperties properties;
    private final LocalChatEventDispatcher localChatEventDispatcher;
    private final ObjectProvider<MqChatEventDispatcher> mqChatEventDispatcherProvider;

    public SwitchingChatEventDispatcher(ChatAgentMqProperties properties,
                                        LocalChatEventDispatcher localChatEventDispatcher,
                                        ObjectProvider<MqChatEventDispatcher> mqChatEventDispatcherProvider) {
        this.properties = properties;
        this.localChatEventDispatcher = localChatEventDispatcher;
        this.mqChatEventDispatcherProvider = mqChatEventDispatcherProvider;
    }

    @Override
    public void dispatch(ChatEvent event) {
        if (properties.isEnabled() && properties.getDispatchers().isAgentRunEnabled()) {
            MqChatEventDispatcher dispatcher = mqChatEventDispatcherProvider.getIfAvailable();
            if (dispatcher != null) {
                dispatcher.dispatch(event);
                return;
            }
        }
        localChatEventDispatcher.dispatch(event);
    }
}
