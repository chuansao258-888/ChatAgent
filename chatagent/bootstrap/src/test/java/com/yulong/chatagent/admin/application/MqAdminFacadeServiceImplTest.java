package com.yulong.chatagent.admin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.yulong.chatagent.admin.model.request.ReplayDlqMessagesRequest;
import com.yulong.chatagent.admin.model.response.GetMqOutboxRetryResponse;
import com.yulong.chatagent.admin.model.response.ReplayDlqMessagesResponse;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxRepository;
import com.yulong.chatagent.mq.support.MqMessageHeaders;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqAdminFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitMqMessagePublisher rabbitMqMessagePublisher;

    @Mock
    private Channel channel;

    private ChatAgentMqProperties properties;
    private ObjectMapper objectMapper;
    private MqAdminFacadeServiceImpl mqAdminFacadeService;

    @BeforeEach
    void setUp() {
        properties = new ChatAgentMqProperties();
        objectMapper = new ObjectMapper();
        mqAdminFacadeService = new MqAdminFacadeServiceImpl(
                adminAccessService,
                outboxRepository,
                amqpAdmin,
                rabbitTemplate,
                rabbitMqMessagePublisher,
                objectMapper,
                properties
        );
        when(adminAccessService.requireAdmin()).thenReturn(LoginUser.builder().userId("admin-1").role("admin").build());
    }

    @Test
    void shouldReturnOutboxRetryStateWithQueueDepthsAndHeaderIdentity() throws Exception {
        when(outboxRepository.countByStatus("PENDING")).thenReturn(2);
        when(outboxRepository.countByStatus("CLAIMED")).thenReturn(1);
        when(outboxRepository.countByStatus("SENT")).thenReturn(7);
        when(outboxRepository.countByStatus("FAILED")).thenReturn(3);
        when(outboxRepository.findRecent("event-1", null, null, 10)).thenReturn(java.util.List.of(sampleOutbox()));
        when(amqpAdmin.getQueueProperties(properties.getQueues().getRetryAgent10s())).thenReturn(queueDepth(4));
        when(amqpAdmin.getQueueProperties(properties.getQueues().getRetryIngest30s())).thenReturn(queueDepth(5));
        when(amqpAdmin.getQueueProperties(properties.getQueues().getChatDlq())).thenReturn(queueDepth(6));

        GetMqOutboxRetryResponse response = mqAdminFacadeService.getOutboxRetryState("event-1", null, null, 10);

        assertThat(response.getPendingCount()).isEqualTo(2);
        assertThat(response.getClaimedCount()).isEqualTo(1);
        assertThat(response.getSentCount()).isEqualTo(7);
        assertThat(response.getFailedCount()).isEqualTo(3);
        assertThat(response.getRetryAgentQueueDepth()).isEqualTo(4);
        assertThat(response.getRetryIngestQueueDepth()).isEqualTo(5);
        assertThat(response.getDlqQueueDepth()).isEqualTo(6);
        assertThat(response.getRecords()).singleElement().satisfies(record -> {
            assertThat(record.getEventId()).isEqualTo("event-1");
            assertThat(record.getIdempotencyKey()).isEqualTo("doc-1");
            assertThat(record.getStatus()).isEqualTo("FAILED");
        });
    }

    @Test
    void shouldReplayDlqMessagesToOriginalRouteAndResetRetryCount() throws Exception {
        ReplayDlqMessagesRequest request = new ReplayDlqMessagesRequest();
        request.setLimit(1);
        request.setResetRetryCount(true);

        when(rabbitTemplate.execute(any(ChannelCallback.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ChannelCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });
        when(channel.basicGet(properties.getQueues().getChatDlq(), false))
                .thenReturn(sampleGetResponse())
                .thenReturn(null);
        when(amqpAdmin.getQueueProperties(properties.getQueues().getChatDlq())).thenReturn(queueDepth(0));

        ReplayDlqMessagesResponse response = mqAdminFacadeService.replayDlqMessages(request);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitMqMessagePublisher).publish(
                eq("chat.direct"),
                eq("ingest.task"),
                messageCaptor.capture(),
                anyString()
        );
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
                .containsEntry(MqMessageHeaders.RETRY_COUNT, 0);
        verify(channel).basicAck(7L, false);
        assertThat(response.getReplayedCount()).isEqualTo(1);
        assertThat(response.isResetRetryCount()).isTrue();
        assertThat(response.getRemainingDlqDepth()).isZero();
    }

    private MqOutbox sampleOutbox() throws Exception {
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-1",
                "doc-1",
                "trace-1",
                "knowledge.ingest",
                null,
                "chat.direct",
                "ingest.task",
                Instant.parse("2026-03-30T00:00:00Z"),
                3
        );
        return MqOutbox.builder()
                .id("outbox-1")
                .eventType("knowledge.ingest")
                .exchange("chat.direct")
                .routingKey("ingest.task")
                .headers(objectMapper.writeValueAsString(MqMessageHeaders.toMap(identity)))
                .status("FAILED")
                .retryCount(3)
                .lastError("boom")
                .createdAt(LocalDateTime.of(2026, 3, 30, 12, 0))
                .build();
    }

    private GetResponse sampleGetResponse() throws Exception {
        MqMessageIdentity identity = new MqMessageIdentity(
                "event-1",
                "doc-1",
                "trace-1",
                "knowledge.ingest",
                null,
                "chat.direct",
                "ingest.task",
                Instant.parse("2026-03-30T00:00:00Z"),
                3
        );
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "knowledgeBaseId", "kb-1",
                "documentId", "doc-1",
                "clearExistingContentFirst", false
        ));
        AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .contentEncoding("UTF-8")
                .headers(MqMessageHeaders.toMap(identity))
                .build();
        return new GetResponse(
                new Envelope(7L, false, properties.getExchanges().getDlxDirect(), properties.getRoutingKeys().getDeadLetter()),
                basicProperties,
                body,
                0
        );
    }

    private Properties queueDepth(long depth) {
        Properties properties = new Properties();
        properties.put("QUEUE_MESSAGE_COUNT", depth);
        return properties;
    }
}
