package com.yulong.chatagent.rag.retrieve;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.retrieval.reranker")
/**
 * External configuration for retrieval reranking providers.
 */
@Data
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
}
