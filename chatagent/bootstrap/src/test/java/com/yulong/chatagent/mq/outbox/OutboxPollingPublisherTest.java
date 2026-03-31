package com.yulong.chatagent.mq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPollingPublisherTest {

    @Mock
    private OutboxRecordService outboxRecordService;

    @Mock
    private RabbitMqMessagePublisher rabbitMqMessagePublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMarkRowSentAfterSuccessfulPublish() throws Exception {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        OutboxPollingPublisher poller = new OutboxPollingPublisher(
                outboxRecordService,
                rabbitMqMessagePublisher,
                objectMapper,
                properties
        );
        MqOutbox outbox = sampleOutbox(0);
        when(outboxRecordService.claimBatch(anyString(), any(LocalDateTime.class))).thenReturn(List.of(outbox));

        poller.publishDueRows();

        verify(rabbitMqMessagePublisher).publish(
                org.mockito.ArgumentMatchers.eq(outbox.getExchange()),
                org.mockito.ArgumentMatchers.eq(outbox.getRoutingKey()),
                any(),
                anyString()
        );
        verify(outboxRecordService).markSent(outbox);
    }

    @Test
    void shouldMarkPublishFailureWhenBrokerSendFails() throws Exception {
        ChatAgentMqProperties properties = new ChatAgentMqProperties();
        OutboxPollingPublisher poller = new OutboxPollingPublisher(
                outboxRecordService,
                rabbitMqMessagePublisher,
                objectMapper,
                properties
        );
        MqOutbox outbox = sampleOutbox(1);
        when(outboxRecordService.claimBatch(anyString(), any(LocalDateTime.class))).thenReturn(List.of(outbox));
        doThrow(new AmqpException("broker unavailable") {
        }).when(rabbitMqMessagePublisher).publish(anyString(), anyString(), any(), anyString());

        poller.publishDueRows();

        verify(outboxRecordService).markPublishFailure(
                org.mockito.ArgumentMatchers.eq(outbox),
                anyString(),
                any(LocalDateTime.class)
        );
    }

    private MqOutbox sampleOutbox(int retryCount) throws Exception {
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
        return MqOutbox.builder()
                .id("outbox-1")
                .eventType("knowledge.ingest")
                .exchange("chat.direct")
                .routingKey("ingest.task")
                .payload("{\"knowledgeBaseId\":\"kb-1\",\"documentId\":\"doc-1\",\"clearExistingContentFirst\":false}")
                .headers(objectMapper.writeValueAsString(MqMessageHeaders.toMap(identity)))
                .status("CLAIMED")
                .retryCount(retryCount)
                .version(1)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
