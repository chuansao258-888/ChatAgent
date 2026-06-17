package com.yulong.chatagent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Configures Redis Pub/Sub to distribute SSE messages across nodes in a cluster.
 */
@Configuration
@Slf4j
public class RedisSseConfiguration {

    private static final String REDIS_CHANNEL = "chatagent:sse:broadcast";

    /**
     * Registers the SSE broadcast listener against the shared Redis channel so
     * messages published on any node are received cluster-wide.
     *
     * @param connectionFactory          Redis connection factory
     * @param sseMessageListenerAdapter  listener adapter bound to the SSE receiver
     * @return configured Redis message-listener container
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter sseMessageListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(sseMessageListenerAdapter, new ChannelTopic(REDIS_CHANNEL));
        return container;
    }

    /**
     * Wraps the {@link SseMessageReceiver} so Redis messages are dispatched to
     * its {@code receive} method.
     *
     * @param receiver SSE broadcast receiver
     * @return listener adapter bound to the receiver
     */
    @Bean
    public MessageListenerAdapter sseMessageListenerAdapter(
            SseMessageReceiver receiver) {
        // We use a dedicated receiver instead of calling SseService directly
        // to handle deserialization and node-local delivery logic cleanly.
        return new MessageListenerAdapter(receiver, "receive");
    }

    /**
     * Creates the broadcast receiver that deserializes payloads and forwards
     * them to local SSE emitters.
     *
     * @param sseService    local SSE delivery service
     * @param objectMapper  JSON mapper for broadcast payloads
     * @return SSE message receiver
     */
    @Bean
    public SseMessageReceiver sseMessageReceiver(SseService sseService, ObjectMapper objectMapper) {
        return new SseMessageReceiver(sseService, objectMapper);
    }

    /**
     * Redis Pub/Sub listener that deserializes broadcast payloads and delivers
     * them to the local SSE emitters that own the matching stream key.
     */
    @Slf4j
    @RequiredArgsConstructor
    public static class SseMessageReceiver {
        private final SseService sseService;
        private final ObjectMapper objectMapper;

        /**
         * Handles a raw broadcast message by parsing it and triggering local
         * delivery when the owning emitter lives on this node.
         *
         * @param message serialized {@link DefaultSseService.BroadcastPayload}
         */
        public void receive(String message) {
            try {
                // The broadcast payload is serialized as a JSON string in DefaultSseService.
                DefaultSseService.BroadcastPayload payload = objectMapper.readValue(
                        message, DefaultSseService.BroadcastPayload.class);

                if (payload != null && payload.streamKey() != null) {
                    sseService.deliverLocal(payload.streamKey(), payload.message());
                }
            } catch (Exception e) {
                log.error("Failed to parse and deliver broadcast SSE message: {}", message, e);
            }
        }
    }
}
