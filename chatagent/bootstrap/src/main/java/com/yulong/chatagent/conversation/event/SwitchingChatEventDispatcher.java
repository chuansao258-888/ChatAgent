package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 根据 MQ rollout 开关选择 ChatEvent 派发路径。
 * <p>
 * 当 MQ 和 agent.run dispatcher 都启用时，优先走 outbox + RabbitMQ；
 * 否则回退到本地异步事件，保证功能在无 MQ 环境也能运行。
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
        // 是否走 MQ 由两层条件共同决定：
        // 1. 全局 MQ 是否启用；
        // 2. agent.run 这个具体 dispatcher 是否启用。
        if (properties.isEnabled() && properties.getDispatchers().isAgentRunEnabled()) {
            // ObjectProvider 允许 MQ 相关 bean 在关闭配置时不存在，避免启动期硬依赖。
            MqChatEventDispatcher dispatcher = mqChatEventDispatcherProvider.getIfAvailable();
            if (dispatcher != null) {
                dispatcher.dispatch(event);
                return;
            }
        }
        // 没有 MQ bean 或开关未开时，稳定回退到本地异步路径。
        localChatEventDispatcher.dispatch(event);
    }
}
