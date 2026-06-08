package com.yulong.chatagent.rag.embedding;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Long-run regression for OllamaEmbeddingClient connection resilience.
 *
 * <p>Phase 10a prerequisite: proves the hardened client completes 1200
 * sequential embedding calls without connection-pool exhaustion and with
 * zero (or strictly bounded) NaN fallbacks. No Spring context, no RAG.
 *
 * <p>Tagged {@code eval-real}; requires a running local Ollama instance
 * with bge-m3 model pulled.
 */
@Tag("eval-v2")
@Tag("eval-real")
class OllamaEmbeddingClientLongRunTest {

    private static final int EMBEDDING_DIMENSION = 1024;
    private static final int LONG_RUN_COUNT = 1200;
    /** Maximum allowed NaN fallbacks in a long run (0.5% of 1200). */
    private static final int MAX_NAN_FALLBACKS = 6;

    private static final String OLLAMA_BASE_URL = setting(
            "chatagent.eval.ollamaBaseUrl",
            "CHATAGENT_RAG_EMBEDDING_BASE_URL",
            "http://127.0.0.1:11434"
    );
    private static final String EMBEDDING_MODEL = "bge-m3";

    private static String setting(String property, String env, String defaultValue) {
        String v = System.getProperty(property);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(env);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    private static boolean ollamaAvailable;

    @BeforeAll
    static void probeOllama() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_BASE_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            ollamaAvailable = response.statusCode() == 200;
        } catch (Exception e) {
            ollamaAvailable = false;
        }
    }

    @Test
    void longRunCompletesWithoutConnectionExhaustion() {
        assumeTrue(ollamaAvailable,
                "Ollama not available at " + OLLAMA_BASE_URL + "; skipping long-run regression");

        OllamaEmbeddingClient client = new OllamaEmbeddingClient(
                WebClient.builder(),
                OLLAMA_BASE_URL,
                EMBEDDING_MODEL
        );

        int failCount = 0;
        long nanFallbacksBefore = client.nanFallbackCount();

        for (int i = 0; i < LONG_RUN_COUNT; i++) {
            try {
                float[] embedding = client.embed("long-run probe " + i);
                assertEquals(EMBEDDING_DIMENSION, embedding.length,
                        "Embedding dimension mismatch at call " + i);
            } catch (Exception e) {
                failCount++;
                System.err.printf("[Ollama long-run] Call %d failed: %s — %s%n",
                        i, e.getClass().getSimpleName(), e.getMessage());
            }
        }

        long nanFallbacks = client.nanFallbackCount() - nanFallbacksBefore;

        System.out.printf(
                "[Ollama long-run] %d/%d succeeded, %d failed, %d NaN fallbacks%n",
                LONG_RUN_COUNT - failCount, LONG_RUN_COUNT, failCount, nanFallbacks
        );

        assertEquals(0, failCount,
                "All " + LONG_RUN_COUNT + " embedding calls must succeed; "
                        + failCount + " failed");
        assertTrue(nanFallbacks <= MAX_NAN_FALLBACKS,
                "NaN fallbacks must be ≤ " + MAX_NAN_FALLBACKS
                        + " (got " + nanFallbacks + ")");
    }

    @Test
    void singleEmbedReturnsValidDimension() {
        assumeTrue(ollamaAvailable,
                "Ollama not available at " + OLLAMA_BASE_URL + "; skipping");

        OllamaEmbeddingClient client = new OllamaEmbeddingClient(
                WebClient.builder(),
                OLLAMA_BASE_URL,
                EMBEDDING_MODEL
        );

        float[] embedding = client.embed("single probe");
        assertEquals(EMBEDDING_DIMENSION, embedding.length,
                "Single embed should return " + EMBEDDING_DIMENSION + "-dim vector");
    }
}
