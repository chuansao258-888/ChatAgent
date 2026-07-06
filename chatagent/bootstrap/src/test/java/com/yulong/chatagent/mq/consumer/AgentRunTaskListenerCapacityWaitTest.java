package com.yulong.chatagent.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.conversation.event.ChatEventProcessor;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.lock.MqSessionExecLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockLease;
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.ratelimit.RateLimitProperties;
import com.yulong.chatagent.ratelimit.capacity.AgentRunCapacityLimiter;
import com.yulong.chatagent.ratelimit.capacity.CapacityGateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Capacity WAIT behavior tests: throttled AI_EXECUTING status emission and
 * timeout terminal path (publishFailure + AI_ERROR/AI_DONE + ACK).
 */
@ExtendWith(MockitoExtension.class)
class AgentRunTaskListenerCapacityWaitTest {

    @Mock
    private RabbitMqMessagePublisher rabbitMqMessagePublisher;
    @Mock
    private DistributedLockManager distributedLockManager;
    @Mock
    private LockWatchdog lockWatchdog;
    @Mock
    private ChatEventProcessor chatEventProcessor;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private AgentRunCapacityLimiter capacityLimiter;
    @Mock
    private Channel channel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RateLimitProperties rateLimitProperties;
    private final AtomicReference<Clock> clockRef = new AtomicReference<>(fixedAt("2026-07-07T10:00:00Z"));

