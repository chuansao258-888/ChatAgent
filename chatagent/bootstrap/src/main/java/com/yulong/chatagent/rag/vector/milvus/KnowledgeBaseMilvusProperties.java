package com.yulong.chatagent.rag.vector.milvus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Collection-level settings for the knowledge-base Milvus index.
 */
@ConfigurationProperties(prefix = "milvus.knowledge-base")
public class KnowledgeBaseMilvusProperties {

    private String collection = "chat_knowledge_chunk";

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }
}
