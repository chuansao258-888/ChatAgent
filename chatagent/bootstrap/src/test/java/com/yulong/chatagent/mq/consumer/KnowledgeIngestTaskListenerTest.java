package com.yulong.chatagent.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquireOutcome;
import com.yulong.chatagent.mq.lock.MqTaskLockAcquisition;
import com.yulong.chatagent.mq.lock.MqTaskLockLease;
import com.yulong.chatagent.mq.lock.MqTaskLockState;
import com.yulong.chatagent.mq.outbox.event.KnowledgeIngestTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
import com.yulong.chatagent.rag.ingestion.RetryableKnowledgeDocumentIngestionException;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
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
class KnowledgeIngestTaskListenerTest {

    @Mock
    private RabbitMqMessagePublisher rabbitMqMessagePublisher;

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Mock
    private KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;

    @Mock
    private KnowledgeDocumentIngestionService knowledgeDocumentIngestionService;

    @Mock
    private Channel channel;

    @Mock
    private DistributedLockManager distributedLockManager;

    @Mock
    private LockWatchdog lockWatchdog;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldAckSuccessfulKnowledgeIngestMessage() throws Exception {
        KnowledgeIngestTaskListener listener = newListener();
        KnowledgeDocumentDTO document = sampleDocument();
        when(knowledgeDocumentRepository.findById("doc-1")).thenReturn(document);
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(lockWatchdog.watch(any())).thenReturn(() -> {
        });

        listener.handle(buildMessage(0, true), channel);

        verify(knowledgeChunkRepository).deleteByKnowledgeDocumentId("doc-1");
        verify(knowledgeBaseMilvusIndexer).deleteByKnowledgeDocumentId("doc-1");
        verify(knowledgeDocumentIngestionService).ingestSync("kb-1", document);
        verify(distributedLockManager).markCompleted(any());
        verify(channel).basicAck(7L, false);
        verify(rabbitMqMessagePublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void shouldMoveRetryableFailureToRetryQueue() throws Exception {
        KnowledgeIngestTaskListener listener = newListener();
        KnowledgeDocumentDTO document = sampleDocument();
        when(knowledgeDocumentRepository.findById("doc-1")).thenReturn(document);
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(lockWatchdog.watch(any())).thenReturn(() -> {
        });
        when(distributedLockManager.releaseRunning(any())).thenReturn(true);
        doThrow(new RetryableKnowledgeDocumentIngestionException("transient", new RuntimeException("boom")))
                .when(knowledgeDocumentIngestionService)
                .ingestSync("kb-1", document);

        listener.handle(buildMessage(0, false), channel);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitMqMessagePublisher).publish(
                eq("retry.direct"),
                eq("retry.ingest"),
                messageCaptor.capture(),
                anyString()
        );
        verify(distributedLockManager).releaseRunning(any());
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
                .containsEntry(MqMessageHeaders.RETRY_COUNT, 1);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldRequeueOriginalMessageWhenRetryPublishFails() throws Exception {
        KnowledgeIngestTaskListener listener = newListener();
        KnowledgeDocumentDTO document = sampleDocument();
        when(knowledgeDocumentRepository.findById("doc-1")).thenReturn(document);
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(lockWatchdog.watch(any())).thenReturn(() -> {
        });
        when(distributedLockManager.releaseRunning(any())).thenReturn(true);
        doThrow(new RetryableKnowledgeDocumentIngestionException("transient", new RuntimeException("boom")))
                .when(knowledgeDocumentIngestionService)
                .ingestSync("kb-1", document);
        doThrow(new AmqpException("retry route failed") {
        }).when(rabbitMqMessagePublisher).publish(anyString(), anyString(), any(), anyString());

        listener.handle(buildMessage(1, false), channel);

        verify(channel).basicNack(7L, false, true);
    }

    @Test
    void shouldRejectMessageWhenRetriesAreExhausted() throws Exception {
        KnowledgeIngestTaskListener listener = newListener();
        KnowledgeDocumentDTO document = sampleDocument();
        when(knowledgeDocumentRepository.findById("doc-1")).thenReturn(document);
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(lockWatchdog.watch(any())).thenReturn(() -> {
        });
        doThrow(new RetryableKnowledgeDocumentIngestionException("transient", new RuntimeException("boom")))
                .when(knowledgeDocumentIngestionService)
                .ingestSync("kb-1", document);

        listener.handle(buildMessage(3, false), channel);

        verify(distributedLockManager).markFailed(any(), anyString());
        verify(channel).basicReject(7L, false);
        verify(rabbitMqMessagePublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void shouldAckDuplicateMessageWithoutProcessing() throws Exception {
        KnowledgeIngestTaskListener listener = newListener();
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(
                new MqTaskLockAcquisition(MqTaskLockAcquireOutcome.DUPLICATE, null, MqTaskLockState.COMPLETED)
        );

        listener.handle(buildMessage(0, false), channel);

        verify(channel).basicAck(7L, false);
        verify(knowledgeDocumentRepository, never()).findById(anyString());
        verify(knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
    }

    private KnowledgeIngestTaskListener newListener() {
        return new KnowledgeIngestTaskListener(
                objectMapper,
                new ChatAgentMqProperties(),
                rabbitMqMessagePublisher,
                distributedLockManager,
                lockWatchdog,
                knowledgeDocumentRepository,
                knowledgeChunkRepository,
                knowledgeBaseMilvusIndexer,
                knowledgeDocumentIngestionService
        );
    }

    private KnowledgeDocumentDTO sampleDocument() {
        return KnowledgeDocumentDTO.builder()
                .id("doc-1")
                .knowledgeBaseId("kb-1")
                .deleted(false)
                .build();
    }

    private Message buildMessage(int retryCount, boolean clearExistingContentFirst) throws Exception {
        KnowledgeIngestTaskPayload payload = new KnowledgeIngestTaskPayload("kb-1", "doc-1", clearExistingContentFirst);
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-1",
                "doc-1",
                "trace-1",
                "knowledge.ingest",
                "chat.direct",
                "ingest.task",
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
                        "chatagent:mq:task-lock:knowledge.ingest:doc-1",
                        "token-1",
                        "KnowledgeIngestTaskListener",
                        new MqMessageIdentity(
                                "event-1",
                                "doc-1",
                                "trace-1",
                                "knowledge.ingest",
                                "chat.direct",
                                "ingest.task",
                                Instant.parse("2026-03-30T00:00:00Z"),
                                0
                        )
                ),
                null
        );
    }
}
