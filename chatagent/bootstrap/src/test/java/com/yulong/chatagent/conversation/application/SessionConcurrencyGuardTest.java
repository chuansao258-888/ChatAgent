package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.exception.SessionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionConcurrencyGuardTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SessionGuardProperties properties;
    private SessionConcurrencyGuard subject;

    @BeforeEach
    void setUp() {
        properties = new SessionGuardProperties();
        subject = new SessionConcurrencyGuard(stringRedisTemplate, properties);
    }

    @Test
    void acquireShouldReleaseLockWhenGuardedSectionFinishes() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doReturn(1L).when(stringRedisTemplate).execute(any(), anyList(), any());

        assertThatCode(() -> {
            try (SessionConcurrencyGuard.SessionLock ignored = subject.acquire("session-1")) {
                // no-op
            }
        }).doesNotThrowAnyException();

        verify(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(stringRedisTemplate).execute(any(), anyList(), any());
    }

    @Test
    void acquireShouldThrowSessionConflictWhenAnotherRequestOwnsTheSession() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> subject.acquire("session-1"))
                .isInstanceOf(SessionConflictException.class)
                .hasMessageContaining("already starting a turn");
    }

    @Test
    void acquireShouldFailOpenWhenRedisErrorsAndPolicyAllowsIt() {
        properties.setFailOpen(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis unavailable"));

        assertThatCode(() -> {
            try (SessionConcurrencyGuard.SessionLock ignored = subject.acquire("session-1")) {
                // no-op
            }
        }).doesNotThrowAnyException();

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any());
    }

    @Test
    void acquireShouldFailFastWhenRedisErrorsAndPolicyDisallowsFailOpen() {
        properties.setFailOpen(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis unavailable"));

        assertThatThrownBy(() -> subject.acquire("session-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis unavailable");
    }
}
