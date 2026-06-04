package com.yulong.chatagent.rag.retrieve;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BgeHttpRetrievalRerankerTest {

    private static final String DEADLINE_HEADER = "X-Reranker-Deadline-Epoch-Ms";

    private RerankerProperties properties;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new RerankerProperties();
        properties.setProvider("bge-http");
        properties.setBaseUrl("http://localhost:7997");
        properties.setPath("/rerank");
        properties.setReadyPath("/ready");
        properties.setMaxCandidates(5);
        properties.setMinimumRequestVolume(2);
        properties.setFailureThreshold(1);
        properties.setFailureRateThresholdPercent(1);
        properties.setReadyProbeTimeoutMs(200);
        properties.setRetryConnectErrors(0);

        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void testRerankWithFewCandidatesReturnsOriginal() {
        BgeHttpRetrievalReranker reranker = createReranker(request -> {
            fail("No HTTP call should be made when candidate count is below 2");
            return Mono.empty();
        });

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("single").score(0.42d).build()
        );
        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertSame(candidates, result);
        assertEquals("retrieval", result.get(0).scoreType());
    }

    @Test
    void testFallbackWhenCircuitOpen() {
        properties.setMinimumRequestVolume(1);

        AtomicInteger rerankCalls = new AtomicInteger();
        BgeHttpRetrievalReranker reranker = createReranker(request -> {
            if ("/rerank".equals(request.url().getPath())) {
                rerankCalls.incrementAndGet();
            }
            return Mono.error(new RuntimeException("Connection refused"));
        });

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("1").score(0.9).build(),
                MilvusSearchHit.builder().chunkId("2").score(0.8).build()
        );

        reranker.rerank("query", candidates);
        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals(2, result.size());
        assertEquals(1, rerankCalls.get());
        assertNull(result.get(0).score());
        assertNull(result.get(1).score());
        assertEquals("fallback", result.get(0).scoreType());
        assertEquals("fallback", result.get(1).scoreType());
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "circuit_open").count());
    }

    @Test
    void testSuccessfulRerankSendsDeadlineHeader() {
        AtomicReference<String> capturedDeadline = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        BgeHttpRetrievalReranker reranker = createReranker(request -> {
            capturedPath.set(request.url().getPath());
            capturedDeadline.set(request.headers().getFirst(DEADLINE_HEADER));
            return Mono.just(jsonResponse("""
                    {"results":[
                      {"index":1,"relevance_score":0.95},
                      {"index":0,"relevance_score":0.85}
                    ]}
                    """));
        });

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
        );

        long before = System.currentTimeMillis();
        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals("/rerank", capturedPath.get());
        assertNotNull(capturedDeadline.get());
        assertTrue(Long.parseLong(capturedDeadline.get()) >= before);
        assertEquals(2, result.size());
        assertEquals("id1", result.get(0).chunkId());
        assertEquals("id0", result.get(1).chunkId());
        assertEquals(0.95d, result.get(0).score());
        assertEquals(0.85d, result.get(1).score());
        assertEquals("reranker", result.get(0).scoreType());
        assertEquals("reranker", result.get(1).scoreType());
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "success").count());
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.attempts", "provider", "bge-http").count());
    }

    @Test
    void testBuildDocumentIncludesDocumentSignals() {
        BgeHttpRetrievalReranker reranker = createReranker(request -> Mono.just(jsonResponse("""
                {"results":[{"index":0,"relevance_score":0.95}]}
                """)));

        String document = reranker.buildDocument(MilvusSearchHit.builder()
                .chunkId("id0")
                .content("Employees may carry over up to five days.")
                .contextText("Annual leave section")
                .documentKeywords(List.of("leave policy"))
                .documentQuestions(List.of("How many leave days can be carried over?"))
                .build());

        assertTrue(document.contains("Document Keywords: leave policy"));
        assertTrue(document.contains("Document Questions: How many leave days can be carried over?"));
    }

    @Test
    void testLowConfidenceRerankMarksHitsAsFiltered() {
        properties.setScoreThreshold(0.15d);
        properties.setEnableConfidenceFilter(true);
        BgeHttpRetrievalReranker reranker = createReranker(request -> Mono.just(jsonResponse("""
                {"results":[
                  {"index":0,"relevance_score":0.12},
                  {"index":1,"relevance_score":0.08}
                ]}
                """)));

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
        );

        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals(2, result.size());
        assertEquals(0.12d, result.get(0).score());
        assertEquals(0.08d, result.get(1).score());
        assertEquals("reranker", result.get(0).scoreType());
        assertEquals("reranker", result.get(1).scoreType());
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "low_confidence").count());
    }

    @Test
    void testLowConfidenceFilterCanBeDisabled() {
        properties.setScoreThreshold(0.15d);
        properties.setEnableConfidenceFilter(false);
        BgeHttpRetrievalReranker reranker = createReranker(request -> Mono.just(jsonResponse("""
                {"results":[
                  {"index":0,"relevance_score":0.12},
                  {"index":1,"relevance_score":0.08}
                ]}
                """)));

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
        );

        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals(0.12d, result.get(0).score());
        assertEquals(0.08d, result.get(1).score());
        assertEquals("reranker", result.get(0).scoreType());
        assertEquals("reranker", result.get(1).scoreType());
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "success").count());
    }

    @Test
    void testTimeoutReturnsFallbackInOriginalOrder() {
        BgeHttpRetrievalReranker reranker = createReranker(request -> Mono.error(new TimeoutException("request timeout")));

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
        );

        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals(List.of("id0", "id1"), result.stream().map(MilvusSearchHit::chunkId).toList());
        assertThatAllFallback(result);
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "timeout").count());
    }

    @Test
    void testMissingScoresReturnFallbackInOriginalOrder() {
        BgeHttpRetrievalReranker reranker = createReranker(request -> Mono.just(jsonResponse("""
                {"results":[
                  {"index":0},
                  {"index":1}
                ]}
                """)));

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
        );

        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals(List.of("id0", "id1"), result.stream().map(MilvusSearchHit::chunkId).toList());
        assertThatAllFallback(result);
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "parse_error").count());
    }

    @Test
    void testConnectErrorRetriesOnceAndSucceeds() {
        properties.setRetryConnectErrors(1);
        AtomicInteger calls = new AtomicInteger();
        BgeHttpRetrievalReranker reranker = createReranker(request -> {
            if (calls.incrementAndGet() == 1) {
                return Mono.error(new ConnectException("Connection refused"));
            }
            return Mono.just(jsonResponse("""
                    {"results":[
                      {"index":1,"relevance_score":0.93},
                      {"index":0,"relevance_score":0.81}
                    ]}
                    """));
        });

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
        );

        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals(2, calls.get());
        assertEquals("id1", result.get(0).chunkId());
        assertEquals(0.93d, result.get(0).score());
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "connect_retry_success").count());
        assertEquals(2.0, meterRegistry.counter("chatagent.reranker.attempts", "provider", "bge-http").count());
    }

    @Test
    void testConnectErrorRetriesOnceAndThenFallsBack() {
        properties.setRetryConnectErrors(1);
        AtomicInteger calls = new AtomicInteger();
        BgeHttpRetrievalReranker reranker = createReranker(request -> {
            calls.incrementAndGet();
            return Mono.error(new ConnectException("Connection refused"));
        });

        List<MilvusSearchHit> candidates = List.of(
                MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
        );

        List<MilvusSearchHit> result = reranker.rerank("query", candidates);

        assertEquals(2, calls.get());
        assertEquals(List.of("id0", "id1"), result.stream().map(MilvusSearchHit::chunkId).toList());
        assertThatAllFallback(result);
        assertEquals(1.0, meterRegistry.counter("chatagent.reranker.requests", "provider", "bge-http", "outcome", "connect_retry_failed").count());
        assertEquals(2.0, meterRegistry.counter("chatagent.reranker.attempts", "provider", "bge-http").count());
    }

    @Test
    void testHalfOpenProbeChecksReadyEndpointBeforeRetrying() {
        properties.setMinimumRequestVolume(1);
        properties.setOpenStateMs(0);
        AtomicInteger rerankCalls = new AtomicInteger();
        AtomicInteger readyCalls = new AtomicInteger();
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/ready", exchange -> {
                readyCalls.incrementAndGet();
                writeJson(exchange, 200, "{}");
            });
            server.createContext("/rerank", exchange -> {
                int call = rerankCalls.incrementAndGet();
                if (call == 1) {
                    writeJson(exchange, 500, "{\"error\":\"temporary failure\"}");
                    return;
                }
                writeJson(exchange, 200, """
                        {"results":[
                          {"index":1,"relevance_score":0.99},
                          {"index":0,"relevance_score":0.77}
                        ]}
                        """);
            });
            server.start();

            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            BgeHttpRetrievalReranker reranker = new BgeHttpRetrievalReranker(WebClient.builder(), properties, meterRegistry);

            List<MilvusSearchHit> candidates = List.of(
                    MilvusSearchHit.builder().chunkId("id0").score(0.9).content("doc0").build(),
                    MilvusSearchHit.builder().chunkId("id1").score(0.8).content("doc1").build()
            );

            List<MilvusSearchHit> firstResult = reranker.rerank("query", candidates);
            List<MilvusSearchHit> secondResult = reranker.rerank("query", candidates);

            assertEquals("fallback", firstResult.get(0).scoreType());
            assertEquals(1, readyCalls.get());
            assertEquals(2, rerankCalls.get());
            assertEquals("id1", secondResult.get(0).chunkId());
            assertEquals(0.99d, secondResult.get(0).score());
            assertEquals("reranker", secondResult.get(0).scoreType());
        } catch (IOException e) {
            fail("Failed to start test HTTP server", e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private BgeHttpRetrievalReranker createReranker(ExchangeFunction exchangeFunction) {
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .exchangeFunction(exchangeFunction)
                .build();
        return new BgeHttpRetrievalReranker(webClient, properties, meterRegistry);
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private void assertThatAllFallback(List<MilvusSearchHit> result) {
        assertEquals(2, result.size());
        assertNull(result.get(0).score());
        assertNull(result.get(1).score());
        assertEquals("fallback", result.get(0).scoreType());
        assertEquals("fallback", result.get(1).scoreType());
    }

    private void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
