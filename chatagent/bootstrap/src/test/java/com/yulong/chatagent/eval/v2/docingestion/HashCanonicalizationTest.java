package com.yulong.chatagent.eval.v2.docingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashCanonicalizationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES = Path.of("..", "..", "tools", "eval", "tests", "fixtures");

    @Test
    void allHashDomainsMatchSharedPythonFixture() throws Exception {
        JsonNode base = JSON.readTree(FIXTURES.resolve("question-set-base.json").toFile());
        JsonNode expected = JSON.readTree(FIXTURES.resolve("question-set-hashes.json").toFile());
        List<JsonNode> overlay = new ArrayList<>();
        for (String line : Files.readAllLines(FIXTURES.resolve("question-set-overlay.jsonl"))) {
            if (!line.isBlank()) {
                overlay.add(JSON.readTree(line));
            }
        }

        assertThat(AcceptedQuestionSetContract.canonicalHash(base)).isEqualTo(expected.path("baseHash").asText());
        assertThat(AcceptedQuestionSetContract.overlayHash(overlay)).isEqualTo(expected.path("overlayHash").asText());
        assertThat(AcceptedQuestionSetContract.canonicalHash(AcceptedQuestionSetContract.applyOverlay(base, overlay)))
                .isEqualTo(expected.path("effectiveBaseHash").asText());
        for (String split : List.of("calibration", "development", "holdout")) {
            var projection = JSON.createArrayNode();
            base.path("questions").forEach(item -> {
                if (split.equals(item.path("split").asText())) {
                    projection.add(item);
                }
            });
            assertThat(AcceptedQuestionSetContract.canonicalHash(projection))
                    .isEqualTo(expected.path("splitHashes").path(split + "Hash").asText());
        }
        assertThat(AcceptedQuestionSetContract.textHash("line one\r\nline two"))
                .isEqualTo(expected.path("textHashes").path("question").asText());
        assertThat(AcceptedQuestionSetContract.textHash("Café\rvalue"))
                .isEqualTo(expected.path("textHashes").path("referenceAnswer").asText());
        assertThat(AcceptedQuestionSetContract.textHash("Δ evidence\r\nline"))
                .isEqualTo(expected.path("textHashes").path("referenceContent").asText());
    }

    @Test
    void canonicalJsonMatchesPythonForControlEscapesWithoutChangingLiteralUnicodeText() {
        assertThat(AcceptedQuestionSetContract.canonicalJson(JSON.getNodeFactory().textNode("\u001f")))
                .isEqualTo("\"\\u001f\"\n");
        assertThat(AcceptedQuestionSetContract.canonicalJson(JSON.getNodeFactory().textNode("\\u001F")))
                .isEqualTo("\"\\\\u001F\"\n");
    }

    @Test
    void frozenAcceptedQuestionSetMatchesItsManifestHashes() throws Exception {
        Path root = Path.of("src", "test", "resources", "eval", "v2", "datasets", "doc-ingestion");
        JsonNode base = JSON.readTree(root.resolve("manual-accepted-questions-v1.json").toFile());
        JsonNode manifest = JSON.readTree(root.resolve("manual-accepted-questions-v1.manifest.json").toFile());

        assertThat(AcceptedQuestionSetContract.canonicalHash(base))
                .isEqualTo(manifest.path("baseHash").asText());
        for (String split : List.of("calibration", "development", "holdout")) {
            var projection = JSON.createArrayNode();
            base.path("questions").forEach(item -> {
                if (split.equals(item.path("split").asText())) {
                    projection.add(item);
                }
            });
            assertThat(AcceptedQuestionSetContract.canonicalHash(projection))
                    .isEqualTo(manifest.path(split + "Hash").asText());
        }
    }
}
