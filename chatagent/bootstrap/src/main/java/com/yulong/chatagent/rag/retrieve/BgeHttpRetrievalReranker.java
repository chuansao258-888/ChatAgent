package com.yulong.chatagent.rag.retrieve;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.trace.TraceContext;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for a locally deployed BGE reranker service.
 * Refactored for Stage 1/2/3 optimization: timeout, circuit breaker, retry, metrics and confidence filtering.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "rag.retrieval.reranker", name = "provider", havingValue = "bge-http")
@Slf4j
public class BgeHttpRetrievalReranker implements RetrievalReranker {

    private static final String DEADLINE_HEADER = "X-Reranker-Deadline-Epoch-Ms";
    private static final String PROVIDER_TAG_VALUE = "bge-http";

    private final WebClient webClient;
    private final RerankerProperties properties;
    private final RerankerCircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;
    private final ConnectionProvider connectionProvider;

    @Autowired
    public BgeHttpRetrievalReranker(WebClient.Builder builder, RerankerProperties properties, MeterRegistry meterRegistry) {
        this(buildDedicatedClientResources(builder, properties), properties, meterRegistry);
    }

    private BgeHttpRetrievalReranker(ClientResources resources, RerankerProperties properties, MeterRegistry meterRegistry) {
        this(resources.webClient(), properties, meterRegistry, resources.connectionProvider());
    }

    BgeHttpRetrievalReranker(WebClient webClient, RerankerProperties properties, MeterRegistry meterRegistry) {
        this(webClient, properties, meterRegistry, null);
    }

