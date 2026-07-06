package com.yulong.chatagent.mq.lock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockManagerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DistributedLockManager distributedLockManager;

    @BeforeEach
    void setUp() {
        distributedLockManager = new DistributedLockManager(
                stringRedisTemplate,
                new ObjectMapper(),
                new ChatAgentMqProperties()
        );
    }

    @Test
    void shouldAcquireRunningLockWhenKeyIsAbsent() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(Boolean.TRUE);

        MqTaskLockAcquisition acquisition = distributedLockManager.tryAcquire(sampleIdentity(), "consumer-1");

        assertThat(acquisition.outcome()).isEqualTo(MqTaskLockAcquireOutcome.ACQUIRED);
        assertThat(acquisition.lease()).isNotNull();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(keyCaptor.capture(), anyString(), eq(Duration.ofSeconds(60)));
        assertThat(keyCaptor.getValue()).isEqualTo("chatagent:mq:task-lock:knowledge.ingest:doc-1");
    }

    @Test
    void shouldTreatExistingCompletedStateAsDuplicate() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(Boolean.FALSE);
        when(valueOperations.get("chatagent:mq:task-lock:knowledge.ingest:doc-1"))
                .thenReturn("""
                        {"status":"COMPLETED","token":"token-1","owner":"consumer-1","taskType":"knowledge.ingest","eventId":"event-1","idempotencyKey":"doc-1","traceId":"trace-1","updatedAtEpochMs":1711756800000,"lastError":""}
                        """);

        MqTaskLockAcquisition acquisition = distributedLockManager.tryAcquire(sampleIdentity(), "consumer-2");

        assertThat(acquisition.outcome()).isEqualTo(MqTaskLockAcquireOutcome.DUPLICATE);
        assertThat(acquisition.existingState()).isEqualTo(MqTaskLockState.COMPLETED);
    }

    @Test
    void shouldReacquireLockWhenExistingStateIsFailed() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(Boolean.FALSE);
        when(valueOperations.get("chatagent:mq:task-lock:knowledge.ingest:doc-1"))
                .thenReturn("""
                        {"status":"FAILED","token":"token-1","owner":"consumer-1","taskType":"knowledge.ingest","eventId":"event-1","idempotencyKey":"doc-1","traceId":"trace-1","updatedAtEpochMs":1711756800000,"lastError":"boom"}
                        """);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("chatagent:mq:task-lock:knowledge.ingest:doc-1")), anyString(), eq("60000")))
                .thenReturn(1L);

        MqTaskLockAcquisition acquisition = distributedLockManager.tryAcquire(sampleIdentity(), "consumer-2");

        assertThat(acquisition.outcome()).isEqualTo(MqTaskLockAcquireOutcome.ACQUIRED);
        assertThat(acquisition.lease()).isNotNull();
    }

    @Test
    void shouldWaitWhenExistingStateIsRunningEvenForSameOwner() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(Boolean.FALSE);
        when(valueOperations.get("chatagent:mq:task-lock:knowledge.ingest:doc-1"))
                .thenReturn("""
                        {"status":"RUNNING","token":"token-1","owner":"consumer-1","taskType":"knowledge.ingest","eventId":"event-1","idempotencyKey":"doc-1","traceId":"trace-1","updatedAtEpochMs":1711756800000,"lastError":""}
                        """);

        MqTaskLockAcquisition acquisition = distributedLockManager.tryAcquire(sampleIdentity(), "consumer-1");

        assertThat(acquisition.outcome()).isEqualTo(MqTaskLockAcquireOutcome.WAIT_REQUIRED);
        assertThat(acquisition.existingState()).isEqualTo(MqTaskLockState.RUNNING);
    }

    @Test
    void shouldMarkLockCompleted() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("lock-1")), eq("token-1"), eq("COMPLETED"), anyString(), eq("consumer-1"), eq(""), eq("86400000")))
                .thenReturn(1L);

        distributedLockManager.markCompleted(new MqTaskLockLease("lock-1", "token-1", "consumer-1", sampleIdentity()));

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(List.of("lock-1")), eq("token-1"), eq("COMPLETED"), anyString(), eq("consumer-1"), eq(""), eq("86400000"));
    }

    @Test
    void shouldReleaseRunningLockWhenTokenMatches() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("lock-1")), eq("token-1")))
                .thenReturn(1L);

        assertThat(distributedLockManager.releaseRunning(
                new MqTaskLockLease("lock-1", "token-1", "consumer-1", sampleIdentity())
        )).isTrue();
    }

    private MqMessageIdentity sampleIdentity() {
        return new MqMessageIdentity(
                "event-1",
                "doc-1",
                "trace-1",
                "knowledge.ingest",
                null,
                "chat.direct",
                "ingest.task",
                Instant.parse("2026-03-30T00:00:00Z"),
                0,
                null,
                null
        );
    }
}
