package com.yulong.chatagent.rag.embedding;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ollama embedding client with explicit bounded connection-pool lifecycle.
 *
 * <p>Uses a dedicated singleton {@link ConnectionProvider} with capped connections,
 * finite pending queue, connect/response timeouts, eager-eviction configuration,
 * and diagnostic error-body extraction.
 * Supports single-text {@code /api/embeddings} with NaN-only controlled fallback
 * and batch {@code /api/embed}.
 */
@Component
public class OllamaEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final WebClient webClient;
    private final String embeddingModel;
    private static final int MAX_EMBEDDING_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** Count of NaN-only zero-vector fallbacks since client creation. */
    private final AtomicLong nanFallbackCount = new AtomicLong(0);

    /**
     * Shared connection provider with bounded pool and finite pending queue.
     * <p>Per Spring's singleton default, one provider serves the application lifetime.
     */
    private static ConnectionProvider ollamaConnectionProvider() {
        return ConnectionProvider.builder("ollama-embed")
                .maxConnections(4)
                .pendingAcquireMaxCount(8)
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .maxIdleTime(Duration.ofSeconds(60))
                .maxLifeTime(Duration.ofMinutes(10))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
    }

    public OllamaEmbeddingClient(
            WebClient.Builder builder,
            @Value("${rag.embedding.base-url:http://localhost:11434}") String baseUrl,
            @Value("${rag.embedding.model:bge-m3}") String embeddingModel
    ) {
        HttpClient httpClient = HttpClient.create(ollamaConnectionProvider())
                .responseTimeout(Duration.ofSeconds(60))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);

        this.webClient = builder
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs()
                                .maxInMemorySize(MAX_EMBEDDING_RESPONSE_BYTES))
                        .build())
                .build();
        this.embeddingModel = embeddingModel;
    }

    /** Returns the cumulative NaN fallback count for diagnostics. */
    public long nanFallbackCount() {
        return nanFallbackCount.get();
    }

    // ── Single-text embedding ──────────────────────────────────────────

    private static final float[] ZERO_VECTOR = new float[1024];

    /**
     * Embed a single text via {@code POST /api/embeddings}.
     *
     * <p>Only {@code NaN} in the embedding vector triggers a controlled fallback
     * (zero vector + counter increment). HTTP errors, connection failures, and
     * timeouts are re-thrown so callers can decide whether to retry or fail.
     *
     * @return a {@code float[1024]} embedding vector
     * @throws RuntimeException on HTTP / connection / timeout errors
     */
    public float[] embed(String text) {

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                float[] vec = embedOnce(text);
                if (!hasNaN(vec)) {
                    return vec;
                }
            } catch (RuntimeException e) {
                if (!isOllamaNanEncodingFailure(e)) {
                    throw e;
                }
            }
            sleepBeforeRetry(attempt);
        }

        // --- Controlled NaN fallback ---
        nanFallbackCount.incrementAndGet();
        log.warn("Ollama NaN fallback: model={} inputLen={} inputSha256={} cumulativeFallbacks={}",
                embeddingModel, text.length(), sha256(text), nanFallbackCount.get());
        return ZERO_VECTOR;
    }

    /** Single attempt — throws on HTTP/connection errors, returns raw vector. */
    private float[] embedOnce(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of("model", embeddingModel, "prompt", text))
                .retrieve()
                .onStatus(status -> status.isError(), this::readErrorBody)
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        float[] vec = resp.getEmbedding();
        Assert.notNull(vec, "Embedding vector cannot be null");
        return vec;
    }

    private static void sleepBeforeRetry(int attempt) {
        if (attempt >= 2) {
            return;
        }
        try {
            Thread.sleep(attempt == 0 ? 200 : 400);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isOllamaNanEncodingFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.contains("Ollama HTTP 500")
                    && message.contains("unsupported value: NaN")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasNaN(float[] vec) {
        for (float v : vec) {
            if (Float.isNaN(v)) return true;
        }
        return false;
    }

    // ── Batch embedding ────────────────────────────────────────────────

    /**
     * Embed a batch of texts via {@code POST /api/embed}.
     *
     * <p>Validates response length matches input size, every vector has
     * dimension 1024, and no element is NaN or Infinity.
     *
     * @param texts non-empty list of input texts (max ~512 per batch)
     * @return float[][] where result[i] is the embedding for texts[i]
     * @throws RuntimeException on HTTP errors, length mismatch, NaN, or Infinity
     */
    public float[][] embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return new float[0][];
        }
        BatchEmbeddingResponse resp = webClient.post()
                .uri("/api/embed")
                .bodyValue(Map.of("model", embeddingModel, "input", texts))
                .retrieve()
                .onStatus(status -> status.isError(), this::readErrorBody)
                .bodyToMono(BatchEmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Batch embedding response cannot be null");
        Assert.notNull(resp.getEmbeddings(), "Batch embedding list cannot be null");
        float[][] results = resp.getEmbeddings();
        Assert.isTrue(results.length == texts.size(),
                () -> "Batch embedding count mismatch: expected " + texts.size()
                        + ", got " + results.length);
        for (int i = 0; i < results.length; i++) {
            int idx = i;
            float[] vector = results[idx];
            Assert.notNull(vector, () -> "Batch embedding[" + idx + "] is null");
            Assert.isTrue(vector.length == 1024,
                    () -> "Batch embedding[" + idx + "] dimension: expected 1024, got "
                            + vector.length);
            for (int j = 0; j < vector.length; j++) {
                int dimIdx = j;
                float v = vector[dimIdx];
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    throw new IllegalArgumentException(
                            "Batch embedding[" + idx + "][" + dimIdx + "] = " + v);
                }
            }
        }
        return results;
    }

    // ── Error handling ─────────────────────────────────────────────────

    private Mono<? extends Throwable> readErrorBody(
            org.springframework.web.reactive.function.client.ClientResponse response
    ) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("<no body>")
                .flatMap(body -> Mono.error(new RuntimeException(
                        "Ollama HTTP " + response.statusCode().value() + " — " + body)));
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    // ── Response DTOs ──────────────────────────────────────────────────

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    @Data
    private static class BatchEmbeddingResponse {
        private float[][] embeddings;
    }
}
