package com.yulong.chatagent.rag.vector.milvus;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Collection-level settings for the knowledge-base Milvus index.
 */
@ConfigurationProperties(prefix = "milvus.knowledge-base")
@Data
public class KnowledgeBaseMilvusProperties {

    private String collection = "chat_knowledge_chunk";
}
