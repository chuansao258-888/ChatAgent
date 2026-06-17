package com.yulong.chatagent.conversation.model;

import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Envelope pushed to clients over the chat SSE stream, describing one event: its {@link Type},
 * a {@link Payload}, and correlation {@link Metadata}.
 */
@Data
@AllArgsConstructor
@Builder
public class SseMessage {

    private Type type;
    private Payload payload;
    private Metadata metadata;

    /**
     * Event payload: the rendered message plus optional status text, completion flag, and turn id.
     */
    @Data
    @AllArgsConstructor
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String statusText;
        private Boolean done;
        private String turnId;
    }

    /**
     * Correlation metadata, primarily the chat-message id.
     */
    @Data
    @AllArgsConstructor
    @Builder
    public static class Metadata {
        private String chatMessageId;
    }

    /**
     * Kinds of events streamed to the client during an agent turn.
     */
    public enum Type {
        AI_GENERATED_CONTENT,
        AI_PLANNING,
        AI_THINKING,
        AI_EXECUTING,
        AI_ERROR,
        AI_DONE,
        TURN_ROLLBACK
    }
}
