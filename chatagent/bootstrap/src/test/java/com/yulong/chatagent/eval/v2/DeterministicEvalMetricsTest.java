package com.yulong.chatagent.eval.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class DeterministicEvalMetricsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void computesCrossLanguageParityMetrics() throws Exception {
        JsonNode parity = parity().get("retrievalMetrics");
        List<String> retrieved = OBJECT_MAPPER.convertValue(
                parity.get("retrieved"),
                new TypeReference<>() {
                }
        );
        Set<String> relevant = OBJECT_MAPPER.convertValue(
                parity.get("relevant"),
                new TypeReference<>() {
                }
        );
        int k = parity.get("k").intValue();
        JsonNode expected = parity.get("expected");

        assertThat(DeterministicEvalMetrics.hitAtK(retrieved, relevant, k)).isEqualTo(expected.get("hitAt3").doubleValue());
        assertThat(DeterministicEvalMetrics.recallAtK(retrieved, relevant, k)).isEqualTo(expected.get("recallAt3").doubleValue());
        assertThat(DeterministicEvalMetrics.precisionAtK(retrieved, relevant, k)).isEqualTo(expected.get("precisionAt3").doubleValue());
        assertThat(DeterministicEvalMetrics.reciprocalRank(retrieved, relevant)).isEqualTo(expected.get("mrr").doubleValue());
        assertThat(DeterministicEvalMetrics.ndcgAtK(retrieved, relevant, k))
                .isCloseTo(expected.get("ndcgAt3").doubleValue(), offset(1.0e-12));
        assertThat(DeterministicEvalMetrics.phraseRecall(
                List.of("Required   phrase appears."),
                List.of("required phrase", "missing")
        )).isEqualTo(0.5);
    }

    @Test
    void rejectsNonPositiveK() {
        assertThatThrownBy(() -> DeterministicEvalMetrics.hitAtK(List.of("a"), Set.of("a"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private JsonNode parity() throws Exception {
        try (InputStream input = DeterministicEvalMetricsTest.class.getResourceAsStream(
                "/eval/v2/fixtures/core-contract-parity.json"
        )) {
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
