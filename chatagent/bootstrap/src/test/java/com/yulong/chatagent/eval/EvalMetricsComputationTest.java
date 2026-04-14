package com.yulong.chatagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the IR metric computation logic with known inputs and expected outputs.
 * This test has no eval tag and runs in normal CI.
 */
class EvalMetricsComputationTest {

    @Test
    void shouldComputeHitAtKCorrectly() {
        List<String> ranked = List.of("doc-1", "doc-2", "doc-3", "doc-4", "doc-5");

        // Relevant doc is in position 2 (0-indexed)
        assertThat(EvalMetrics.hitAtK(ranked, Set.of("doc-3"), 3)).isEqualTo(1.0);
        assertThat(EvalMetrics.hitAtK(ranked, Set.of("doc-3"), 2)).isEqualTo(0.0);
        assertThat(EvalMetrics.hitAtK(ranked, Set.of("doc-3"), 5)).isEqualTo(1.0);

        // No relevant docs
        assertThat(EvalMetrics.hitAtK(ranked, Set.of("doc-99"), 5)).isEqualTo(0.0);

        // Empty relevant set
        assertThat(EvalMetrics.hitAtK(ranked, Set.of(), 5)).isEqualTo(0.0);
    }

    @Test
    void shouldComputeMRRCorrectly() {
        List<String> ranked = List.of("doc-1", "doc-2", "doc-3", "doc-4");

        // First relevant at position 1 (0-indexed) -> MRR = 1.0
        assertThat(EvalMetrics.mrr(ranked, Set.of("doc-1"))).isEqualTo(1.0);

        // First relevant at position 3 (0-indexed) -> MRR = 1/4 = 0.25
        assertThat(EvalMetrics.mrr(ranked, Set.of("doc-4"))).isEqualTo(0.25);

        // No relevant doc
        assertThat(EvalMetrics.mrr(ranked, Set.of("doc-99"))).isEqualTo(0.0);
    }

    @Test
    void shouldComputeNdcgCorrectlyForKnownGrades() {
        Map<String, Integer> grades = Map.of("doc-1", 3, "doc-2", 2, "doc-3", 1, "doc-4", 0);

        // Perfect ranking: highest grade first
        List<String> perfectRanking = List.of("doc-1", "doc-2", "doc-3", "doc-4");
        assertThat(EvalMetrics.ndcgAtK(perfectRanking, grades, 4)).isEqualTo(1.0);

        // Worst ranking: lowest grade first (except doc-4 which is 0)
        List<String> worstRanking = List.of("doc-3", "doc-2", "doc-1", "doc-4");
        double worstNdcg = EvalMetrics.ndcgAtK(worstRanking, grades, 4);
        assertThat(worstNdcg).isGreaterThan(0.0).isLessThan(1.0);
    }

    @Test
    void shouldReturnZeroNdcgWhenNoRelevantDocs() {
        Map<String, Integer> grades = Map.of("doc-1", 0, "doc-2", 0);
        List<String> ranked = List.of("doc-1", "doc-2");
        assertThat(EvalMetrics.ndcgAtK(ranked, grades, 5)).isEqualTo(0.0);
    }

    @Test
    void shouldComputeF1Correctly() {
        assertThat(EvalMetrics.f1(1.0, 1.0)).isEqualTo(1.0);
        assertThat(EvalMetrics.f1(0.5, 0.5)).isEqualTo(0.5);
        assertThat(EvalMetrics.f1(0.0, 1.0)).isEqualTo(0.0);
        assertThat(EvalMetrics.f1(0.8, 0.9)).isBetween(0.84, 0.85);
    }

    @Test
    void shouldComputeAccuracyCorrectly() {
        assertThat(EvalMetrics.accuracy(95, 100)).isEqualTo(0.95);
        assertThat(EvalMetrics.accuracy(0, 10)).isEqualTo(0.0);
        assertThat(EvalMetrics.accuracy(0, 0)).isEqualTo(0.0);
    }
}
