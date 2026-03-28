package com.yulong.chatagent.conversation.summary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisLockManagerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisLockManager redisLockManager;

    @BeforeEach
    void setUp() {
        redisLockManager = new RedisLockManager(stringRedisTemplate);
    }

    @Test
    void shouldReturnTokenWhenLockAcquired() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(Boolean.TRUE);

        String token = redisLockManager.tryLock("session-1");

        assertThat(token).isNotBlank();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(keyCaptor.capture(), anyString(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("chatagent:memory:lock:session-1");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void shouldReturnNullWhenLockAlreadyHeld() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(Boolean.FALSE);

        assertThat(redisLockManager.tryLock("session-1")).isNull();
    }

    @Test
    void shouldUnlockOnlyWhenTokenMatches() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("chatagent:memory:lock:session-1")), eq("token-1")))
                .thenReturn(1L);

        assertThat(redisLockManager.unlock("session-1", "token-1")).isTrue();
    }

    @Test
    void shouldRejectUnlockWhenTokenDoesNotMatch() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("chatagent:memory:lock:session-1")), eq("token-2")))
                .thenReturn(0L);

        assertThat(redisLockManager.unlock("session-1", "token-2")).isFalse();
    }

    @Test
    void shouldRejectUnlockWhenTokenIsBlank() {
        assertThat(redisLockManager.unlock("session-1", null)).isFalse();
        assertThat(redisLockManager.unlock("session-1", "")).isFalse();
        assertThat(redisLockManager.unlock("session-1", "   ")).isFalse();

        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), any(List.class), anyString());
    }
}
