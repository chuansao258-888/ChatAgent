package com.yulong.chatagent.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Abstraction for server-sent event streams keyed by an application-specific stream ID.
 */
public interface SseService {

    /**
     * Opens or replaces an SSE stream for a given key.
     *
     * @param streamKey logical stream identifier
     * @return configured SSE emitter
     */
    SseEmitter connect(String streamKey);

    /**
     * Sends a message to the SSE stream identified by the key.
     *
     * @param streamKey logical stream identifier
     * @param message payload to send
     */
    void send(String streamKey, Object message);
}
