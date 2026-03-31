package com.yulong.chatagent.rag.vector.milvus;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for Milvus connectivity and collection defaults.
 */
@ConfigurationProperties(prefix = "milvus")
@Data
public class MilvusProperties {

    private boolean enabled = false;
    private String host = "localhost";
    private int port = 19530;
    private String database = "default";
    private String collection = "chat_file_chunk";
    private String username = "";
    private String password = "";
    private String metricType = "COSINE";
    private String indexType = "AUTOINDEX";
    private String consistencyLevel = "BOUNDED";
    private int dimension = 1024;
    /**
     * Enables the sparse BM25 field/function pair in the collection schema.
     */
    private boolean bm25Enabled = false;
}
