package com.yulong.chatagent.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.yulong.chatagent.conversation.event.ChatEventProcessor;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.lock.MqSessionExecLockAcquisition;
import com.yulong.chatagent.mq.lock.MqSessionExecLockLease;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockLease;
import com.yulong.chatagent.mq.lock.MqTaskLockState;
import com.yulong.chatagent.mq.outbox.event.AgentRunTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunTaskListenerTest {

    @Mock
    private RabbitMqMessagePublisher rabbitMqMessagePublisher;

    @Mock
    private DistributedLockManager distributedLockManager;

    @Mock
    private LockWatchdog lockWatchdog;

    @Mock
    private ChatEventProcessor chatEventProcessor;

    @Mock
    private Channel channel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldAckSuccessfulAgentRunMessage() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });

        listener.handle(buildMessage(0, false), channel);

        verify(chatEventProcessor, never()).rollbackTurn(anyString(), anyString());
        verify(chatEventProcessor).process(any());
        verify(distributedLockManager).markCompleted(any());
        verify(channel).basicAck(7L, false);
        verify(rabbitMqMessagePublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void shouldRollbackAndProcessWhenRetryCountIsGreater() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });

        listener.handle(buildMessage(1, false), channel);

        verify(chatEventProcessor).rollbackTurn("session-1", "turn-1");
        verify(chatEventProcessor).process(any());
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldRollbackAndProcessWhenForceRollbackIsSetEvenIfRetryCountIsZero() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });

        listener.handle(buildMessage(0, true), channel);

        verify(chatEventProcessor).rollbackTurn("session-1", "turn-1");
        verify(chatEventProcessor).process(any());
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldMoveRetryableFailureToRetryQueue() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });
        when(distributedLockManager.releaseRunning(any())).thenReturn(true);
        doThrow(new RuntimeException("transient")).when(chatEventProcessor).process(any());

        listener.handle(buildMessage(0, false), channel);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitMqMessagePublisher).publish(
                eq("retry.direct"),
                eq("retry.agent"),
                messageCaptor.capture(),
                anyString()
        );
        verify(distributedLockManager).releaseRunning(any());
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
                .containsEntry(MqMessageHeaders.RETRY_COUNT, 1);
        verify(channel).basicAck(7L, false);
        verify(chatEventProcessor, never()).publishFailure(any(), any());
    }

    @Test
    void shouldRequeueOriginalMessageWhenRetryPublishFails() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });
        when(distributedLockManager.releaseRunning(any())).thenReturn(true);
        doThrow(new RuntimeException("transient")).when(chatEventProcessor).process(any());
        doThrow(new AmqpException("retry route failed") {
        }).when(rabbitMqMessagePublisher).publish(anyString(), anyString(), any(), anyString());

        listener.handle(buildMessage(1, false), channel);

        verify(channel).basicNack(7L, false, true);
    }

    @Test
    void shouldRequeueOriginalMessageWhenRunningLockCannotBeReleasedBeforeRetry() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });
        when(distributedLockManager.releaseRunning(any())).thenReturn(false);
        doThrow(new RuntimeException("transient")).when(chatEventProcessor).process(any());

        listener.handle(buildMessage(0, false), channel);

        verify(channel).basicNack(7L, false, true);
        verify(rabbitMqMessagePublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void shouldRejectTerminalFailureAndPublishFallbackMessage() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });
        doThrow(new BizException("bad request")).when(chatEventProcessor).process(any());

        listener.handle(buildMessage(0, false), channel);

        verify(distributedLockManager).markFailed(any(), anyString());
        verify(chatEventProcessor).publishFailure(any(), any(BizException.class));
        verify(channel).basicReject(7L, false);
    }

    @Test
    void shouldRejectMessageWhenRetriesAreExhaustedAndPublishFallbackMessage() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(distributedLockManager.acquireSessionExecLock(eq("session-1"), anyString())).thenReturn(acquiredSessionLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });
        doThrow(new RuntimeException("transient")).when(chatEventProcessor).process(any());

        listener.handle(buildMessage(3, false), channel);

        verify(distributedLockManager).markFailed(any(), anyString());
        verify(chatEventProcessor).publishFailure(any(), any(RuntimeException.class));
        verify(channel).basicReject(7L, false);
        verify(rabbitMqMessagePublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void shouldAckDuplicateMessageWithoutProcessing() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(
                new MqTaskLockAcquisition(MqTaskLockAcquireOutcome.DUPLICATE, null, MqTaskLockState.COMPLETED)
        );

        listener.handle(buildMessage(0, false), channel);

        verify(channel).basicAck(7L, false);
        verify(chatEventProcessor, never()).process(any());
    }

    @Test
    void shouldDelayWaitRequiredMessage() throws Exception {
        AgentRunTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(
                new MqTaskLockAcquisition(MqTaskLockAcquireOutcome.WAIT_REQUIRED, null, MqTaskLockState.RUNNING)
        );

        listener.handle(buildMessage(0, false), channel);

        verify(rabbitMqMessagePublisher).publish(eq("retry.direct"), eq("retry.agent"), any(), anyString());
        verify(channel).basicAck(7L, false);
        verify(chatEventProcessor, never()).process(any());
    }

    @Test
    void shouldFailOpenWhenRedisFailsAndPolicyIsFailOpen() throws Exception {
        AgentRunTaskListener listener = newListener();
        // Default agentRunPolicy is FAIL_OPEN in properties
        when(distributedLockManager.tryAcquire(any(), anyString())).thenThrow(new RuntimeException("Redis down"));

        listener.handle(buildMessage(0, false), channel);

        verify(chatEventProcessor).process(any());
        verify(channel).basicAck(7L, false);
        // markCompleted should be skipped because lease is null in FAIL_OPEN
        verify(distributedLockManager, never()).markCompleted(any());
    }

    @Test
    void shouldFailFastWhenRedisFailsAndPolicyIsFailFast() throws Exception {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        properties.getDispatchers().setAgentRunEnabled(true);
        properties.getLocks().setAgentRunPolicy(ChatAgentMqProperties.RedisFailurePolicy.FAIL_FAST);
        
        AgentRunTaskListener listener = new AgentRunTaskListener(
                objectMapper,
                properties,
                rabbitMqMessagePublisher,
                distributedLockManager,
                lockWatchdog,
                chatEventProcessor
        );
        
        when(distributedLockManager.tryAcquire(any(), anyString())).thenThrow(new RuntimeException("Redis down"));

        // Fatal error bubbles up to consume() which defaults to basicNack(true) in AbstractRetryingMqConsumer.java
        listener.handle(buildMessage(0, false), channel);
        
        verify(channel).basicNack(7L, false, true);
        verify(chatEventProcessor, never()).process(any());
    }

    private AgentRunTaskListener newListener() {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        properties.getDispatchers().setAgentRunEnabled(true);
        return new AgentRunTaskListener(
                objectMapper,
                properties,
                rabbitMqMessagePublisher,
                distributedLockManager,
                lockWatchdog,
                chatEventProcessor
        );
    }

    private Message buildMessage(int retryCount, boolean forceRollback) throws Exception {
        AgentRunTaskPayload payload = new AgentRunTaskPayload(
                "agent-1",
                "session-1",
                "turn-1",
                "msg-1",
                "hello",
                3,
                null,
                "rewritten",
                "user-1",
                forceRollback
        );
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-1",
                "turn-1",
                "trace-1",
                "agent.run",
                "session-1",
                "chat.direct",
                "agent.run",
                Instant.parse("2026-03-30T00:00:00Z"),
                retryCount
        );
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        MqMessageHeaders.apply(properties, identity);
        return MessageBuilder.withBody(objectMapper.writeValueAsBytes(payload))
                .andProperties(properties)
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
                        "event-1",
                        "turn-1",
                        "trace-1",
                        "agent.run",
                        "session-1",
                        "chat.direct",
                        "agent.run",
                        Instant.parse("2026-03-30T00:00:00Z"),
                        0
                        )
                ),
                null
        );
    }

    private MqSessionExecLockAcquisition acquiredSessionLock() {
        return new MqSessionExecLockAcquisition(
                MqTaskLockAcquireOutcome.ACQUIRED,
                new MqSessionExecLockLease(
                        "chatagent:mq:session-exec-lock:session-1",
                        "session-token-1",
                        "AgentRunTaskListener",
                        "session-1"
                )
        );
    }
}
