package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed clarification-state store.
 */
@Component
@Slf4j
public class RedisPendingIntentResolutionStore implements PendingIntentResolutionStore {

    private static final String KEY_PREFIX = "chatagent:intent:pending:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisPendingIntentResolutionStore(StringRedisTemplate stringRedisTemplate,
                                             ObjectMapper objectMapper,
                                             @Value("${chatagent.intent.pending-ttl-minutes:5}") long pendingTtlMinutes) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(Math.max(pendingTtlMinutes, 1L));
    }

    @Override
    public PendingIntentResolution get(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String payload = stringRedisTemplate.opsForValue().get(key(sessionId));
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            PendingIntentResolution pending = objectMapper.readValue(payload, PendingIntentResolution.class);
            if (pending.getExpiresAt() != null && pending.getExpiresAt().isBefore(Instant.now())) {
                delete(sessionId);
                return null;
            }
            return pending;
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize pending intent resolution: sessionId={}, error={}", sessionId, e.getMessage());
            delete(sessionId);
            return null;
        }
    }

    @Override
    public void save(PendingIntentResolution pendingIntentResolution) {
        if (pendingIntentResolution == null || !StringUtils.hasText(pendingIntentResolution.getSessionId())) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        pendingIntentResolution.setExpiresAt(expiresAt);
        try {
            stringRedisTemplate.opsForValue().set(
                    key(pendingIntentResolution.getSessionId()),
                    objectMapper.writeValueAsString(pendingIntentResolution),
                    Duration.between(now, expiresAt)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize pending intent resolution", e);
        }
    }

    @Override
    public void delete(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        stringRedisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
