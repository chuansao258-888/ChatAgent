package com.yulong.chatagent.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.lock.DistributedLockManager;
import com.yulong.chatagent.mq.lock.LockWatchdog;
import com.yulong.chatagent.mq.outbox.event.KnowledgeIngestTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.mq.support.RabbitMqMessagePublisher;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
import com.yulong.chatagent.rag.ingestion.RetryableKnowledgeDocumentIngestionException;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * Consumes staged knowledge-ingest tasks from RabbitMQ and preserves the retry/DLQ header contract.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class KnowledgeIngestTaskListener extends AbstractRetryingMqConsumer<KnowledgeIngestTaskPayload> {

    private final ObjectMapper objectMapper;
    private final ChatAgentMqProperties properties;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;
    private final KnowledgeDocumentIngestionService knowledgeDocumentIngestionService;

    public KnowledgeIngestTaskListener(ObjectMapper objectMapper,
                                       ChatAgentMqProperties properties,
                                       RabbitMqMessagePublisher rabbitMqMessagePublisher,
                                       DistributedLockManager distributedLockManager,
                                       LockWatchdog lockWatchdog,
                                       KnowledgeDocumentRepository knowledgeDocumentRepository,
                                       KnowledgeChunkRepository knowledgeChunkRepository,
                                       KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer,
                                       KnowledgeDocumentIngestionService knowledgeDocumentIngestionService) {
        super(properties, rabbitMqMessagePublisher, distributedLockManager, lockWatchdog);
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.knowledgeBaseMilvusIndexer = knowledgeBaseMilvusIndexer;
        this.knowledgeDocumentIngestionService = knowledgeDocumentIngestionService;
    }

    @RabbitListener(
            queues = "${chatagent.mq.queues.knowledge-ingest-task:knowledge.ingest.task}",
            containerFactory = "knowledgeIngestListenerContainerFactory"
    )
    public void handle(Message message, Channel channel) throws IOException {
        consume(message, channel);
    }

    @Override
    protected String retryExchange() {
        return properties.getExchanges().getRetryDirect();
    }

    @Override
    protected String retryRoutingKey() {
        return properties.getRoutingKeys().getRetryIngest();
    }

    @Override
    protected int maxRetryCount() {
        return 3;
    }

    @Override
    protected boolean isRetryable(Exception exception) {
        return exception instanceof RetryableKnowledgeDocumentIngestionException;
    }

    @Override
    protected KnowledgeIngestTaskPayload deserializePayload(Message message) throws Exception {
        return objectMapper.readValue(message.getBody(), KnowledgeIngestTaskPayload.class);
    }

    @Override
    protected void processTask(KnowledgeIngestTaskPayload payload, MqMessageIdentity identity) {
        KnowledgeDocumentDTO knowledgeDocument = loadDocument(payload);
        if (payload.clearExistingContentFirst()) {
            knowledgeChunkRepository.deleteByKnowledgeDocumentId(knowledgeDocument.getId());
            knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(knowledgeDocument.getId());
        }
        knowledgeDocumentIngestionService.ingestSync(payload.knowledgeBaseId(), knowledgeDocument);
        log.info("Knowledge ingest task processed: eventId={}, documentId={}",
                identity.eventId(), knowledgeDocument.getId());
    }

    private KnowledgeDocumentDTO loadDocument(KnowledgeIngestTaskPayload payload) {
        if (payload == null || !StringUtils.hasText(payload.knowledgeBaseId()) || !StringUtils.hasText(payload.documentId())) {
            throw new IllegalArgumentException("Invalid knowledge ingest payload");
        }
        // In the current single-database rollout, a missing/deleted/misbound document is terminal rather than retryable.
        KnowledgeDocumentDTO knowledgeDocument = knowledgeDocumentRepository.findById(payload.documentId());
        if (knowledgeDocument == null) {
            throw new IllegalArgumentException("Knowledge document not found: " + payload.documentId());
        }
        if (Boolean.TRUE.equals(knowledgeDocument.getDeleted())) {
            throw new IllegalArgumentException("Knowledge document is deleted: " + payload.documentId());
        }
        if (!payload.knowledgeBaseId().equals(knowledgeDocument.getKnowledgeBaseId())) {
            throw new IllegalArgumentException("Knowledge document does not belong to knowledge base: " + payload.documentId());
        }
        return knowledgeDocument;
    }
}