    @BeforeEach
    void setUp() {
        rateLimitProperties = new RateLimitProperties();
        rateLimitProperties.getAgentRun().setWaitTimeoutMs(120000L);
        rateLimitProperties.getAgentRun().setWaitStatusIntervalMs(5000L);
        // Default stubs so lenient mockers don't produce UnnecessaryStubbingException.
        lenient().when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });
        lenient().when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        lenient().when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString()))
                .thenReturn(acquiredSessionLock());
    }

    @Test
    void firstCapacityWaitShouldEmitStatusImmediately() throws Exception {
        when(capacityLimiter.tryAcquire(any())).thenReturn(CapacityGateResult.waitInQueue());
        AgentRunTaskListener listener = newListener();

        listener.handle(buildMessage(null, null), channel);

        // First WAIT publishes status immediately (lastNotifiedAt was null).
        verify(chatEventProcessor, times(1)).publishCapacityWaitStatus(any());
        verify(rabbitMqMessagePublisher).publish(anyString(), anyString(), any(Message.class), anyString());
        // Non-terminal WAIT must NOT publish failure; turn is left pending for requeue.
        verify(chatEventProcessor, never()).publishFailure(any(ChatEvent.class), any());
    }

    @Test
    void shouldThrottleDuplicateStatusWithinInterval() throws Exception {
        // lastNotifiedAt is only 1 second ago; interval is 5s -> suppressed.
        Instant startedAt = Instant.parse("2026-07-07T10:00:00Z");
        Instant lastNotifiedAt = Instant.parse("2026-07-07T09:59:59Z");
        when(capacityLimiter.tryAcquire(any())).thenReturn(CapacityGateResult.waitInQueue());
        AgentRunTaskListener listener = newListener();

        listener.handle(buildMessage(startedAt, lastNotifiedAt), channel);

        // Suppressed: no status published, still requeued.
        verify(chatEventProcessor, never()).publishCapacityWaitStatus(any());
        verify(rabbitMqMessagePublisher).publish(anyString(), anyString(), any(Message.class), anyString());
    }

    @Test
    void shouldPublishStatusAgainAfterIntervalElapses() throws Exception {
        // lastNotifiedAt is 10s ago; interval is 5s -> re-published.
        Instant startedAt = Instant.parse("2026-07-07T09:59:50Z");
        Instant lastNotifiedAt = Instant.parse("2026-07-07T09:59:50Z");
        when(capacityLimiter.tryAcquire(any())).thenReturn(CapacityGateResult.waitInQueue());
        AgentRunTaskListener listener = newListener();

        listener.handle(buildMessage(startedAt, lastNotifiedAt), channel);

        verify(chatEventProcessor, times(1)).publishCapacityWaitStatus(any());
        verify(rabbitMqMessagePublisher).publish(anyString(), anyString(), any(Message.class), anyString());
    }

    @Test
    void shouldRequeueWithUpdatedLastNotifiedAtOnFirstWait() throws Exception {
        when(capacityLimiter.tryAcquire(any())).thenReturn(CapacityGateResult.waitInQueue());
        AgentRunTaskListener listener = newListener();

        listener.handle(buildMessage(null, null), channel);

        // Capture the requeued message and verify capacityWaitLastNotifiedAt was set.
        org.mockito.ArgumentCaptor<Message> msgCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitMqMessagePublisher).publish(anyString(), anyString(), msgCaptor.capture(), anyString());
        MqMessageIdentity requeued = MqMessageHeaders.read(msgCaptor.getValue().getMessageProperties());
        assertThat(requeued).isNotNull();
        assertThat(requeued.capacityWaitStartedAt()).isNotNull();
        assertThat(requeued.capacityWaitLastNotifiedAt()).isNotNull();
        // retryCount must not increment on capacity WAIT.
        assertThat(requeued.retryCount()).isZero();
    }

    @Test
    void shouldCompleteTurnAndAckOnTimeout() throws Exception {
        // startedAt is 200s before now; timeout is 120s -> timed out.
        Instant startedAt = Instant.parse("2026-07-07T09:56:40Z");
        when(capacityLimiter.tryAcquire(any())).thenReturn(CapacityGateResult.waitInQueue());
        AgentRunTaskListener listener = newListener();

        listener.handle(buildMessage(startedAt, startedAt), channel);

        // Terminal path: publishFailure (rollback + fallback + AI_ERROR/AI_DONE) called, turn completed.
        verify(chatEventProcessor, times(1)).publishFailure(any(ChatEvent.class), any());
        // ACKed as terminal (not requeued).
        verify(channel, times(1)).basicAck(eq(7L), eq(false));
        verify(rabbitMqMessagePublisher, never()).publish(anyString(), anyString(), any(Message.class), anyString());
        // Task lock marked completed so it does not block future replays.
        verify(distributedLockManager, times(1)).markCompleted(any());
    }

    private AgentRunTaskListener newListener() {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        properties.getDispatchers().setAgentRunEnabled(true);
        return new AgentRunTaskListener(
                objectMapper, properties, rabbitMqMessagePublisher, distributedLockManager,
                lockWatchdog, chatEventProcessor, chatSessionRepository, capacityLimiter,
                rateLimitProperties, clockRef.get()
        );
    }

    private Message buildMessage(Instant capacityWaitStartedAt, Instant capacityWaitLastNotifiedAt) throws Exception {
        AgentRunTaskPayload payload = new AgentRunTaskPayload(
                "agent-1", "session-1", "turn-1", 1L, "msg-1",
                "hello", 3, null, "rewritten", "user-1", false
        );
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-1", "turn-1", "trace-1", "agent.run", "session-1",
                "chat.direct", "agent.run", Instant.parse("2026-07-07T09:00:00Z"),
                0, capacityWaitStartedAt, capacityWaitLastNotifiedAt
        );
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(7L);
        MqMessageHeaders.apply(props, identity);
        return MessageBuilder.withBody(objectMapper.writeValueAsBytes(payload))
                .andProperties(props)
                .build();
    }

    private MqTaskLockAcquisition acquiredLock() {
        return new MqTaskLockAcquisition(
                MqTaskLockAcquireOutcome.ACQUIRED,
                new MqTaskLockLease(
                        "chatagent:mq:task-lock:agent.run:turn-1",
                        "token-1",
                        "AgentRunTaskListener",
                        new MqMessageIdentity(
                                "event-1", "turn-1", "trace-1", "agent.run", "session-1",
                                "chat.direct", "agent.run", Instant.parse("2026-07-07T09:00:00Z"),
                                0, null, null
                        )
                ),
                null
        );
    }

    private MqSessionExecLockAcquisition acquiredSessionLock() {
        return new MqSessionExecLockAcquisition(
                MqTaskLockAcquireOutcome.ACQUIRED,
                new com.yulong.chatagent.mq.lock.MqSessionExecLockLease(
                        "chatagent:mq:session-exec-lock:session-1",
                        "session-token-1", "AgentRunTaskListener", "session-1"
                )
        );
    }

    private static Clock fixedAt(String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
    }
}
