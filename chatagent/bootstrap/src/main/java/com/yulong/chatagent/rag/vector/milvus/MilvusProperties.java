package com.yulong.chatagent.rag.vector.milvus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for Milvus connectivity and collection defaults.
 */
@ConfigurationProperties(prefix = "milvus")
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public String getIndexType() {
        return indexType;
    }

    public void setIndexType(String indexType) {
        this.indexType = indexType;
    }

    public String getConsistencyLevel() {
        return consistencyLevel;
    }

    public void setConsistencyLevel(String consistencyLevel) {
        this.consistencyLevel = consistencyLevel;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public boolean isBm25Enabled() {
        return bm25Enabled;
    }

    public void setBm25Enabled(boolean bm25Enabled) {
        this.bm25Enabled = bm25Enabled;
    }
}
