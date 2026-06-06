package com.yulong.chatagent.eval.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class EvalArtifactWriter {

    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path outputRoot;
    private final ObjectMapper objectMapper;

    public EvalArtifactWriter(Path outputRoot) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public Path writeRun(
            EvalRunManifest manifest,
            Map<String, Object> metrics,
            List<Map<String, Object>> samples,
            List<Map<String, Object>> failures
    ) {
        Path runDirectory = ensureRunDirectory(manifest.runId());
        writeJson(runDirectory.resolve("manifest.json"), manifest);
        writeJson(runDirectory.resolve("metrics.json"), metrics);
        writeJsonLines(runDirectory.resolve("samples.jsonl"), samples);
        writeJsonLines(runDirectory.resolve("failures.jsonl"), failures);
        return runDirectory;
    }

    private Path ensureRunDirectory(String runId) {
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("Unsafe evaluation run ID: " + runId);
        }
        Path runDirectory = outputRoot.resolve(runId).normalize();
        if (!runDirectory.startsWith(outputRoot)) {
            throw new IllegalArgumentException("Evaluation run directory escapes output root");
        }
        try {
            Files.createDirectories(runDirectory);
            return runDirectory;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create evaluation run directory", exception);
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            objectMapper.writeValue(path.toFile(), value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write evaluation artifact: " + path.getFileName(), exception);
        }
    }

    private void writeJsonLines(Path path, List<Map<String, Object>> rows) {
        try {
            StringBuilder output = new StringBuilder();
            for (Map<String, Object> row : rows) {
                output.append(objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(row))
                        .append('\n');
            }
            Files.writeString(path, output);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write evaluation artifact: " + path.getFileName(), exception);
        }
    }
}
