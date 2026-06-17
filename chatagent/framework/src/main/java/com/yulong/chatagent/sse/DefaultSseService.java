package com.yulong.chatagent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
@RequiredArgsConstructor
/**
 * Default memory-backed SSE implementation with Redis broadcasting capability.
 */
public class DefaultSseService implements SseService {

    private static final String REDIS_CHANNEL = "chatagent:sse:broadcast";
    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ConcurrentMap<String, SseEmitterSender> clients = new ConcurrentHashMap<>();
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String streamKey) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseEmitterSender sender = new SseEmitterSender(emitter);
        // Replacing the existing sender for the same key lets reconnecting
        // clients take over the stream cleanly.
        clients.put(streamKey, sender);
        sender.sendEvent("init", "connected");

        emitter.onCompletion(() -> clients.remove(streamKey, sender));
        emitter.onTimeout(() -> {
            clients.remove(streamKey, sender);
            sender.complete();
        });
        emitter.onError(error -> {
            clients.remove(streamKey, sender);
            sender.fail(error);
        });

        return emitter;
    }

    @Override
    public void publish(String streamKey, Object message) {
        try {
            BroadcastPayload payload = new BroadcastPayload(streamKey, message);
            stringRedisTemplate.convertAndSend(REDIS_CHANNEL, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to broadcast SSE message for streamKey={}", streamKey, e);
        }
    }

    @Override
    public void deliverLocal(String streamKey, Object message) {
        SseEmitterSender sender = clients.get(streamKey);
        if (sender == null) {
            return; // Not on this node, silently ignore.
        }

        try {
            sender.sendEvent("message", message);
        } catch (Exception e) {
            // Remove broken emitters immediately so later sends do not keep failing.
            clients.remove(streamKey, sender);
            log.warn("SSE connection lost during local delivery: streamKey={}", streamKey, e);
        }
    }

    /**
     * Redis broadcast envelope carrying the target stream key and the message to
     * deliver on every node that owns a matching local emitter.
     */
    public record BroadcastPayload(String streamKey, Object message) {
    }
}
