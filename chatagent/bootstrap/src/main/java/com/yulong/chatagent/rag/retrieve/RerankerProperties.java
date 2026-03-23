package com.yulong.chatagent.rag.retrieve;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.retrieval.reranker")
/**
 * External configuration for retrieval reranking providers.
 */
public class RerankerProperties {

    private String provider = "none";
    private String modelId = "BAAI/bge-reranker-v2-m3";
    /**
     * Upper bound on the number of fused candidates sent to the reranker.
     */
    private int maxCandidates = 8;
    /**
     * Maximum characters per candidate when building reranker input payloads.
     */
    private int maxChunkChars = 900;
    private String baseUrl = "http://localhost:7997";
    private String path = "/rerank";
    private String apiKey;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }

    public void setMaxChunkChars(int maxChunkChars) {
        this.maxChunkChars = maxChunkChars;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
