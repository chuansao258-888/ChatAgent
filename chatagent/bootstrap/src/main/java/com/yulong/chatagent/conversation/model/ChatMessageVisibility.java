package com.yulong.chatagent.conversation.model;

import com.yulong.chatagent.support.dto.ChatMessageDTO;

/** Shared persisted-message visibility rule used by memory readers. */
public final class ChatMessageVisibility {
    private ChatMessageVisibility() {
    }

    public static boolean isInternal(ChatMessageDTO message) {
        return message != null
                && message.getMetadata() != null
                && Boolean.TRUE.equals(message.getMetadata().getInternal());
    }
}
