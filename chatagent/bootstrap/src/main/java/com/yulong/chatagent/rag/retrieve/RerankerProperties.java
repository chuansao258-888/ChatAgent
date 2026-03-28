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
    private String readyPath = "/ready";
    private String apiKey;

    private int maxConnections = 10;
    private int connectTimeoutMs = 300;
    private int responseTimeoutMs = 1800;
    private int readTimeoutMs = 1800;
    private int writeTimeoutMs = 1000;
    private int pendingAcquireTimeoutMs = 200;
    private int readyProbeTimeoutMs = 200;
    private int retryConnectErrors = 1;
    private boolean enableConfidenceFilter = true;
    private double scoreThreshold = 0.15d;

    // Circuit breaker properties
    private int failureThreshold = 5;
    private int failureRateThresholdPercent = 50;
    private int minimumRequestVolume = 10;
    private long openStateMs = 30000;
    private int halfOpenProbeCount = 1;

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

    public String getReadyPath() {
        return readyPath;
    }

    public void setReadyPath(String readyPath) {
        this.readyPath = readyPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getResponseTimeoutMs() {
        return responseTimeoutMs;
    }

    public void setResponseTimeoutMs(int responseTimeoutMs) {
        this.responseTimeoutMs = responseTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getWriteTimeoutMs() {
        return writeTimeoutMs;
    }

    public void setWriteTimeoutMs(int writeTimeoutMs) {
        this.writeTimeoutMs = writeTimeoutMs;
    }

    public int getPendingAcquireTimeoutMs() {
        return pendingAcquireTimeoutMs;
    }

    public void setPendingAcquireTimeoutMs(int pendingAcquireTimeoutMs) {
        this.pendingAcquireTimeoutMs = pendingAcquireTimeoutMs;
    }

    public int getReadyProbeTimeoutMs() {
        return readyProbeTimeoutMs;
    }

    public void setReadyProbeTimeoutMs(int readyProbeTimeoutMs) {
        this.readyProbeTimeoutMs = readyProbeTimeoutMs;
    }

    public int getRetryConnectErrors() {
        return retryConnectErrors;
    }

    public void setRetryConnectErrors(int retryConnectErrors) {
        this.retryConnectErrors = retryConnectErrors;
    }

    public boolean isEnableConfidenceFilter() {
        return enableConfidenceFilter;
    }

    public void setEnableConfidenceFilter(boolean enableConfidenceFilter) {
        this.enableConfidenceFilter = enableConfidenceFilter;
    }

    public double getScoreThreshold() {
        return scoreThreshold;
    }

    public void setScoreThreshold(double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getMinimumRequestVolume() {
        return minimumRequestVolume;
    }

    public void setMinimumRequestVolume(int minimumRequestVolume) {
        this.minimumRequestVolume = minimumRequestVolume;
    }

    public int getFailureRateThresholdPercent() {
        return failureRateThresholdPercent;
    }

    public void setFailureRateThresholdPercent(int failureRateThresholdPercent) {
        this.failureRateThresholdPercent = failureRateThresholdPercent;
    }

    public long getOpenStateMs() {
        return openStateMs;
    }

    public void setOpenStateMs(long openStateMs) {
        this.openStateMs = openStateMs;
    }

    public int getHalfOpenProbeCount() {
        return halfOpenProbeCount;
    }

    public void setHalfOpenProbeCount(int halfOpenProbeCount) {
        this.halfOpenProbeCount = halfOpenProbeCount;
    }
}
