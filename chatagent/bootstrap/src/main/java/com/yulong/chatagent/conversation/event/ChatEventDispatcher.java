package com.yulong.chatagent.conversation.event;

/**
 * Dispatches one prepared chat turn to either the local async path or the MQ-backed path.
 */
public interface ChatEventDispatcher {

    void dispatch(ChatEvent event);
}
