package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

public record BufferedStreamingResponse(
        ChatResponse response,
        List<BufferedStreamEvent> events
) {

    public enum EventType {
        CONTENT,
        THINKING
    }

    public record BufferedStreamEvent(
            EventType type,
            String text
    ) {
    }
}
