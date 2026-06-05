package com.yulong.chatagent.rag.vector.milvus;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Collection-level settings for the user-memory Milvus index (L3 long-term memory recall).
 */
@ConfigurationProperties(prefix = "milvus.user-memory")
@Data
public class UserMemoryMilvusProperties {

    private String collection = "chat_user_memory";
}
