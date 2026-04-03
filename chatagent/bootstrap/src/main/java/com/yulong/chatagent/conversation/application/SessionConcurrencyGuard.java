package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.exception.SessionConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Guards the synchronous chat entrypoint from overlapping requests on the same session.
 */
@Component
@Slf4j
public class SessionConcurrencyGuard {

    private static final String KEY_PREFIX = "chatagent:conversation:session-guard:";
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = buildCompareAndDeleteScript();

    private final StringRedisTemplate stringRedisTemplate;
    private final SessionGuardProperties properties;

    public SessionConcurrencyGuard(StringRedisTemplate stringRedisTemplate,
                                   SessionGuardProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    public SessionLock acquire(String sessionId) {
        if (!properties.isEnabled() || !StringUtils.hasText(sessionId)) {
            return noopLock();
        }

        String key = key(sessionId);
        String token = UUID.randomUUID().toString();
        try {
            ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
            Boolean acquired = valueOperations.setIfAbsent(
                    key,
                    token,
                    Duration.ofMillis(properties.getTtlMs())
            );
            if (Boolean.TRUE.equals(acquired)) {
                return new SessionLock(key, token);
            }
            throw new SessionConflictException("Another request is already starting a turn for this session");
        } catch (SessionConflictException e) {
            throw e;
        } catch (Exception e) {
            if (properties.isFailOpen()) {
                log.warn("Session entry guard failed open for sessionId={}: {}", sessionId, e.getMessage());
                return noopLock();
            }
            throw e;
        }
    }

    private SessionLock noopLock() {
        return new SessionLock(null, null);
    }

    private boolean release(String key, String token) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(token)) {
            return false;
        }
        Long deleted = stringRedisTemplate.execute(COMPARE_AND_DELETE_SCRIPT, List.of(key), token);
        return Long.valueOf(1L).equals(deleted);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId.trim();
    }

    private static DefaultRedisScript<Long> buildCompareAndDeleteScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
                """);
        script.setResultType(Long.class);
        return script;
    }

    public final class SessionLock implements AutoCloseable {
        private final String key;
        private final String token;

        private SessionLock(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            try {
                release(key, token);
            } catch (Exception e) {
                log.warn("Failed to release session entry guard: key={}, error={}", key, e.getMessage());
            }
        }
    }
}
