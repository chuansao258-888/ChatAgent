package com.yulong.chatagent.conversation.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Legacy in-process dispatch path backed by Spring application events.
 */
@Component
public class LocalChatEventDispatcher implements ChatEventDispatcher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public LocalChatEventDispatcher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void dispatch(ChatEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
