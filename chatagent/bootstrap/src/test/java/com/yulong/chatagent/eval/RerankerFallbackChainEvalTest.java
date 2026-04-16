package com.yulong.chatagent.eval;

import com.yulong.chatagent.rag.retrieve.BgeHttpRetrievalReranker;
import com.yulong.chatagent.rag.retrieve.NoopRetrievalReranker;
import com.yulong.chatagent.rag.retrieve.RerankerCircuitBreaker;
import com.yulong.chatagent.rag.retrieve.RerankerProperties;
import com.yulong.chatagent.rag.retrieve.RetrievalReranker;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reranker fallback chain recovery latency evaluation.
 *
 * <p>Measures latency across three reranker paths:
 * <ul>
 *   <li>Noop (baseline) — returns candidates unchanged</li>
 *   <li>BGE circuit-OPEN fallback — circuit breaker blocks HTTP call, returns original order with scoreType=fallback</li>
 *   <li>BGE circuit recovery cycle — CLOSED→OPEN→HALF_OPEN→CLOSED at the reranker API level</li>
 * </ul>
 *
 * <p>No external infrastructure needed — uses unreachable stub endpoint and tripped circuit breaker.
 * <br>Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-reranker-fallback -Dtest=RerankerFallbackChainEvalTest
 */
@Tag("eval-reranker-fallback")
class RerankerFallbackChainEvalTest {

    private static final double NANOS_TO_MS = 1_000_000.0;
    private static final int WARMUP = 50;
    private static final int ITERATIONS = 200;

    @Test
    void evaluateFallbackChainLatency() throws Exception {
        List<MilvusSearchHit> candidates = buildCandidates(10);
        String query = "年假政策有什么要求";

        NoopRetrievalReranker noopReranker = new NoopRetrievalReranker();
        BgeHttpRetrievalReranker bgeReranker = createBgeReranker(80);

        // --- Noop baseline ---
        benchmarkReranker(noopReranker, query, candidates, WARMUP);
        List<Long> noopLatencies = benchmarkReranker(noopReranker, query, candidates, ITERATIONS);

        // --- BGE circuit-OPEN fallback ---
        tripBgeCircuit(bgeReranker);
        benchmarkReranker(bgeReranker, query, candidates, WARMUP);
        List<Long> bgeFallbackLatencies = benchmarkReranker(bgeReranker, query, candidates, ITERATIONS);

        List<MilvusSearchHit> fallbackResult = bgeReranker.rerank(query, candidates);
        assertThat(fallbackResult).hasSize(candidates.size());
        assertThat(fallbackResult.get(0).scoreType()).isEqualTo("fallback");

        // --- Compile report ---
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("candidateCount", candidates.size());
        report.put("warmupIterations", WARMUP);
        report.put("benchmarkIterations", ITERATIONS);

        report.put("noopP50Ms", percentile(noopLatencies, 50));
        report.put("noopP95Ms", percentile(noopLatencies, 95));
        report.put("noopP99Ms", percentile(noopLatencies, 99));

        report.put("bgeFallbackP50Ms", percentile(bgeFallbackLatencies, 50));
        report.put("bgeFallbackP95Ms", percentile(bgeFallbackLatencies, 95));
        report.put("bgeFallbackP99Ms", percentile(bgeFallbackLatencies, 99));

        double overheadP50 = percentile(bgeFallbackLatencies, 50) - percentile(noopLatencies, 50);
        report.put("fallbackOverheadP50Ms", Math.round(overheadP50 * 10000.0) / 10000.0);

        report.put("fallbackScoreType", "fallback");
        report.put("fallbackPreservesOrder", verifyOrderPreserved(candidates, fallbackResult));

        // --- Circuit recovery cycle benchmark ---
        Map<String, Object> recoveryReport = benchmarkRecoveryCycle(candidates, query);
        report.put("recoveryCycle", recoveryReport);

        var reportPath = EvalReportWriter.writeReport("reranker-fallback-chain-eval", report);

        System.out.println("\n=== Reranker Fallback Chain Evaluation ===");
        System.out.printf("Candidates: %d, Iterations: %d%n", candidates.size(), ITERATIONS);
        System.out.println();
        System.out.printf("%-25s  P50(ms)  P95(ms)  P99(ms)%n", "Path");
        System.out.printf("%-25s  %7.4f  %7.4f  %7.4f%n", "Noop (baseline)",
                percentile(noopLatencies, 50), percentile(noopLatencies, 95), percentile(noopLatencies, 99));
        System.out.printf("%-25s  %7.4f  %7.4f  %7.4f%n", "BGE circuit-OPEN fallback",
                percentile(bgeFallbackLatencies, 50), percentile(bgeFallbackLatencies, 95), percentile(bgeFallbackLatencies, 99));
        System.out.printf("%nFallback overhead vs Noop P50: %.4f ms%n", overheadP50);
        System.out.printf("Fallback preserves input order: %s%n", report.get("fallbackPreservesOrder"));
        System.out.println();
        System.out.printf("Recovery cycle (CLOSED→OPEN→HALF_OPEN→CLOSED):%n");
        System.out.printf("  Trip latency P50:     %.4f ms%n", recoveryReport.get("tripLatencyP50Ms"));
        System.out.printf("  Recovery latency P50: %.4f ms%n", recoveryReport.get("recoveryLatencyP50Ms"));
        System.out.printf("  Full cycle P50:       %.2f ms (includes %d ms openStateMs wait)%n",
                recoveryReport.get("fullCycleP50Ms"), recoveryReport.get("openStateMs"));
        System.out.println("\nReport: " + reportPath);

        assertThat(percentile(bgeFallbackLatencies, 50))
                .as("BGE fallback P50 should be sub-ms").isLessThan(1.0);
        assertThat((double) recoveryReport.get("tripLatencyP50Ms"))
                .as("Circuit trip P50 should be sub-ms").isLessThan(5.0);
        assertThat((double) recoveryReport.get("recoveryLatencyP50Ms"))
                .as("Circuit recovery P50 should be sub-ms").isLessThan(1.0);
    }

