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
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIngestTaskListenerTest {

    @Mock
    private RabbitMqMessagePublisher rabbitMqMessagePublisher;

    @Mock
    private DistributedLockManager distributedLockManager;

    @Mock
    private LockWatchdog lockWatchdog;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldAckSuccessfulKnowledgeIngestMessage() throws Exception {
        KnowledgeIngestTaskListener listener = newListener(new ChatAgentMqProperties());
        KnowledgeDocumentDTO document = KnowledgeDocumentDTO.builder()
                .id("doc-1")
                .knowledgeBaseId("kb-1")
                .deleted(false)
                .build();

        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(acquiredLock());
        when(lockWatchdog.watch(any(), any())).thenReturn(() -> {
        });
        when(knowledgeDocumentRepository.findById("doc-1")).thenReturn(document);

        listener.handle(buildMessage(0, true), channel);

        verify(knowledgeChunkRepository).deleteByKnowledgeDocumentId("doc-1");
        verify(knowledgeBaseMilvusIndexer).deleteByKnowledgeDocumentId("doc-1");
        verify(knowledgeDocumentIngestionService).ingestSync("kb-1", document);
        verify(distributedLockManager).markCompleted(any());
        verify(channel).basicAck(7L, false);
        verify(rabbitMqMessagePublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void shouldRequeueWithDelayWhenTaskLockIsRunning() throws Exception {
        KnowledgeIngestTaskListener listener = newListener(new ChatAgentMqProperties());
        when(distributedLockManager.tryAcquire(any(), anyString())).thenReturn(
                new MqTaskLockAcquisition(MqTaskLockAcquireOutcome.WAIT_REQUIRED, null, MqTaskLockState.RUNNING)
        );

        listener.handle(buildMessage(0, false), channel);

        verify(rabbitMqMessagePublisher).publish(anyString(), anyString(), any(), anyString());
        verify(channel).basicAck(7L, false);
        verify(knowledgeDocumentRepository, never()).findById(anyString());
        verify(knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
    }

    @Test
    void shouldFailFastWhenRedisFailsAndPolicyIsFailFast() throws Exception {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        properties.getLocks().setIngestTaskPolicy(ChatAgentMqProperties.RedisFailurePolicy.FAIL_FAST);

        KnowledgeIngestTaskListener listener = newListener(properties);
        when(distributedLockManager.tryAcquire(any(), anyString())).thenThrow(new RuntimeException("Redis down"));

        listener.handle(buildMessage(0, false), channel);

        verify(channel).basicNack(7L, false, true);
        verify(knowledgeDocumentRepository, never()).findById(anyString());
        verify(knowledgeDocumentIngestionService, never()).ingestSync(anyString(), any());
    }

    private KnowledgeIngestTaskListener newListener(ChatAgentMqProperties properties) {
        return new KnowledgeIngestTaskListener(
                objectMapper,
                properties,
                rabbitMqMessagePublisher,
                distributedLockManager,
                lockWatchdog,
                knowledgeDocumentRepository,
                knowledgeChunkRepository,
                knowledgeBaseMilvusIndexer,
                knowledgeDocumentIngestionService
        );
    }

    private Message buildMessage(int retryCount, boolean clearExistingContentFirst) throws Exception {
        KnowledgeIngestTaskPayload payload = new KnowledgeIngestTaskPayload("kb-1", "doc-1", clearExistingContentFirst);
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-1",
                "doc-1",
                "trace-1",
                "knowledge.ingest",
                null,
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
                        "KnowledgeIngestTaskListener:node-a",
                        new MqMessageIdentity(
                        "event-1",
                        "doc-1",
                        "trace-1",
                        "knowledge.ingest",
                        null,
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
