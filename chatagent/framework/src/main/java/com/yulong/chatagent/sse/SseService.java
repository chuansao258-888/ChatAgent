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
     * Publishes a message to the SSE stream across the entire cluster.
     * This may involve broadcasting via Redis Pub/Sub or similar.
     *
     * @param streamKey logical stream identifier
     * @param message payload to send
     */
    void publish(String streamKey, Object message);

    /**
     * Delivers a message ONLY to the local SSE emitters on this specific node.
     * Usually called by the broadcast listener (e.g., Redis subscriber).
     *
     * @param streamKey logical stream identifier
     * @param message payload to send
     */
    void deliverLocal(String streamKey, Object message);
}