    private BgeHttpRetrievalReranker(WebClient webClient,
                                     RerankerProperties properties,
                                     MeterRegistry meterRegistry,
                                     ConnectionProvider connectionProvider) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.circuitBreaker = new RerankerCircuitBreaker(PROVIDER_TAG_VALUE, properties);
        this.webClient = webClient;
        this.connectionProvider = connectionProvider;
        Gauge.builder("chatagent.reranker.circuit.state", circuitBreaker, RerankerCircuitBreaker::stateMetricValue)
                .tag("provider", PROVIDER_TAG_VALUE)
                .register(meterRegistry);
    }

    /**
     * Builds an isolated WebClient and connection pool so reranker traffic does not interfere with other outbound calls.
     */
    private static ClientResources buildDedicatedClientResources(WebClient.Builder builder, RerankerProperties props) {
        ConnectionProvider provider = ConnectionProvider.builder("reranker-pool")
                .maxConnections(props.getMaxConnections())
                .pendingAcquireTimeout(Duration.ofMillis(props.getPendingAcquireTimeoutMs()))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs())
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(props.getReadTimeoutMs(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(props.getWriteTimeoutMs(), TimeUnit.MILLISECONDS)))
                .responseTimeout(Duration.ofMillis(props.getResponseTimeoutMs()));

        WebClient webClient = builder
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        return new ClientResources(webClient, provider);
    }

    @PreDestroy
    void destroy() {
        if (connectionProvider != null) {
            connectionProvider.disposeLater().block(Duration.ofSeconds(5));
        }
    }

    @Override
    public List<MilvusSearchHit> rerank(String queryText, List<MilvusSearchHit> candidates) {
        if (!StringUtils.hasText(queryText) || candidates == null || candidates.size() < 2) {
            return candidates;
        }

        if (!circuitBreaker.allowRequest()) {
            recordOutcome("circuit_open", 0, candidates.size(), 0);
            log.warn("BGE rerank skipped (Circuit OPEN): traceId={}, breakerState={}",
                    TraceContext.getTraceId(), circuitBreaker.getState());
            return markAsFallback(candidates);
        }

        if (circuitBreaker.getState() == RerankerCircuitBreaker.State.HALF_OPEN) {
            long readinessDeadline = System.currentTimeMillis() + properties.getReadyProbeTimeoutMs();
            if (!isRerankerReady(readinessDeadline)) {
                recordOutcome("not_ready", 0, candidates.size(), 0);
                circuitBreaker.recordFailure();
                log.warn("BGE rerank skipped (service not ready during HALF_OPEN): traceId={}, breakerState={}",
                        TraceContext.getTraceId(), circuitBreaker.getState());
                return markAsFallback(candidates);
            }
        }

        int limit = Math.min(candidates.size(), Math.max(2, properties.getMaxCandidates()));
        List<MilvusSearchHit> rerankCandidates = new ArrayList<>(candidates.subList(0, limit));
        List<String> documents = rerankCandidates.stream()
                .map(this::buildDocument)
                .toList();

        int payloadChars = documents.stream().mapToInt(String::length).sum() + queryText.length();
        BgeRerankRequest request = new BgeRerankRequest(
                properties.getModelId(),
                queryText,
                documents,
                limit,
                false
        );

        Timer.Sample sample = Timer.start(meterRegistry);
        long startTime = System.nanoTime();
        AtomicInteger attempts = new AtomicInteger(0);

        try {
            log.info("BGE rerank started: traceId={}, modelId={}, candidateCount={}, payloadChars={}, breakerState={}",
                    TraceContext.getTraceId(), properties.getModelId(), rerankCandidates.size(), payloadChars, circuitBreaker.getState());

            BgeRerankResponse response = executeWithRetry(request, attempts);

            if (response == null) {
                String outcome = attempts.get() > 1 ? "connect_retry_failed" : "fallback";
                recordOutcome(outcome, sample, payloadChars, candidates.size(), 0);
                return markAsFallback(candidates);
            }

            List<RankedIdAndScore> rankedResults = extractRankedIdsAndScores(response, rerankCandidates);
            if (rankedResults.isEmpty()) {
                recordOutcome("parse_error", sample, payloadChars, candidates.size(), 0);
                circuitBreaker.recordFailure();
                log.warn("BGE rerank returned no usable scores: traceId={}, modelId={}, breakerState={}",
                        TraceContext.getTraceId(), properties.getModelId(), circuitBreaker.getState());
                return markAsFallback(candidates);
            }

            if (shouldFilterLowConfidence(rankedResults)) {
                recordOutcome("filtered", sample, payloadChars, candidates.size(), rankedResults.size());
                circuitBreaker.recordSuccess();
                log.warn("BGE rerank filtered low-confidence result: traceId={}, modelId={}, topScore={}, threshold={}, breakerState={}",
                        TraceContext.getTraceId(), properties.getModelId(), rankedResults.get(0).score(), properties.getScoreThreshold(), circuitBreaker.getState());
                return markAsFiltered(candidates);
            }

            List<MilvusSearchHit> reranked = applyRankingWithScores(candidates, rankedResults);
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            String outcome = attempts.get() > 1 ? "connect_retry_success" : "success";
            recordOutcome(outcome, sample, payloadChars, candidates.size(), rankedResults.size());
            circuitBreaker.recordSuccess();

            log.info("BGE rerank completed: traceId={}, modelId={}, rankedIds={}, durationMs={}, breakerState={}, attempts={}",
                    TraceContext.getTraceId(), properties.getModelId(), rankedResults.size(),
                    durationMs, circuitBreaker.getState(), attempts.get());

            return reranked;
        } catch (Exception e) {
            String outcome = classifyError(e, attempts.get());
            recordOutcome(outcome, sample, payloadChars, candidates.size(), 0);
            circuitBreaker.recordFailure();

            log.warn("BGE rerank failed ({}): traceId={}, breakerState={}, attempts={}, error={}",
                    outcome, TraceContext.getTraceId(), circuitBreaker.getState(), attempts.get(), abbreviate(e.getMessage()));
            return markAsFallback(candidates);
        }
    }

    private BgeRerankResponse executeWithRetry(BgeRerankRequest request, AtomicInteger attempts) {
        long deadline = System.currentTimeMillis() + properties.getResponseTimeoutMs();

        return webClient.post()
                .uri(properties.getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyHeaders)
                .header(DEADLINE_HEADER, String.valueOf(deadline))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(BgeRerankResponse.class)
                .doOnSubscribe(subscription -> {
                    attempts.incrementAndGet();
                    meterRegistry.counter("chatagent.reranker.attempts", "provider", PROVIDER_TAG_VALUE).increment();
                })
                .retryWhen(reactor.util.retry.Retry.max(properties.getRetryConnectErrors())
                        .filter(this::isConnectError)
                        .doBeforeRetry(retrySignal -> log.info(
                                "Retrying BGE rerank due to connection error: traceId={}, attempt={}, breakerState={}, error={}",
                                TraceContext.getTraceId(),
                                retrySignal.totalRetries() + 1,
                                circuitBreaker.getState(),
                                abbreviate(retrySignal.failure().getMessage()))))
                .block();
    }

    private boolean isRerankerReady(long deadline) {
        try {
            Boolean ready = webClient.get()
                    .uri(properties.getReadyPath())
                    .headers(this::applyHeaders)
                    .header(DEADLINE_HEADER, String.valueOf(deadline))
                    .retrieve()
                    .toBodilessEntity()
                    .map(response -> response.getStatusCode().is2xxSuccessful())
                    .timeout(Duration.ofMillis(properties.getReadyProbeTimeoutMs()))
                    .onErrorReturn(false)
                    .block();
            return Boolean.TRUE.equals(ready);
        } catch (Exception e) {
            log.warn("BGE readiness probe failed: traceId={}, breakerState={}, error={}",
                    TraceContext.getTraceId(), circuitBreaker.getState(), abbreviate(e.getMessage()));
            return false;
        }
    }

    private boolean isConnectError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException
                    || current instanceof ConnectTimeoutException) {
                return true;
            }
            if (current instanceof SocketException socketException) {
                String message = socketException.getMessage();
                if (StringUtils.hasText(message) && message.toLowerCase().contains("connection reset")) {
                    return true;
                }
            }
            String className = current.getClass().getName();
            if (className.endsWith("PoolAcquireTimeoutException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTimeoutError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof WebClientResponseException webClientResponseException
                    && webClientResponseException.getStatusCode().value() == 408) {
                return true;
            }
            String className = current.getClass().getName();
            if (className.endsWith("ReadTimeoutException") || className.endsWith("WriteTimeoutException")) {
                return true;
            }
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase();
                if (normalized.contains("timeout") || normalized.contains("deadline")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private List<MilvusSearchHit> markAsFallback(List<MilvusSearchHit> candidates) {
        return candidates.stream()
                .map(hit -> hit.withScore(null).withScoreType("fallback"))
                .toList();
    }

    private List<MilvusSearchHit> markAsFiltered(List<MilvusSearchHit> candidates) {
        return candidates.stream()
                .map(hit -> hit.withScore(null).withScoreType("filtered"))
                .toList();
    }

    private boolean shouldFilterLowConfidence(List<RankedIdAndScore> rankedResults) {
        if (!properties.isEnableConfidenceFilter() || rankedResults.isEmpty()) {
            return false;
        }
        return rankedResults.get(0).score() < properties.getScoreThreshold();
    }

    private String classifyError(Exception exception, int attempts) {
        if (isTimeoutError(exception)) {
            return "timeout";
        }
        if (isConnectError(exception)) {
            return attempts > 1 ? "connect_retry_failed" : "connect_error";
        }
        return "http_error";
    }

    private void recordOutcome(String outcome, int payloadChars, int candidates, int ranked) {
        recordOutcome(outcome, null, payloadChars, candidates, ranked);
    }

    private void recordOutcome(String outcome, Timer.Sample sample, int payloadChars, int candidates, int ranked) {
        meterRegistry.counter("chatagent.reranker.requests",
                "provider", PROVIDER_TAG_VALUE,
                "outcome", outcome).increment();

        if (sample != null) {
            sample.stop(meterRegistry.timer("chatagent.reranker.latency",
                    "provider", PROVIDER_TAG_VALUE,
                    "outcome", outcome));
        }

        DistributionSummary.builder("chatagent.reranker.payload.chars")
                .baseUnit("characters")
                .tag("provider", PROVIDER_TAG_VALUE)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(payloadChars);

        DistributionSummary.builder("chatagent.reranker.candidates")
                .baseUnit("count")
                .tag("provider", PROVIDER_TAG_VALUE)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(candidates);
    }

    private void applyHeaders(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getApiKey())) {
            headers.setBearerAuth(properties.getApiKey());
        }
        headers.add("X-Trace-Id", TraceContext.getTraceId());
    }

    String buildDocument(MilvusSearchHit hit) {
        String content = StringUtils.hasText(hit.content()) ? hit.content() : hit.retrievalText();
        content = content == null ? "" : content;
        StringBuilder builder = new StringBuilder();
        if (!hit.documentKeywords().isEmpty()) {
            builder.append("Document Keywords: ")
                    .append(String.join(", ", hit.documentKeywords()))
                    .append("\n");
        }
        if (!hit.documentQuestions().isEmpty()) {
            builder.append("Document Questions: ")
                    .append(String.join(" | ", hit.documentQuestions()))
                    .append("\n");
        }
        if (!StringUtils.hasText(hit.contextText())) {
            builder.append(content);
            return truncate(builder.toString());
        }
        builder.append("Context: ").append(hit.contextText()).append("\n\nContent: ").append(content);
        return truncate(builder.toString());
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= properties.getMaxChunkChars()) {
            return text;
        }
        return text.substring(0, properties.getMaxChunkChars());
    }

    private List<RankedIdAndScore> extractRankedIdsAndScores(BgeRerankResponse response, List<MilvusSearchHit> candidates) {
        List<BgeRerankResult> results = response.results();
        if (results == null || results.isEmpty()) {
            results = response.data();
        }
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .filter(result -> result.index() != null && result.index() >= 0 && result.index() < candidates.size())
                .map(result -> new RankedIdAndScore(candidates.get(result.index()).chunkId(), scoreOf(result)))
                .filter(result -> StringUtils.hasText(result.id()) && result.score() != null)
                .sorted(Comparator.comparingDouble((RankedIdAndScore result) -> result.score()).reversed())
                .toList();
    }

    private Double scoreOf(BgeRerankResult result) {
        if (result.relevanceScore() != null) {
            return result.relevanceScore();
        }
        if (result.score() != null) {
            return result.score();
        }
        return null;
    }

    private List<MilvusSearchHit> applyRankingWithScores(List<MilvusSearchHit> original, List<RankedIdAndScore> rankedResults) {
        Map<String, MilvusSearchHit> byId = new LinkedHashMap<>();
        for (MilvusSearchHit hit : original) {
            if (StringUtils.hasText(hit.chunkId())) {
                byId.put(hit.chunkId(), hit);
            }
        }

        List<MilvusSearchHit> reranked = new ArrayList<>(original.size());
        for (RankedIdAndScore result : rankedResults) {
            MilvusSearchHit hit = byId.remove(result.id());
            if (hit != null) {
                reranked.add(hit.withScore(result.score()).withScoreType("reranker"));
            }
        }
        for (MilvusSearchHit remaining : byId.values()) {
            reranked.add(remaining.withScoreType("retrieval"));
        }
        return reranked;
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 117) + "...";
    }

    private record ClientResources(WebClient webClient, ConnectionProvider connectionProvider) {
    }

    private record RankedIdAndScore(String id, Double score) {
    }

    record BgeRerankRequest(
            String model,
            String query,
            List<String> documents,
            int top_n,
            boolean return_documents
    ) {
    }

    record BgeRerankResponse(
            List<BgeRerankResult> results,
            List<BgeRerankResult> data
    ) {
    }

    record BgeRerankResult(
            Integer index,
            @JsonProperty("relevance_score")
            Double relevanceScore,
            Double score
    ) {
    }
}
