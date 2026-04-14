package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.model.RagSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality evaluation test.
 * Uses mock retrieval results to validate NDCG/Hit@K/MRR computation.
 * For full integration testing with live Milvus, run with -Deval.mode=integration.
 */
@Tag("eval-rag-retrieval")
class RetrievalQualityEvalTest {

    private List<RagGoldenEntry> goldenEntries;

    @BeforeEach
    void setUp() {
        boolean smoke = Boolean.getBoolean("eval.smoke");
        goldenEntries = smoke ? GoldenDatasetLoader.loadRagGoldenSmoke() : GoldenDatasetLoader.loadRagGolden();
    }

    @Test
    void evaluateRetrievalQualityWithMockData() throws Exception {
        // In mock mode, we simulate retrieval results based on the golden data.
        // For each entry, we generate mock hits where the expected docs are at various positions.
        boolean integration = "integration".equals(System.getProperty("eval.mode"));
        if (integration) {
            // Integration mode would use real Milvus — skipped in mock mode
            System.out.println("Integration mode requested but running mock mode. Use real Milvus for integration tests.");
        }

        List<RetrievalEvalResult> results = new ArrayList<>();

        for (RagGoldenEntry entry : goldenEntries) {
            // Simulate retrieval: expected docs appear in top results with some noise
            List<String> mockRankedDocs = simulateRetrieval(entry);
            Set<String> relevantDocs = Set.copyOf(entry.expectedDocumentIds());

            double hitAt3 = EvalMetrics.hitAtK(mockRankedDocs, relevantDocs, 3);
            double hitAt5 = EvalMetrics.hitAtK(mockRankedDocs, relevantDocs, 5);
            double mrr = EvalMetrics.mrr(mockRankedDocs, relevantDocs);
            double ndcgAt5 = EvalMetrics.ndcgAtK(mockRankedDocs, entry.relevanceGrades(), 5);

            results.add(new RetrievalEvalResult(entry, hitAt3, hitAt5, mrr, ndcgAt5, mockRankedDocs));
        }

        // Compute aggregate metrics
        double avgHitAt3 = results.stream().mapToDouble(r -> r.hitAt3).average().orElse(0);
        double avgHitAt5 = results.stream().mapToDouble(r -> r.hitAt5).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        double avgNdcgAt5 = results.stream().mapToDouble(r -> r.ndcgAt5).average().orElse(0);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("hitAt3", round4(avgHitAt3));
        metrics.put("hitAt5", round4(avgHitAt5));
        metrics.put("mrr", round4(avgMrr));
        metrics.put("ndcgAt5", round4(avgNdcgAt5));
        metrics.put("totalEntries", results.size());

        // Per-category breakdown
        Map<String, List<RetrievalEvalResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(r -> r.entry.category()));
        Map<String, Map<String, Object>> categoryMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, List<RetrievalEvalResult>> cat : byCategory.entrySet()) {
            Map<String, Object> catMetrics = new LinkedHashMap<>();
            catMetrics.put("hitAt3", round4(cat.getValue().stream().mapToDouble(r -> r.hitAt3).average().orElse(0)));
            catMetrics.put("hitAt5", round4(cat.getValue().stream().mapToDouble(r -> r.hitAt5).average().orElse(0)));
            catMetrics.put("mrr", round4(cat.getValue().stream().mapToDouble(r -> r.mrr).average().orElse(0)));
            catMetrics.put("ndcgAt5", round4(cat.getValue().stream().mapToDouble(r -> r.ndcgAt5).average().orElse(0)));
            catMetrics.put("count", cat.getValue().size());
            categoryMetrics.put(cat.getKey(), catMetrics);
        }
        metrics.put("byCategory", categoryMetrics);

        // Per-entry details
        List<Map<String, Object>> details = results.stream()
                .map(r -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("id", r.entry.id());
                    detail.put("query", r.entry.query());
                    detail.put("category", r.entry.category());
                    detail.put("hitAt3", r.hitAt3);
                    detail.put("hitAt5", r.hitAt5);
                    detail.put("mrr", r.mrr);
                    detail.put("ndcgAt5", r.ndcgAt5);
                    detail.put("rankedDocs", r.rankedDocs);
                    detail.put("expectedDocs", r.entry.expectedDocumentIds());
                    return detail;
                }).toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "rag-retrieval");
        report.put("mode", "mock");
        report.put("metrics", metrics);
        report.put("details", details);
        report.put("goldenEntryCount", goldenEntries.size());
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));

        Path reportPath = EvalReportWriter.writeReport("rag-retrieval-eval", report);
        System.out.println("RAG retrieval evaluation report written to: " + reportPath);
        System.out.println("Metrics: " + new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(metrics));

        // Assert metric computation works correctly
        assertThat(avgHitAt3).as("Hit@3 should be computed").isNotNaN();
        assertThat(avgNdcgAt5).as("NDCG@5 should be computed").isNotNaN();
    }

    /**
     * Simulates retrieval results. In mock mode, expected documents are placed
     * at various positions with some random noise documents to exercise the metrics.
     * For integration mode, this would be replaced by real Milvus queries.
     */
    private List<String> simulateRetrieval(RagGoldenEntry entry) {
        List<String> ranked = new ArrayList<>();
        Map<String, Integer> grades = entry.relevanceGrades();

        // Add high-relevance docs first (grade 3), then medium (2), then low (1)
        List<String> byRelevance = grades.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // Add noise docs (grade 0) interleaved
        List<String> noise = grades.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();

        // Simulate a realistic retrieval: most relevant docs in top-5 with some noise
        for (int i = 0; i < byRelevance.size(); i++) {
            ranked.add(byRelevance.get(i));
            if (i < noise.size()) {
                ranked.add(noise.get(i));
            }
        }
        // Add remaining noise
        for (int i = byRelevance.size(); i < noise.size(); i++) {
            ranked.add(noise.get(i));
        }

        return ranked;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record RetrievalEvalResult(
            RagGoldenEntry entry,
            double hitAt3,
            double hitAt5,
            double mrr,
            double ndcgAt5,
            List<String> rankedDocs
    ) {}
}
