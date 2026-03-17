package com.yulong.chatagent.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
public class DefaultSseService implements SseService {

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ConcurrentMap<String, SseEmitterSender> clients = new ConcurrentHashMap<>();

    @Override
    public SseEmitter connect(String streamKey) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseEmitterSender sender = new SseEmitterSender(emitter);
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
    public void send(String streamKey, Object message) {
        SseEmitterSender sender = clients.get(streamKey);
        if (sender == null) {
            log.warn("No SSE client found for chatSessionId={}", streamKey);
            return;
        }

        try {
            sender.sendEvent("message", message);
        } catch (Exception e) {
            clients.remove(streamKey, sender);
            log.warn("SSE connection lost: chatSessionId={}", streamKey, e);
        }
    }
}
