package com.yulong.chatagent.conversation.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 本地进程内派发路径。
 * <p>
 * 它通过 Spring ApplicationEvent + @Async 执行 Agent，适合本地开发或不启用 MQ 的部署。
 */
@Component
public class LocalChatEventDispatcher implements ChatEventDispatcher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public LocalChatEventDispatcher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void dispatch(ChatEvent event) {
        // 注意：本地路径也不是同步执行 ChatAgent。
        // 它只是把事件发到 Spring ApplicationEvent，总体仍会由 @Async 监听器异步接手。
        applicationEventPublisher.publishEvent(event);
    }
}
