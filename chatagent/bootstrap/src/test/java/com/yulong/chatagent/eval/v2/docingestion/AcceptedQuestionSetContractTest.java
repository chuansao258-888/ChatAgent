package com.yulong.chatagent.eval.v2.docingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcceptedQuestionSetContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FORMATS = List.of("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD");

    @TempDir
    Path tempDir;

    @Test
    void acceptsFullyReviewedBaseWithMatchingHashesAndCounts() {
        ObjectNode base = validBase();
        ObjectNode manifest = manifestFor(base);

        AcceptedQuestionSetContract.ValidatedQuestionSet result =
                AcceptedQuestionSetContract.validate(base, manifest, List.of(), null);

        assertThat(result.baseHash()).isEqualTo(manifest.path("baseHash").asText());
        assertThat(result.overlayHash()).isNull();
    }

    @Test
    void rejectsPendingQuestionAndMissingReviewer() {
        ObjectNode pendingBase = validBase();
        ((ObjectNode) pendingBase.path("questions").get(0)).put("auditStatus", "pending");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(
                pendingBase, manifestFor(pendingBase), List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("llm-reviewed");

        ObjectNode missingReviewerBase = validBase();
        ((ObjectNode) missingReviewerBase.path("questions").get(0).path("llmProvenance")).put("reviewerModel", "");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(
                missingReviewerBase, manifestFor(missingReviewerBase), List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("reviewerModel");

        ObjectNode missingReferenceChunkBase = validBase();
        ((ObjectNode) missingReferenceChunkBase.path("questions").get(0)).remove("referenceChunkId");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(
                missingReferenceChunkBase, manifestFor(missingReferenceChunkBase), List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("referenceChunkId");
    }

    @Test
    void rejectsMissingManifestAndPerFormatSplitMinimum() {
        ObjectNode base = validBase();
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, null, List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("manifest");

        ObjectNode missingReviewReceipt = manifestFor(base);
        missingReviewReceipt.remove("reviewReceiptHash");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, missingReviewReceipt, List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Gemini-review receipt");

        for (int index = 80; index < 95; index++) {
            ((ObjectNode) base.path("questions").get(index)).put("split", "calibration");
        }
        ObjectNode manifest = manifestFor(base);
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("holdout");
    }

    @Test
    void rejectsStaleBaseSplitHashesAndManifestCounts() {
        ObjectNode base = validBase();
        ObjectNode staleBaseHash = manifestFor(base);
        staleBaseHash.put("baseHash", "stale");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, staleBaseHash, List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("baseHash");

        ObjectNode staleSplit = manifestFor(base);
        staleSplit.put("calibrationHash", "stale");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, staleSplit, List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("calibrationHash");

        ObjectNode wrongCount = manifestFor(base);
        wrongCount.put("totalQuestions", 499);
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, wrongCount, List.of(), null))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void overlayRejectsHoldoutUnknownExtraFieldAndMissingReceipt() {
        ObjectNode base = validBase();
        ObjectNode manifest = manifestFor(base);
        ObjectNode safe = overlay("q-sec_html-0", "question", "What fact 0 is approved?", "What approved fact has index zero?");

        ObjectNode holdout = overlay("q-sec_html-80", "question", "What fact 80 is approved?", "Changed?");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(holdout), receipt(base, manifest, List.of(holdout), "bound")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("holdout");

        ObjectNode unknown = overlay("unknown", "question", "old", "new");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(unknown), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("unknown");

        safe.put("extra", true);
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(safe), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exactly");

        ObjectNode safeWithoutExtra = overlay(
                "q-sec_html-0", "question", "What fact 0 is approved?", "What approved fact has index zero?");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(safeWithoutExtra), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("receipt");

        ObjectNode forbidden = overlay("q-sec_html-0", "filename", "sec_html-0.dat", "changed.dat");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(forbidden), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void matchingOverlayReceiptPassesAndBoundContentIsRechecked() {
        ObjectNode base = validBase();
        ObjectNode manifest = manifestFor(base);
        ObjectNode overlay = overlay(
                "q-sec_html-0", "question", "What fact 0 is approved?", "What approved fact has index zero?");
        ObjectNode receipt = receipt(base, manifest, List.of(overlay), "bound evidence");

        AcceptedQuestionSetContract.ValidatedQuestionSet validated =
                AcceptedQuestionSetContract.validate(base, manifest, List.of(overlay), receipt);
        AcceptedQuestionSetContract.validateBoundReceipt(validated, Map.of(
                "q-sec_html-0",
                new AcceptedQuestionSetContract.BoundEvidence(
                        "What approved fact has index zero?", "Approved answer 0", "bound evidence")));

        assertThatThrownBy(() -> AcceptedQuestionSetContract.validateBoundReceipt(validated, Map.of(
                "q-sec_html-0",
                new AcceptedQuestionSetContract.BoundEvidence(
                        "What approved fact has index zero?", "Approved answer 0", "tampered evidence"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("content hash");
    }

    @Test
    void staleOverlayReceiptFailsClosed() {
        ObjectNode base = validBase();
        ObjectNode manifest = manifestFor(base);
        ObjectNode overlay = overlay(
                "q-sec_html-0", "question", "What fact 0 is approved?", "What approved fact has index zero?");
        ObjectNode staleReceipt = receipt(base, manifest, List.of(overlay), "bound evidence");
        staleReceipt.put("overlayHash", "stale");

        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(overlay), staleReceipt))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("overlayHash");

        ObjectNode failedReceipt = receipt(base, manifest, List.of(overlay), "bound evidence");
        ((ObjectNode) failedReceipt.path("rows").get(0)).put("status", "failed");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, manifest, List.of(overlay), failedReceipt))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must pass");
    }

    @Test
    void acceptedQuestionBindsByFrozenSourceHashAndExportsLlmProvenance() {
        var first = ingested("doc-1", "sha-one", "first evidence");
        var second = ingested("doc-2", "sha-two", "second approved evidence");
        var provenance = new ProductionDocumentIngestionEvalTest.LlmProvenance(
                "gemini-test", "gemini-cli", "gemini-test", "gemini-cli", "review-v1");
        var question = new ProductionDocumentIngestionEvalTest.ManualQuestion(
                "q-accepted", "e-accepted", "README.md", "sha-two", "WEB_MD", "calibration", 0,
                "frozen-chunk-id", "Which evidence was approved?", "second approved evidence",
                "Approved answer", "llm-generated-llm-reviewed-v1", "llm-reviewed", provenance);

        var queries = ProductionDocumentIngestionEvalTest.bindManualQuestions(
                List.of(first, second),
                new ProductionDocumentIngestionEvalTest.ManualQuestionSet(
                        "manual-accepted-questions-v1", List.of(question)));

        assertThat(queries).singleElement().satisfies(query -> {
            assertThat(query.referenceDocId()).isEqualTo("doc-2");
            assertThat(query.referenceChunkId()).isEqualTo("chunk-doc-2");
            assertThat(query.sourceSha256()).isEqualTo("sha-two");
            Map<String, Object> metadata = ProductionDocumentIngestionEvalTest.questionAuthorshipMetadata(query);
            assertThat(metadata).containsEntry("auditStatus", "llm-reviewed");
            assertThat(metadata).containsKey("llmProvenance");
            assertThat(((Map<?, ?>) metadata.get("questionProvenance")).get("method"))
                    .isEqualTo("llm-assisted-no-source-v1");
        });
    }

    @Test
    void acceptsOneHashBoundCodexManualAssistedReplacement() {
        ObjectNode base = validBase();
        ObjectNode replacement = (ObjectNode) base.path("questions").get(0);
        replacement.put("generationMethod", "codex-manual-assisted-llm-reviewed-v1");
        replacement.withObject("/llmProvenance").put("generatorModel", "gpt-5-codex");
        replacement.withObject("/llmProvenance").put("generatorTool", "codex-desktop");
        ObjectNode manifest = manifestFor(base);
        manifest.put("manualReplacementCount", 1);
        manifest.put("manualReplacementReceiptHash", "b".repeat(64));

        AcceptedQuestionSetContract.validate(base, manifest, List.of(), null);

        ((ObjectNode) base.path("questions").get(1))
                .put("generationMethod", "codex-manual-assisted-llm-reviewed-v1");
        ObjectNode invalidManifest = manifestFor(base);
        invalidManifest.put("manualReplacementCount", 2);
        invalidManifest.put("manualReplacementReceiptHash", "b".repeat(64));
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validate(base, invalidManifest, List.of(), null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("at most one");
    }

    @Test
    void codexManualAssistedQuestionKeepsDistinctProductionProvenance() {
        var provenance = new ProductionDocumentIngestionEvalTest.LlmProvenance(
                "gpt-5-codex", "codex-desktop", "gemini-test", "gemini-cli", "review-v1");
        var question = new ProductionDocumentIngestionEvalTest.ManualQuestion(
                "q-codex", "e-codex", "README.md", "sha-one", "WEB_MD", "calibration", 0,
                "frozen-chunk-id", "Which evidence was approved?", "first approved evidence",
                "Approved answer", "codex-manual-assisted-llm-reviewed-v1", "llm-reviewed", provenance);

        var query = ProductionDocumentIngestionEvalTest.bindManualQuestions(
                List.of(ingested("doc-1", "sha-one", "first approved evidence")),
                new ProductionDocumentIngestionEvalTest.ManualQuestionSet(
                        "manual-accepted-questions-v1", List.of(question)))
                .get(0);
        Map<String, Object> metadata = ProductionDocumentIngestionEvalTest.questionAuthorshipMetadata(query);

        assertThat(query.generationMethod()).isEqualTo("codex-manual-assisted-llm-reviewed-v1");
        assertThat(((Map<?, ?>) metadata.get("questionProvenance")).get("method"))
                .isEqualTo("codex-manual-assisted-no-source-v1");
        assertThat(((Map<?, ?>) metadata.get("llmProvenance")).get("generatorModel"))
                .isEqualTo("gpt-5-codex");
        assertThat(((Map<?, ?>) metadata.get("llmProvenance")).get("generatorTool"))
                .isEqualTo("codex-desktop");
    }

    @Test
    void smokeQuestionKeepsManualProvenanceWithoutLlmFields() {
        var question = new ProductionDocumentIngestionEvalTest.ManualQuestion(
                "q-smoke", null, "README.md", null, null, "calibration", 0,
                null, "Which evidence is present?", "first evidence", "Manual answer",
                null, null, null);

        var query = ProductionDocumentIngestionEvalTest.bindManualQuestions(
                List.of(ingested("doc-1", "sha-one", "first evidence")),
                new ProductionDocumentIngestionEvalTest.ManualQuestionSet("manual-smoke-questions-v1", List.of(question)))
                .get(0);
        Map<String, Object> metadata = ProductionDocumentIngestionEvalTest.questionAuthorshipMetadata(query);

        assertThat(query.generationMethod()).isEqualTo("manual-reviewed-no-source-v1");
        assertThat(metadata).doesNotContainKeys("llmProvenance", "auditStatus");
        assertThat(((Map<?, ?>) metadata.get("questionProvenance")).get("method"))
                .isEqualTo("manual-reviewed-no-source-v1");
    }

    @Test
    void evidenceExportIdentityChecksDatasetManifestAndExactBytes() throws Exception {
        Path dataset = tempDir.resolve(Path.of("datasets", "doc-ingestion", "doc-ingestion-retrieval-v1.jsonl"));
        Files.createDirectories(dataset.getParent());
        Files.writeString(dataset, "row\n");
        String expectedHash = "sha256:" + AcceptedQuestionSetContract.textHash("row\n");
        Path datasetManifest = tempDir.resolve(
                Path.of("manifests", "datasets", "doc-ingestion-retrieval-v1.json"));
        Files.createDirectories(datasetManifest.getParent());
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("datasetHash", expectedHash);
        manifest.put("localPath", "datasets/doc-ingestion/doc-ingestion-retrieval-v1.jsonl");
        JSON.writeValue(datasetManifest.toFile(), manifest);
        Path sourceManifest = tempDir.resolve("source-manifest.json");
        Path chunkInventory = tempDir.resolve("chunk-inventory.json");
        Path chunkEvidence = tempDir.resolve("chunk-evidence.json");
        Files.writeString(sourceManifest, "sources\n");
        Files.writeString(chunkInventory, "inventory\n");
        Files.writeString(chunkEvidence, "evidence\n");
        Path evidenceManifest = tempDir.resolve("b3-export-manifest.json");
        JSON.writeValue(evidenceManifest.toFile(), JSON.createObjectNode()
                .put("datasetHash", expectedHash)
                .put("sourceManifestHash", "sha256:" + AcceptedQuestionSetContract.textHash("sources\n"))
                .put("chunkInventoryHash", "sha256:" + AcceptedQuestionSetContract.textHash("inventory\n"))
                .put("chunkEvidenceHash", "sha256:" + AcceptedQuestionSetContract.textHash("evidence\n")));

        AcceptedQuestionSetContract.validateEvidenceExportIdentity(evidenceManifest, expectedHash);
        Files.writeString(dataset, "tampered\n");

        assertThatThrownBy(() -> AcceptedQuestionSetContract.validateEvidenceExportIdentity(evidenceManifest, expectedHash))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("dataset bytes");
        Files.writeString(dataset, "row\n");
        Files.writeString(chunkEvidence, "tampered\n");
        assertThatThrownBy(() -> AcceptedQuestionSetContract.validateEvidenceExportIdentity(evidenceManifest, expectedHash))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("chunkEvidenceHash");
    }

    private static ObjectNode validBase() {
        ObjectNode base = JSON.createObjectNode().put("version", "manual-accepted-questions-v1");
        ArrayNode questions = base.putArray("questions");
        for (String format : FORMATS) {
            String lower = format.toLowerCase();
            for (int index = 0; index < 100; index++) {
                String split = index < 60 ? "calibration" : index < 80 ? "development" : "holdout";
                ObjectNode row = questions.addObject();
                row.put("id", "q-" + lower + "-" + index);
                row.put("evidenceId", "e-" + lower + "-" + index);
                row.put("filename", lower + "-" + index + ".dat");
                row.put("sourceSha256", lower + "-sha-" + index);
                row.put("format", format);
                row.put("split", split);
                row.put("chunkIndex", index);
                row.put("referenceChunkId", "chunk-" + lower + "-" + index);
                row.put("question", "What fact " + index + " is approved?");
                row.put("referenceNeedle", "Approved fact " + index);
                row.put("referenceAnswer", "Approved answer " + index);
                row.put("generationMethod", "llm-generated-llm-reviewed-v1");
                row.put("auditStatus", "llm-reviewed");
                ObjectNode provenance = row.putObject("llmProvenance");
                provenance.put("generatorModel", "gemini-test");
                provenance.put("generatorTool", "gemini-cli");
                provenance.put("reviewerModel", "gemini-test");
                provenance.put("reviewerTool", "gemini-cli");
                provenance.put("reviewerPromptVersion", "review-v1");
            }
        }
        return base;
    }

    private static ObjectNode manifestFor(ObjectNode base) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("baseHash", AcceptedQuestionSetContract.canonicalHash(base));
        manifest.put("evidenceExportManifestPath", "b3-export-manifest.json");
        manifest.put("evidenceExportDatasetHash", "sha256:evidence");
        manifest.put("reviewReceiptHash", "a".repeat(64));
        ArrayNode questions = (ArrayNode) base.path("questions");
        manifest.put("totalQuestions", questions.size());
        Map<String, Integer> perFormat = new LinkedHashMap<>();
        Map<String, Integer> perSplit = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> perFormatSplit = new LinkedHashMap<>();
        for (String split : List.of("calibration", "development", "holdout")) {
            ArrayNode projection = JSON.createArrayNode();
            questions.forEach(item -> {
                if (split.equals(item.path("split").asText())) {
                    projection.add(item);
                }
            });
            manifest.put(split + "Hash", AcceptedQuestionSetContract.canonicalHash(projection));
        }
        questions.forEach(item -> {
            String format = item.path("format").asText();
            String split = item.path("split").asText();
            perFormat.merge(format, 1, Integer::sum);
            perSplit.merge(split, 1, Integer::sum);
            perFormatSplit.computeIfAbsent(format, ignored -> new LinkedHashMap<>()).merge(split, 1, Integer::sum);
        });
        manifest.set("perFormatCounts", JSON.valueToTree(perFormat));
        manifest.set("perSplitCounts", JSON.valueToTree(perSplit));
        manifest.set("perFormatSplitCounts", JSON.valueToTree(perFormatSplit));
        return manifest;
    }

    private static ObjectNode overlay(String id, String field, String oldValue, String newValue) {
        ObjectNode row = JSON.createObjectNode();
        row.put("id", id);
        row.put("field", field);
        row.put("oldValue", oldValue);
        row.put("newValue", newValue);
        row.put("reason", "clarity");
        row.put("reviewer", "Reviewer");
        row.put("tuningAllowed", true);
        return row;
    }

    private static ObjectNode receipt(ObjectNode base,
                                      ObjectNode manifest,
                                      List<JsonNode> overlay,
                                      String boundContent) {
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("baseHash", manifest.path("baseHash").asText());
        receipt.put("overlayHash", AcceptedQuestionSetContract.overlayHash(overlay));
        receipt.put("evidenceExportDatasetHash", manifest.path("evidenceExportDatasetHash").asText());
        ObjectNode verifier = receipt.putObject("verifier");
        verifier.put("provider", "test");
        verifier.put("model", "test");
        verifier.put("profile", "test");
        ObjectNode effective = AcceptedQuestionSetContract.applyOverlay(base, overlay);
        Map<String, JsonNode> effectiveById = new LinkedHashMap<>();
        effective.path("questions").forEach(item -> effectiveById.put(item.path("id").asText(), item));
        ArrayNode rows = receipt.putArray("rows");
        overlay.stream().map(item -> item.path("id").asText()).distinct().forEach(id -> {
            JsonNode item = effectiveById.get(id);
            ObjectNode row = rows.addObject();
            row.put("id", id);
            row.put("questionHash", AcceptedQuestionSetContract.textHash(item.path("question").asText()));
            row.put("referenceAnswerHash", AcceptedQuestionSetContract.textHash(item.path("referenceAnswer").asText()));
            row.put("referenceContentHash", AcceptedQuestionSetContract.textHash(boundContent));
            row.put("questionAnswerableFromEvidence", true);
            row.put("referenceAnswerSupported", true);
            row.put("referenceAnswerAddressesQuestion", true);
            row.put("status", "pass");
        });
        return receipt;
    }

    private static ProductionDocumentIngestionEvalTest.IngestedDocument ingested(
            String docId, String sha256, String content) {
        var source = new ProductionDocumentIngestionEvalTest.SourceFile(
                "https://example.invalid/" + docId, null, "README.md", "text/markdown",
                "group", "WEB_MD", "test", "calibration", sha256);
        var chunk = KnowledgeChunkDTO.builder().id("chunk-" + docId).chunkIndex(0).content(content).build();
        var mq = new ProductionDocumentIngestionEvalTest.MqTrace(
                "event", "key", "PUBLISHED", true, "COMPLETED", LocalDateTime.now());
        var mineru = new ProductionDocumentIngestionEvalTest.MineruTrace(false, false, null, 0, List.of());
        return new ProductionDocumentIngestionEvalTest.IngestedDocument(
                source, docId, sha256, content.length(), "now", List.of(chunk), mq, mineru, true);
    }
}
