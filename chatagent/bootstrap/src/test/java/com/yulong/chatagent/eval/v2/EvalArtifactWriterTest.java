package com.yulong.chatagent.eval.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalArtifactWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void writesNormalizedArtifactsAndOmitsNullTuningFields() throws Exception {
        EvalRunManifest manifest = manifest("run-1");

        Path runDirectory = new EvalArtifactWriter(tempDirectory).writeRun(
                manifest,
                Map.of("status", "pass", "overall", Map.of("hitAt3", 1.0)),
                List.of(Map.of("sampleId", "sample-1")),
                List.of()
        );

        JsonNode writtenManifest = objectMapper.readTree(runDirectory.resolve("manifest.json").toFile());
        assertThat(writtenManifest.has("tuning")).isFalse();
        assertThat(runDirectory.resolve("metrics.json")).exists();
        assertThat(runDirectory.resolve("samples.jsonl")).exists();
        assertThat(runDirectory.resolve("failures.jsonl")).exists();
        assertThat(java.nio.file.Files.readAllLines(runDirectory.resolve("samples.jsonl"))).hasSize(1);
    }

    @Test
    void rejectsUnsafeRunId() {
        assertThatThrownBy(() -> new EvalArtifactWriter(tempDirectory).writeRun(
                manifest("../escape"),
                Map.of(),
                List.of(),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private EvalRunManifest manifest(String runId) {
        return new EvalRunManifest(
                runId,
                "rag-retrieval",
                "smoke",
                "2026-06-06T00:00:00Z",
                "branch",
                "sha",
                "dataset",
                "dataset-hash",
                Map.of("topK", 3),
                "fingerprint",
                Map.of(),
                Map.of(),
                List.of("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl"),
                null
        );
    }
}