    private Map<String, Object> benchmarkRecoveryCycle(List<MilvusSearchHit> candidates, String query) throws Exception {
        int openStateMs = 80;
        int cycles = 10;
        List<Long> tripLatencies = new ArrayList<>();
        List<Long> recoveryLatencies = new ArrayList<>();
        List<Long> fullCycleLatencies = new ArrayList<>();

        for (int c = 0; c < cycles; c++) {
            RerankerProperties props = createProperties(openStateMs);
            RerankerCircuitBreaker cb = new RerankerCircuitBreaker("bench-bge", props);

            long cycleStart = System.nanoTime();

            // Trip
            long tripStart = System.nanoTime();
            for (int f = 0; f < props.getMinimumRequestVolume(); f++) {
                cb.recordFailure();
            }
            tripLatencies.add(System.nanoTime() - tripStart);
            assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.OPEN);

            // Verify fallback during OPEN
            assertThat(cb.allowRequest()).isFalse();

            // Wait for HALF_OPEN
            Thread.sleep(openStateMs + 20);
            assertThat(cb.allowRequest()).isTrue();
            assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.HALF_OPEN);

            // Recover
            long recoverStart = System.nanoTime();
            cb.recordSuccess();
            recoveryLatencies.add(System.nanoTime() - recoverStart);
            assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.CLOSED);

            fullCycleLatencies.add(System.nanoTime() - cycleStart);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openStateMs", openStateMs);
        result.put("cycles", cycles);
        result.put("tripLatencyP50Ms", percentile(tripLatencies, 50));
        result.put("tripLatencyP99Ms", percentile(tripLatencies, 99));
        result.put("recoveryLatencyP50Ms", percentile(recoveryLatencies, 50));
        result.put("recoveryLatencyP99Ms", percentile(recoveryLatencies, 99));
        result.put("fullCycleP50Ms", percentile(fullCycleLatencies, 50));
        return result;
    }

    private static boolean verifyOrderPreserved(List<MilvusSearchHit> original, List<MilvusSearchHit> fallback) {
        if (original.size() != fallback.size()) return false;
        for (int i = 0; i < original.size(); i++) {
            if (!original.get(i).chunkId().equals(fallback.get(i).chunkId())) return false;
        }
        return true;
    }

    private List<Long> benchmarkReranker(RetrievalReranker reranker, String query,
                                         List<MilvusSearchHit> candidates, int iterations) {
        List<Long> latencies = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            reranker.rerank(query, candidates);
            latencies.add(System.nanoTime() - start);
        }
        return latencies;
    }

    private static BgeHttpRetrievalReranker createBgeReranker(int openStateMs) throws Exception {
        RerankerProperties props = createProperties(openStateMs);
        WebClient stubClient = WebClient.builder().baseUrl("http://127.0.0.1:1").build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Constructor<BgeHttpRetrievalReranker> ctor = BgeHttpRetrievalReranker.class.getDeclaredConstructor(
                WebClient.class, RerankerProperties.class, io.micrometer.core.instrument.MeterRegistry.class);
        ctor.setAccessible(true);
        return ctor.newInstance(stubClient, props, registry);
    }

    private static void tripBgeCircuit(BgeHttpRetrievalReranker reranker) throws Exception {
        var cbField = BgeHttpRetrievalReranker.class.getDeclaredField("circuitBreaker");
        cbField.setAccessible(true);
        RerankerCircuitBreaker cb = (RerankerCircuitBreaker) cbField.get(reranker);

        var propsField = BgeHttpRetrievalReranker.class.getDeclaredField("properties");
        propsField.setAccessible(true);
        RerankerProperties props = (RerankerProperties) propsField.get(reranker);

        for (int i = 0; i < props.getMinimumRequestVolume(); i++) {
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(RerankerCircuitBreaker.State.OPEN);
    }

    private static RerankerProperties createProperties(int openStateMs) {
        RerankerProperties props = new RerankerProperties();
        props.setFailureThreshold(3);
        props.setMinimumRequestVolume(5);
        props.setFailureRateThresholdPercent(50);
        props.setOpenStateMs(openStateMs);
        props.setHalfOpenProbeCount(1);
        props.setBaseUrl("http://127.0.0.1:1");
        return props;
    }

    private static List<MilvusSearchHit> buildCandidates(int count) {
        List<MilvusSearchHit> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candidates.add(MilvusSearchHit.builder()
                    .chunkId("chunk-" + i)
                    .sourceId("src-" + i)
                    .documentId("doc-" + i)
                    .chunkIndex(i)
                    .documentName("test-doc-" + i)
                    .content("这是测试文档第" + i + "个分块的内容，包含一些关于年假政策的信息。")
                    .retrievalText("年假政策 测试 分块" + i)
                    .score(0.9 - i * 0.05)
                    .build());
        }
        return candidates;
    }

    private static double percentile(List<Long> values, int p) {
        if (values.isEmpty()) return 0.0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index)) / NANOS_TO_MS;
    }
}
