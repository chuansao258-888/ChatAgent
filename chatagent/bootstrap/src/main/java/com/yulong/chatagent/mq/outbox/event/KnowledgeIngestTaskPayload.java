package com.yulong.chatagent.mq.outbox.event;

/**
 * Payload for the {@code knowledge.ingest.task} MQ event.
 */
public record KnowledgeIngestTaskPayload(
        String knowledgeBaseId,
        String documentId,
        boolean clearExistingContentFirst
) {
}
