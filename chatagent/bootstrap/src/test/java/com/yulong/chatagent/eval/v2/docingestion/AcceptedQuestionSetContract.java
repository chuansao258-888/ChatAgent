package com.yulong.chatagent.eval.v2.docingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-closed B3.3 accepted-question, overlay, and hash contract.
 */
final class AcceptedQuestionSetContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FORMATS = List.of("SEC_HTML", "PDF", "DOCX", "XLSX", "WEB_MD");
    private static final List<String> SPLITS = List.of("calibration", "development", "holdout");
    private static final Map<String, Integer> MIN_PER_FORMAT_SPLIT = Map.of(
            "calibration", 20,
            "development", 10,
            "holdout", 10);
    private static final Set<String> OVERLAY_KEYS = Set.of(
            "id", "field", "oldValue", "newValue", "reason", "reviewer", "tuningAllowed");
    private static final Set<String> OVERLAY_FIELDS = Set.of("question", "referenceAnswer");
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final Pattern LOWER_HEX_SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private AcceptedQuestionSetContract() {
    }

    static ValidatedQuestionSet validate(JsonNode base,
                                         JsonNode manifest,
                                         List<JsonNode> overlayRows,
                                         JsonNode overlayValidationReceipt) {
        assertThat(base).as("accepted question base is required").isNotNull();
        assertThat(manifest).as("accepted question manifest is required").isNotNull();
        assertThat(base.isObject()).as("accepted question base must be a JSON object").isTrue();
        assertThat(manifest.isObject()).as("accepted question manifest must be a JSON object").isTrue();

        String baseHash = canonicalHash(base);
        assertTextEquals(manifest, "baseHash", baseHash);
        assertThat(text(manifest, "evidenceExportManifestPath"))
                .as("question-set manifest must identify the frozen B3.2 export manifest")
                .isNotBlank();
        String evidenceHash = text(manifest, "evidenceExportDatasetHash");
        assertThat(evidenceHash).as("question-set manifest must identify the frozen B3.2 dataset hash").isNotBlank();
        assertThat(text(manifest, "reviewReceiptHash"))
                .as("question-set manifest must include the Gemini-review receipt hash")
                .matches(LOWER_HEX_SHA256_PATTERN);

        ArrayNode questions = requiredQuestions(base);
        assertAcceptedRowsAndCounts(questions, manifest);
        for (String split : SPLITS) {
            ArrayNode projection = JSON.createArrayNode();
            for (JsonNode question : questions) {
                if (split.equals(text(question, "split"))) {
                    projection.add(question);
                }
            }
            assertTextEquals(manifest, split + "Hash", canonicalHash(projection));
        }

        if (overlayRows == null || overlayRows.isEmpty()) {
            return new ValidatedQuestionSet(base.deepCopy(), baseHash, null, evidenceHash, Map.of());
        }

        List<JsonNode> sortedOverlay = validateAndSortOverlay(base, overlayRows);
        String overlayHash = overlayHash(sortedOverlay);
        ObjectNode effective = applyOverlay(base, sortedOverlay);
        Map<String, ReceiptRow> receiptRows = validateReceipt(
                overlayValidationReceipt, baseHash, overlayHash, evidenceHash, sortedOverlay);
        return new ValidatedQuestionSet(effective, baseHash, overlayHash, evidenceHash, receiptRows);
    }

    static void validateBoundReceipt(ValidatedQuestionSet questionSet, Map<String, BoundEvidence> boundById) {
        if (questionSet.overlayHash() == null) {
            return;
        }
        ArrayNode questions = requiredQuestions(questionSet.effectiveBase());
        Map<String, JsonNode> byId = new HashMap<>();
        questions.forEach(item -> byId.put(text(item, "id"), item));
        assertThat(boundById.keySet())
                .as("overlay validation must bind every and only modified question")
                .containsExactlyInAnyOrderElementsOf(questionSet.receiptRows().keySet());
        questionSet.receiptRows().forEach((id, receipt) -> {
            JsonNode question = byId.get(id);
            BoundEvidence bound = boundById.get(id);
            assertThat(question).as("effective overlay question must exist: %s", id).isNotNull();
            assertThat(bound).as("bound overlay evidence must exist: %s", id).isNotNull();
            assertThat(textHash(text(question, "question"))).as("overlay question hash must match: %s", id)
                    .isEqualTo(receipt.questionHash());
            assertThat(textHash(text(question, "referenceAnswer"))).as("overlay answer hash must match: %s", id)
                    .isEqualTo(receipt.referenceAnswerHash());
            assertThat(textHash(bound.referenceContent())).as("overlay content hash must match: %s", id)
                    .isEqualTo(receipt.referenceContentHash());
        });
    }

    static void validateEvidenceExportIdentity(Path evidenceManifestPath, String expectedDatasetHash) throws IOException {
        assertThat(evidenceManifestPath).as("frozen B3.2 evidence-export manifest must exist").exists();
        JsonNode evidenceManifest = JSON.readTree(evidenceManifestPath.toFile());
        assertThat(text(evidenceManifest, "datasetHash"))
                .as("accepted question manifest must match frozen B3.2 evidence dataset hash")
                .isEqualTo(expectedDatasetHash);
        Path evidenceRoot = evidenceManifestPath.getParent();
        Path datasetManifestPath = evidenceRoot.resolve(
                Path.of("manifests", "datasets", "doc-ingestion-retrieval-v1.json"));
        assertThat(datasetManifestPath).as("frozen B3.2 dataset manifest must exist").exists();
        JsonNode datasetManifest = JSON.readTree(datasetManifestPath.toFile());
        assertThat(text(datasetManifest, "datasetHash"))
                .as("frozen B3.2 dataset manifest hash must match accepted question identity")
                .isEqualTo(expectedDatasetHash);
        Path datasetPath = evidenceRoot.resolve(text(datasetManifest, "localPath")).normalize();
        assertThat(datasetPath).as("frozen B3.2 evidence dataset must exist").exists();
        assertThat(fileHash(datasetPath))
                .as("frozen B3.2 evidence dataset bytes must match accepted question identity")
                .isEqualTo(expectedDatasetHash);
        for (Map.Entry<String, String> artifact : Map.of(
                "sourceManifestHash", "source-manifest.json",
                "chunkInventoryHash", "chunk-inventory.json",
                "chunkEvidenceHash", "chunk-evidence.json").entrySet()) {
            String expectedHash = text(evidenceManifest, artifact.getKey());
            assertThat(expectedHash)
                    .as("frozen B3.2 export manifest must include %s", artifact.getKey())
                    .isNotBlank();
            Path artifactPath = evidenceRoot.resolve(artifact.getValue());
            assertThat(artifactPath).as("frozen B3.2 evidence artifact must exist: %s", artifact.getValue()).exists();
            assertThat(fileHash(artifactPath))
                    .as("frozen B3.2 evidence artifact bytes must match %s", artifact.getKey())
                    .isEqualTo(expectedHash);
        }
    }

    static String canonicalHash(JsonNode value) {
        return sha256(canonicalJson(value));
    }

    static String textHash(String value) {
        return sha256(value.replace("\r\n", "\n").replace("\r", "\n"));
    }

    static String overlayHash(List<JsonNode> rows) {
        ArrayNode sorted = JSON.createArrayNode();
        rows.stream()
                .sorted(Comparator.comparing((JsonNode row) -> text(row, "id"))
                        .thenComparing(row -> text(row, "field")))
                .forEach(sorted::add);
        return canonicalHash(sorted);
    }

    static ObjectNode applyOverlay(JsonNode base, List<JsonNode> rows) {
        ObjectNode result = base.deepCopy();
        Map<String, ObjectNode> byId = new HashMap<>();
        requiredQuestions(result).forEach(item -> byId.put(text(item, "id"), (ObjectNode) item));
        for (JsonNode row : rows) {
            byId.get(text(row, "id")).set(text(row, "field"), row.get("newValue"));
        }
        return result;
    }

    static String canonicalJson(JsonNode value) {
        StringBuilder result = new StringBuilder();
        appendCanonical(result, value, 0);
        return result.append('\n').toString();
    }

    private static void assertAcceptedRowsAndCounts(ArrayNode questions, JsonNode manifest) {
        assertThat(questions.size()).as("accepted question set must contain at least 500 questions")
                .isGreaterThanOrEqualTo(500);
        Set<String> ids = new HashSet<>();
        Map<String, Integer> perFormat = new LinkedHashMap<>();
        Map<String, Integer> perSplit = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> perFormatSplit = new LinkedHashMap<>();
        int manualReplacementCount = 0;
        for (JsonNode item : questions) {
            String id = text(item, "id");
            assertThat(id).as("accepted question id must be valid ASCII").matches(ID_PATTERN);
            assertThat(ids.add(id)).as("accepted question ids must be unique: %s", id).isTrue();
            assertThat(text(item, "auditStatus")).as("accepted question must be llm-reviewed: %s", id)
                    .isEqualTo("llm-reviewed");
            String generationMethod = text(item, "generationMethod");
            assertThat(generationMethod).as("accepted question must preserve reviewed generation provenance: %s", id)
                    .isIn("llm-generated-llm-reviewed-v1", "codex-manual-assisted-llm-reviewed-v1");
            if ("codex-manual-assisted-llm-reviewed-v1".equals(generationMethod)) {
                manualReplacementCount++;
            }
            JsonNode provenance = item.get("llmProvenance");
            assertThat(provenance).as("accepted question must include llmProvenance: %s", id).isNotNull();
            for (String field : List.of(
                    "generatorModel", "generatorTool", "reviewerModel", "reviewerTool", "reviewerPromptVersion")) {
                assertThat(text(provenance, field)).as("accepted question %s must include %s", id, field).isNotBlank();
            }
            for (String field : List.of(
                    "evidenceId", "filename", "sourceSha256", "format", "split", "question",
                    "referenceNeedle", "referenceAnswer", "referenceChunkId")) {
                assertThat(text(item, field)).as("accepted question %s must include %s", id, field).isNotBlank();
            }
            assertThat(item.path("chunkIndex").isIntegralNumber())
                    .as("accepted question %s must include integral chunkIndex", id).isTrue();
            String format = text(item, "format");
            String split = text(item, "split");
            assertThat(FORMATS).as("accepted question format must be supported: %s", id).contains(format);
            assertThat(SPLITS).as("accepted question split must be supported: %s", id).contains(split);
            perFormat.merge(format, 1, Integer::sum);
            perSplit.merge(split, 1, Integer::sum);
            perFormatSplit.computeIfAbsent(format, ignored -> new LinkedHashMap<>()).merge(split, 1, Integer::sum);
        }
        for (String format : FORMATS) {
            assertThat(perFormat.getOrDefault(format, 0)).as("%s needs at least 50 accepted questions", format)
                    .isGreaterThanOrEqualTo(50);
            for (Map.Entry<String, Integer> minimum : MIN_PER_FORMAT_SPLIT.entrySet()) {
                assertThat(perFormatSplit.getOrDefault(format, Map.of()).getOrDefault(minimum.getKey(), 0))
                        .as("%s/%s accepted question minimum", format, minimum.getKey())
                        .isGreaterThanOrEqualTo(minimum.getValue());
            }
        }
        assertThat(manifest.path("totalQuestions").asInt(-1)).isEqualTo(questions.size());
        assertThat(manualReplacementCount).as("at most one Codex/manual-assisted replacement is allowed")
                .isLessThanOrEqualTo(1);
        assertThat(manifest.path("manualReplacementCount").asInt(0)).isEqualTo(manualReplacementCount);
        if (manualReplacementCount == 1) {
            assertThat(text(manifest, "manualReplacementReceiptHash"))
                    .as("Codex/manual-assisted replacement receipt hash").matches(LOWER_HEX_SHA256_PATTERN);
        }
        assertCounts(manifest.path("perFormatCounts"), perFormat, "perFormatCounts");
        assertCounts(manifest.path("perSplitCounts"), perSplit, "perSplitCounts");
        for (String format : FORMATS) {
            assertCounts(manifest.path("perFormatSplitCounts").path(format),
                    perFormatSplit.getOrDefault(format, Map.of()), "perFormatSplitCounts." + format);
        }
    }

    private static List<JsonNode> validateAndSortOverlay(JsonNode base, List<JsonNode> rows) {
        Map<String, JsonNode> baseById = new HashMap<>();
        requiredQuestions(base).forEach(item -> baseById.put(text(item, "id"), item));
        Set<String> seen = new HashSet<>();
        List<JsonNode> sorted = new ArrayList<>();
        for (JsonNode row : rows) {
            assertThat(fieldNames(row)).as("overlay entry must contain exactly the allowed envelope keys")
                    .containsExactlyInAnyOrderElementsOf(OVERLAY_KEYS);
            String id = text(row, "id");
            String field = text(row, "field");
            assertThat(OVERLAY_FIELDS).as("overlay field is forbidden: %s", field).contains(field);
            JsonNode baseItem = baseById.get(id);
            assertThat(baseItem).as("overlay references unknown id: %s", id).isNotNull();
            assertThat(text(baseItem, "split")).as("overlay cannot modify holdout id: %s", id)
                    .isNotEqualTo("holdout");
            assertThat(seen.add(id + "\n" + field)).as("duplicate overlay entry: %s/%s", id, field).isTrue();
            assertThat(text(row, "reviewer")).as("overlay reviewer is required: %s", id).isNotBlank();
            assertThat(row.path("tuningAllowed").isBoolean() && row.path("tuningAllowed").booleanValue())
                    .as("overlay tuningAllowed must be true: %s", id).isTrue();
            assertThat(row.get("oldValue")).as("overlay oldValue mismatch: %s/%s", id, field)
                    .isEqualTo(baseItem.get(field));
            assertThat(text(row, "newValue")).as("overlay newValue must be non-empty: %s/%s", id, field)
                    .isNotBlank();
            sorted.add(row);
        }
        sorted.sort(Comparator.comparing((JsonNode row) -> text(row, "id"))
                .thenComparing(row -> text(row, "field")));
        return sorted;
    }

    private static Map<String, ReceiptRow> validateReceipt(JsonNode receipt,
                                                            String baseHash,
                                                            String overlayHash,
                                                            String evidenceHash,
                                                            List<JsonNode> overlayRows) {
        assertThat(receipt).as("overlay validation receipt is required").isNotNull();
        assertTextEquals(receipt, "baseHash", baseHash);
        assertTextEquals(receipt, "overlayHash", overlayHash);
        assertTextEquals(receipt, "evidenceExportDatasetHash", evidenceHash);
        JsonNode verifier = receipt.path("verifier");
        assertThat(verifier.isObject()).as("overlay validation receipt verifier provenance is required").isTrue();
        for (String field : List.of("provider", "model", "profile")) {
            assertThat(text(verifier, field)).as("overlay verifier %s is required", field).isNotBlank();
        }
        Set<String> modifiedIds = new TreeSet<>();
        overlayRows.forEach(row -> modifiedIds.add(text(row, "id")));
        Map<String, ReceiptRow> rows = new LinkedHashMap<>();
        JsonNode receiptRows = receipt.path("rows");
        assertThat(receiptRows.isArray()).as("overlay validation receipt rows are required").isTrue();
        for (JsonNode row : receiptRows) {
            String id = text(row, "id");
            assertThat(modifiedIds).as("receipt cannot contain an unmodified id: %s", id).contains(id);
            assertThat(rows).as("receipt id must be unique: %s", id).doesNotContainKey(id);
            assertThat(text(row, "status")).as("overlay receipt row must pass: %s", id).isEqualTo("pass");
            for (String field : List.of(
                    "questionAnswerableFromEvidence", "referenceAnswerSupported", "referenceAnswerAddressesQuestion")) {
                assertThat(row.path(field).isBoolean() && row.path(field).booleanValue())
                        .as("overlay receipt %s must prove %s", id, field).isTrue();
            }
            rows.put(id, new ReceiptRow(
                    text(row, "questionHash"),
                    text(row, "referenceAnswerHash"),
                    text(row, "referenceContentHash")));
        }
        assertThat(rows.keySet()).containsExactlyInAnyOrderElementsOf(modifiedIds);
        rows.forEach((id, row) -> {
            assertThat(row.questionHash()).as("receipt questionHash is required: %s", id).isNotBlank();
            assertThat(row.referenceAnswerHash()).as("receipt referenceAnswerHash is required: %s", id).isNotBlank();
            assertThat(row.referenceContentHash()).as("receipt referenceContentHash is required: %s", id).isNotBlank();
        });
        return Map.copyOf(rows);
    }

    private static void assertCounts(JsonNode actualNode, Map<String, Integer> expected, String label) {
        assertThat(actualNode.isObject()).as("manifest %s is required", label).isTrue();
        Map<String, Integer> actual = new LinkedHashMap<>();
        actualNode.fields().forEachRemaining(entry -> actual.put(entry.getKey(), entry.getValue().asInt()));
        assertThat(actual).as("manifest %s must match accepted rows", label).isEqualTo(expected);
    }

    private static ArrayNode requiredQuestions(JsonNode base) {
        JsonNode questions = base.path("questions");
        assertThat(questions.isArray()).as("accepted question base must contain questions array").isTrue();
        return (ArrayNode) questions;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertTextEquals(JsonNode node, String field, String expected) {
        assertThat(text(node, field)).as("%s must match", field).isEqualTo(expected);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String fileHash(Path path) throws IOException {
        return "sha256:" + sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendCanonical(StringBuilder result, JsonNode node, int depth) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            if (names.isEmpty()) {
                result.append("{}");
                return;
            }
            result.append("{\n");
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index);
                indent(result, depth + 1);
                appendQuoted(result, name);
                result.append(": ");
                appendCanonical(result, node.get(name), depth + 1);
                result.append(index + 1 == names.size() ? '\n' : ",\n");
            }
            indent(result, depth);
            result.append('}');
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                result.append("[]");
                return;
            }
            result.append("[\n");
            for (int index = 0; index < node.size(); index++) {
                indent(result, depth + 1);
                appendCanonical(result, node.get(index), depth + 1);
                result.append(index + 1 == node.size() ? '\n' : ",\n");
            }
            indent(result, depth);
            result.append(']');
            return;
        }
        if (node.isTextual()) {
            appendQuoted(result, node.textValue());
            return;
        }
        result.append(node.toString());
    }

    private static void appendQuoted(StringBuilder result, String value) {
        try {
            String encoded = JSON.writeValueAsString(value);
            for (int index = 0; index < encoded.length(); index++) {
                char current = encoded.charAt(index);
                result.append(current);
            if (current != '\\'
                    || index + 5 >= encoded.length()
                    || encoded.charAt(index + 1) != 'u'
                    || hasOddPrecedingBackslashes(encoded, index)) {
                continue;
            }
            result.append('u');
            for (int hexIndex = index + 2; hexIndex <= index + 5; hexIndex++) {
                result.append(Character.toLowerCase(encoded.charAt(hexIndex)));
            }
                index += 5;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode canonical JSON string", e);
        }
    }

    private static boolean hasOddPrecedingBackslashes(String value, int index) {
        int count = 0;
        for (int cursor = index - 1; cursor >= 0 && value.charAt(cursor) == '\\'; cursor--) {
            count++;
        }
        return count % 2 != 0;
    }

    private static void indent(StringBuilder result, int depth) {
        result.append("  ".repeat(depth));
    }

    record ValidatedQuestionSet(JsonNode effectiveBase,
                                String baseHash,
                                String overlayHash,
                                String evidenceExportDatasetHash,
                                Map<String, ReceiptRow> receiptRows) {
    }

    record ReceiptRow(String questionHash, String referenceAnswerHash, String referenceContentHash) {
    }

    record BoundEvidence(String question, String referenceAnswer, String referenceContent) {
    }
}
