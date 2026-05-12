package com.yulong.chatagent.mq.outbox.event;

/**
 * knowledge.ingest.task 的业务 payload。
 *
 * identity/header 负责“这是谁、怎么重试、怎么幂等”，
 * payload 只负责“这次要入库哪个文档”：
 * 1. knowledgeBaseId：目标知识库；
 * 2. documentId：要解析/切块/向量化的文档；
 * 3. clearExistingContentFirst：重新入库前是否先清理旧 chunk 和旧向量。
 */
public record KnowledgeIngestTaskPayload(
        String knowledgeBaseId,
        String documentId,
        boolean clearExistingContentFirst
) {
}
