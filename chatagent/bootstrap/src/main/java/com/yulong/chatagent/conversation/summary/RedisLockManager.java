package com.yulong.chatagent.conversation.summary;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight Redis-based mutex for session-scoped summary tasks.
 */
@Component
public class RedisLockManager {

    private static final String KEY_PREFIX = "chatagent:memory:lock:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = buildCompareAndDeleteScript();

    private final StringRedisTemplate stringRedisTemplate;

    public RedisLockManager(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public String tryLock(String sessionId) {
        String token = UUID.randomUUID().toString();
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        Boolean acquired = valueOperations.setIfAbsent(key(sessionId), token, DEFAULT_TTL);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public boolean unlock(String sessionId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long deleted = stringRedisTemplate.execute(COMPARE_AND_DELETE_SCRIPT, List.of(key(sessionId)), token);
        return Long.valueOf(1L).equals(deleted);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
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
}
