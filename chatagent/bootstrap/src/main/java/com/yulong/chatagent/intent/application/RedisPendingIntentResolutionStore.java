package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.contract.ClarificationKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;

/**
 * 基于 Redis 的澄清状态存储实现。
 * <p>
 * clarification 不是长期记忆，而是一个很短暂的会话中间态，
 * 因此这里用 Redis 按 session 保存即可，特点是：
 * <ul>
 *     <li>轻量；</li>
 *     <li>天然支持 TTL 过期；</li>
 *     <li>适合同步入口线程和后续多实例部署共享状态。</li>
 * </ul>
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
        // 一个 session 在任意时刻最多只有一个待澄清状态。
        String payload = stringRedisTemplate.opsForValue().get(key(sessionId));
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            PendingIntentResolution pending = objectMapper.readValue(payload, PendingIntentResolution.class);
            normalizeLegacyPayload(pending);
            if (pending.getExpiresAt() != null && pending.getExpiresAt().isBefore(Instant.now())) {
                // 过期后立即清理，避免用户很久之后回复一句“第一个”仍然命中旧澄清。
                delete(sessionId);
                return null;
            }
            return pending;
        } catch (JsonProcessingException e) {
            // 反序列化失败时宁可丢弃旧状态，也不要把损坏的 clarification 上下文继续传下去。
            log.warn("Failed to deserialize pending intent resolution: sessionId={}, reason=malformed_payload", sessionId);
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
        normalizeLegacyPayload(pendingIntentResolution);
        // expiresAt 同时保存在对象里，便于调试和显式判断，不完全依赖 Redis TTL 黑盒。
        pendingIntentResolution.setExpiresAt(expiresAt);
        try {
            // Redis TTL 与对象里的 expiresAt 保持一致，
            // 这样无论是从 Redis 视角还是从业务对象视角，看起来都是同一个过期边界。
            stringRedisTemplate.opsForValue().set(
                    key(pendingIntentResolution.getSessionId()),
                    objectMapper.writeValueAsString(pendingIntentResolution),
                    Duration.between(now, expiresAt)
            );
        } catch (JsonProcessingException e) {
            // 这里直接抛异常，因为 save 失败会导致上层以为“澄清状态已经记住”，
            // 下一轮却无法继续，属于逻辑一致性问题。
            throw new IllegalStateException("Failed to serialize pending intent resolution", e);
        }
    }

    @Override
    public void delete(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        // 删除动作很常见：命中候选、候选失效、过期清理时都会走这里。
        stringRedisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        // key 只按 session 维度区分，因为 clarification 是会话局部中间态，不是用户全局状态。
        // 即使同一个用户同时开多个会话，也不应该共享同一份待澄清候选。
        return KEY_PREFIX + sessionId;
    }

    private void normalizeLegacyPayload(PendingIntentResolution pending) {
        if (pending == null) {
            return;
        }
        if (pending.getClarificationKind() == null) {
            pending.setClarificationKind(ClarificationKind.ROUTE_CHOICE);
        }
        if (pending.getAttemptCount() == null || pending.getAttemptCount() < 0) {
            pending.setAttemptCount(0);
        }
        if ((pending.getCandidateNodeIds() == null || pending.getCandidateNodeIds().isEmpty())
                && pending.getOrderedCandidates() != null) {
            pending.setCandidateNodeIds(pending.getOrderedCandidates().stream()
                    .map(PendingIntentResolution.PendingCandidate::nodeId)
                    .toList());
        }
    }
}
