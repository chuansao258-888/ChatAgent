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
 * 知识库 ingestion MQ 消费者。
 *
 * 它和 agent.run 走同一个 AbstractRetryingMqConsumer 模板，
 * 但业务动作不同：消费到文档入库任务后，执行解析、切块、向量化、写 Milvus。
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
        // 具体 ack/retry/DLQ/幂等锁逻辑全部交给父类模板。
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
        // ingestion 失败通常和外部模型/向量库/临时 IO 有关，最多显式重试 3 次。
        return 3;
    }

    @Override
    protected boolean isRetryable(Exception exception) {
        // 只有明确标记为可重试的 ingestion 异常才进 retry queue；
        // 参数错误、文档不存在、绑定关系错误都属于终局失败。
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
            // 重新入库时先清旧 chunk 和 Milvus 向量，避免同一文档重复检索出旧内容。
            knowledgeChunkRepository.deleteByKnowledgeDocumentId(knowledgeDocument.getId());
            knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(knowledgeDocument.getId());
        }
        // ingestSync 是真正的同步入库流程；MQ 只是把这个耗时动作异步化。
        knowledgeDocumentIngestionService.ingestSync(payload.knowledgeBaseId(), knowledgeDocument);
        log.info("Knowledge ingest task processed: eventId={}, documentId={}",
                identity.eventId(), knowledgeDocument.getId());
    }

    private KnowledgeDocumentDTO loadDocument(KnowledgeIngestTaskPayload payload) {
        if (payload == null || !StringUtils.hasText(payload.knowledgeBaseId()) || !StringUtils.hasText(payload.documentId())) {
            throw new IllegalArgumentException("Invalid knowledge ingest payload");
        }
        // 当前是单库场景：文档缺失/已删除/知识库不匹配不是临时错误，重试也不会恢复，所以直接终局失败。
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
