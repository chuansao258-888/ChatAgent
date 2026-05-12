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
 * 会话级同步入口保护器。
 * <p>
 * 它只保护“开始一个 turn”的同步入口，不负责串行化后续完整 Agent 运行周期。
 * 设计目标是：
 * <ul>
 *     <li>防止同一个 session 在同一时刻并发进入两次 turn 编排；</li>
 *     <li>避免用户双击发送、前端重试或网络抖动带来的重入；</li>
 *     <li>保护消息顺序、L1 记忆窗口和 SSE 前端状态不被交叉污染。</li>
 * </ul>
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
        // 关闭开关或 sessionId 为空时退化为空锁，调用方仍然可以用 try-with-resources 统一处理。
        if (!properties.isEnabled() || !StringUtils.hasText(sessionId)) {
            return noopLock();
        }

        String key = key(sessionId);
        String token = UUID.randomUUID().toString();
        try {
            ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
            // Redis 原子 SET NX PX：
            // 1. 只有 key 不存在才设置成功；
            // 2. value 使用随机 token，后续释放锁时要做 compare-and-delete；
            // 3. TTL 防止进程异常退出后锁永久残留。
            Boolean acquired = valueOperations.setIfAbsent(
                    key,
                    token,
                    Duration.ofMillis(properties.getTtlMs())
            );
            if (Boolean.TRUE.equals(acquired)) {
                return new SessionLock(key, token);
            }
            // 这里直接抛冲突异常，让上层把“同 session 正在开始另一轮 turn”暴露给调用方。
            throw new SessionConflictException("Another request is already starting a turn for this session");
        } catch (SessionConflictException e) {
            throw e;
        } catch (Exception e) {
            if (properties.isFailOpen()) {
                // fail-open 是刻意的可用性取舍：
                // Redis 出问题时，宁可允许极少数并发 turn，也不让聊天入口整体不可用。
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
        // 释放时不能直接 DEL：
        // 锁可能已经过期并被其他请求重新获取，此时必须确认 value 仍然是自己的 token。
        Long deleted = stringRedisTemplate.execute(COMPARE_AND_DELETE_SCRIPT, List.of(key), token);
        return Long.valueOf(1L).equals(deleted);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId.trim();
    }

    private static DefaultRedisScript<Long> buildCompareAndDeleteScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        // 只有当 Redis 里当前 value 等于本请求的 token 时才删除，
        // 防止“旧请求在超时后把新请求的锁误删”。
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
                // 调用方用 try-with-resources 即可，释放细节全部封装在锁对象内部。
                release(key, token);
            } catch (Exception e) {
                log.warn("Failed to release session entry guard: key={}, error={}", key, e.getMessage());
            }
        }
    }
}
