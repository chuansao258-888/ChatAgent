package com.yulong.chatagent.rag.retrieve;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Redis cache settings for knowledge-document rerank signals.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rag.retrieval.document-signals")
@Data
public class KnowledgeDocumentSignalProperties {

    private long cacheTtlMinutes = 30;
}
