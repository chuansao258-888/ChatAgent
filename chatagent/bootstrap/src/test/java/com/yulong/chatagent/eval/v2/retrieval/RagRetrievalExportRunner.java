package com.yulong.chatagent.eval.v2.retrieval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.eval.v2.DeterministicEvalMetrics;
import com.yulong.chatagent.eval.v2.EvalArtifactWriter;
import com.yulong.chatagent.eval.v2.EvalConfigFingerprint;
import com.yulong.chatagent.eval.v2.EvalRunManifest;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RagRetrievalExportRunner {

    private static final List<String> ARTIFACT_FILES = List.of(
            "manifest.json",
            "metrics.json",
            "samples.jsonl",
            "failures.jsonl"
    );

    private final EvalArtifactWriter artifactWriter;
    private final SearcherFactory searcherFactory;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    RagRetrievalExportRunner(EvalArtifactWriter artifactWriter, SearcherFactory searcherFactory) {
        this.artifactWriter = artifactWriter;
        this.searcherFactory = searcherFactory;
    }

    RunResult run(RunRequest request, List<RetrievalCase> cases) {
        KnowledgeBaseSimilaritySearcher searcher = searcherFactory.create(request.config());
        List<Map<String, Object>> samples = new ArrayList<>();
        List<SampleMetrics> sampleMetrics = new ArrayList<>();
        LinkedHashSet<String> retrievedRelevantSources = new LinkedHashSet<>();
        LinkedHashSet<String> expectedSources = new LinkedHashSet<>();
        String timestamp = Instant.now().toString();

        for (RetrievalCase evalCase : cases) {
            long startNanos = System.nanoTime();
            List<KnowledgeBaseSimilaritySearcher.RankedCandidateHit> candidateHits = searcher.searchRankedCandidateHitsByKnowledgeBaseIds(
                    request.knowledgeBaseIds(), evalCase.userInput()
            );
            List<MilvusSearchHit> topHits = candidateHits.stream()
                    .map(KnowledgeBaseSimilaritySearcher.RankedCandidateHit::hit)
                    .limit(request.config().topK())
                    .toList();
            long latencyNanos = System.nanoTime() - startNanos;

            List<String> retrievedDocumentIds = topHits.stream().map(MilvusSearchHit::documentId).toList();
            Set<String> relevant = new LinkedHashSet<>(evalCase.referenceContextIds());
            expectedSources.addAll(relevant);
            retrievedDocumentIds.stream().filter(relevant::contains).forEach(retrievedRelevantSources::add);
            List<String> retrievedTexts = topHits.stream().map(hit -> hit.content() != null ? hit.content() : hit.retrievalText()).toList();
            sampleMetrics.add(new SampleMetrics(
                    DeterministicEvalMetrics.hitAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.recallAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.precisionAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.reciprocalRank(retrievedDocumentIds, relevant),
                    DeterministicEvalMetrics.ndcgAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.phraseRecall(retrievedTexts, evalCase.requiredPhrases()),
                    latencyNanos
            ));
            samples.add(toSample(evalCase, topHits, candidateHits, latencyNanos));
        }

        Map<String, Object> config = new LinkedHashMap<>(request.runConfig());
        config.putAll(request.config().asMap());
        String configFingerprint = EvalConfigFingerprint.sha256(config);
        Map<String, Object> metrics = aggregate(
                sampleMetrics,
                retrievedRelevantSources.size(),
                expectedSources.size(),
                request.config().topK()
        );
        EvalRunManifest manifest = new EvalRunManifest(
                request.runId(),
                "rag-retrieval",
                request.mode(),
                timestamp,
                request.gitBranch(),
                request.gitSha(),
                request.datasetId(),
                request.datasetHash(),
                config,
                configFingerprint,
                Map.of(),
                Map.of(),
                ARTIFACT_FILES,
                request.tuning()
        );
        Path runDirectory = artifactWriter.writeRun(manifest, metrics, samples, List.of());
        writeDatasetRoot(runDirectory, request, samples, timestamp);
        return new RunResult(runDirectory, metrics, samples, configFingerprint);
    }

    private Map<String, Object> toSample(
            RetrievalCase evalCase,
            List<MilvusSearchHit> topHits,
            List<KnowledgeBaseSimilaritySearcher.RankedCandidateHit> candidateHits,
            long latencyNanos
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(evalCase.metadata());
        metadata.put("latencyMs", nanosToMillis(latencyNanos));
        metadata.put("retrievedCount", topHits.size());
        metadata.put("candidateCount", candidateHits.size());
        metadata.put("candidateContexts", candidateContexts(candidateHits));

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleId", evalCase.sampleId());
        sample.put("datasetId", evalCase.datasetId());
        sample.put("split", evalCase.split());
        sample.put("userInput", evalCase.userInput());
        sample.put("retrievedContexts", topHits.stream().map(this::toContext).toList());
        sample.put("referenceContextIds", evalCase.referenceContextIds());
        sample.put("metadata", metadata);
        return sample;
    }

    private List<Map<String, Object>> candidateContexts(List<KnowledgeBaseSimilaritySearcher.RankedCandidateHit> hits) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            KnowledgeBaseSimilaritySearcher.RankedCandidateHit candidate = hits.get(index);
            MilvusSearchHit hit = candidate.hit();
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("id", hit.documentId());
            context.put("text", hit.content() != null ? hit.content() : hit.retrievalText());
            context.put("sourceId", hit.sourceId());
            context.put("score", candidate.fusedScore());
            context.put("scoreType", "rrf");
            Map<String, Object> rankSignals = new LinkedHashMap<>();
            rankSignals.put("candidateRank", index + 1);
            putIfPresent(rankSignals, "denseRank", candidate.denseRank());
            putIfPresent(rankSignals, "bm25Rank", candidate.bm25Rank());
            putIfPresent(rankSignals, "denseScore", candidate.denseScore());
            putIfPresent(rankSignals, "bm25Score", candidate.bm25Score());
            rankSignals.put("fusedScore", candidate.fusedScore());
            context.put("rankSignals", rankSignals);
            contexts.add(context);
        }
        return contexts;
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private Map<String, Object> toContext(MilvusSearchHit hit) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("id", hit.documentId());
        context.put("text", hit.content() != null ? hit.content() : hit.retrievalText());
        context.put("sourceId", hit.sourceId());
        context.put("score", hit.score());
        return context;
    }

    private Map<String, Object> aggregate(
            List<SampleMetrics> rows,
            int retrievedRelevantSourceCount,
            int expectedSourceCount,
            int topK
    ) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("sampleCount", rows.size());
        metrics.put("hitAtK", average(rows, SampleMetrics::hitAtK));
        metrics.put("recallAtK", average(rows, SampleMetrics::recallAtK));
        metrics.put("precisionAtK", average(rows, SampleMetrics::precisionAtK));
        metrics.put("mrr", average(rows, SampleMetrics::mrr));
        metrics.put("ndcgAtK", average(rows, SampleMetrics::ndcgAtK));
        metrics.put("phraseRecall", average(rows, SampleMetrics::phraseRecall));
        metrics.put("sourceCoverage", expectedSourceCount == 0 ? 0.0d : retrievedRelevantSourceCount / (double) expectedSourceCount);
        metrics.put("averageLatencyMs", average(rows, row -> nanosToMillis(row.latencyNanos())));
        metrics.put("p95LatencyMs", percentile95(rows.stream().map(SampleMetrics::latencyNanos).toList()));
        metrics.put("topK", topK);
        return metrics;
    }

    private void writeDatasetRoot(
            Path runDirectory,
            RunRequest request,
            List<Map<String, Object>> samples,
            String exportTimestamp
    ) {
        try {
            List<Map<String, Object>> datasetRows = datasetRows(samples);
            Path datasetPath = runDirectory.resolve(Path.of("datasets", "rag", request.datasetId() + ".jsonl"));
            Files.createDirectories(datasetPath.getParent());
            writeJsonLines(datasetPath, datasetRows);

            Map<String, Object> splitManifest = splitManifest(request.datasetId(), datasetRows);
            Path splitPath = runDirectory.resolve(Path.of("manifests", "splits", request.datasetId() + ".json"));
            Files.createDirectories(splitPath.getParent());
            objectMapper.writeValue(splitPath.toFile(), splitManifest);

            Map<String, Object> datasetManifest = datasetManifest(
                    request,
                    datasetRows,
                    splitManifest,
                    sha256(datasetPath),
                    sha256(splitPath),
                    exportTimestamp
            );
            Path manifestPath = runDirectory.resolve(Path.of("manifests", "datasets", request.datasetId() + ".json"));
            Files.createDirectories(manifestPath.getParent());
            objectMapper.writeValue(manifestPath.toFile(), datasetManifest);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write RAG retrieval tuning dataset root", exception);
        }
    }

    private List<Map<String, Object>> datasetRows(List<Map<String, Object>> samples) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> sample : samples) {
            Map<String, Object> metadata = metadata(sample);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sampleId", sample.get("sampleId"));
            row.put("datasetId", sample.get("datasetId"));
            row.put("sourceGroupId", sourceGroupId(sample, metadata));
            row.put("split", sample.get("split"));
            row.put("userInput", sample.get("userInput"));
            row.put("referenceContextIds", sample.get("referenceContextIds"));
            row.put("metadata", metadata);
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> sample) {
        Object value = sample.get("metadata");
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private String sourceGroupId(Map<String, Object> sample, Map<String, Object> metadata) {
        Object explicit = metadata.get("sourceGroupId");
        if (explicit != null && !explicit.toString().isBlank()) {
            return explicit.toString();
        }
        Object references = sample.get("referenceContextIds");
        if (references instanceof List<?> list && !list.isEmpty()) {
            return "doc:" + list.get(0);
        }
        return "sample:" + sample.get("sampleId");
    }

    private Map<String, Object> splitManifest(String datasetId, List<Map<String, Object>> rows) {
        Map<String, LinkedHashSet<String>> groupsBySplit = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            groupsBySplit.computeIfAbsent(row.get("split").toString(), ignored -> new LinkedHashSet<>())
                    .add(row.get("sourceGroupId").toString());
        }
        Map<String, Object> splits = new LinkedHashMap<>();
        groupsBySplit.forEach((split, groupIds) -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("groupIds", new ArrayList<>(groupIds));
            details.put("groupHash", sha256(groupIds));
            splits.put(split, details);
        });
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("datasetId", datasetId);
        manifest.put("splits", splits);
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> datasetManifest(
            RunRequest request,
            List<Map<String, Object>> rows,
            Map<String, Object> splitManifest,
            String datasetHash,
            String splitManifestHash,
            String exportTimestamp
    ) {
        Map<String, Object> splits = new LinkedHashMap<>();
        Map<String, Object> splitDetails = (Map<String, Object>) splitManifest.get("splits");
        splitDetails.forEach((split, detailsValue) -> {
            Map<String, Object> details = (Map<String, Object>) detailsValue;
            long recordCount = rows.stream().filter(row -> split.equals(row.get("split"))).count();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("recordCount", recordCount);
            summary.put("groupCount", ((List<?>) details.get("groupIds")).size());
            summary.put("groupHash", details.get("groupHash"));
            splits.put(split, summary);
        });
        Set<String> groupIds = new LinkedHashSet<>();
        rows.forEach(row -> groupIds.add(row.get("sourceGroupId").toString()));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("datasetId", request.datasetId());
        manifest.put("version", 1);
        manifest.put("sourceIds", sourceIds(request));
        manifest.put("recordSchema", "eval-retrieval-dataset-record.schema.json");
        manifest.put("localPath", "datasets/rag/" + request.datasetId() + ".jsonl");
        manifest.put("datasetHash", datasetHash);
        manifest.put("splitManifestPath", "manifests/splits/" + request.datasetId() + ".json");
        manifest.put("splitManifestHash", splitManifestHash);
        manifest.put("recordCount", rows.size());
        manifest.put("groupCount", groupIds.size());
        manifest.put("splits", splits);
        manifest.put("provenance", provenance(request.runConfig(), exportTimestamp));
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private List<String> sourceIds(RunRequest request) {
        Object rawSourceIds = request.runConfig().get("sourceIds");
        if (rawSourceIds instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of("beir-scifact");
    }

    private Map<String, Object> provenance(Map<String, Object> runConfig, String exportTimestamp) {
        String embeddingMode = String.valueOf(runConfig.getOrDefault("embeddingMode", "deterministic-hash"));
        String embeddingModel = String.valueOf(runConfig.getOrDefault("embeddingModel", embeddingMode));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("provider", embeddingMode.startsWith("ollama") ? "ollama" : "deterministic");
        provenance.put("modelName", embeddingModel);
        provenance.put("embeddingModel", embeddingModel);
        provenance.put("exportTimestamp", exportTimestamp);
        return provenance;
    }

    private void writeJsonLines(Path path, List<Map<String, Object>> rows) throws IOException {
        StringBuilder output = new StringBuilder();
        for (Map<String, Object> row : rows) {
            output.append(objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(row))
                    .append('\n');
        }
        Files.writeString(path, output, StandardCharsets.UTF_8);
    }

    private String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    private String sha256(Object value) {
        try {
            return sha256(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to hash evaluation manifest value", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private double average(List<SampleMetrics> rows, MetricValue value) {
        return rows.stream().mapToDouble(value::value).average().orElse(0.0d);
    }

    private double percentile95(List<Long> latencyNanos) {
        if (latencyNanos.isEmpty()) {
            return 0.0d;
        }
        List<Long> sorted = latencyNanos.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95d) - 1);
        return nanosToMillis(sorted.get(index));
    }

    private double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    interface SearcherFactory {
        KnowledgeBaseSimilaritySearcher create(RetrievalConfig config);
    }

    record RetrievalConfig(int topK, int candidateK, int rrfK) {
        RetrievalConfig {
            if (topK <= 0 || candidateK <= 0 || rrfK <= 0) {
                throw new IllegalArgumentException("Retrieval evaluation config values must be positive");
            }
        }

        Map<String, Object> asMap() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("topK", topK);
            config.put("candidateK", candidateK);
            config.put("rrfK", rrfK);
            return config;
        }
    }

    record RetrievalCase(
            String sampleId,
            String datasetId,
            String split,
            String userInput,
            List<String> referenceContextIds,
            List<String> requiredPhrases,
            Map<String, Object> metadata
    ) {
        RetrievalCase {
            referenceContextIds = List.copyOf(referenceContextIds);
            requiredPhrases = requiredPhrases == null ? List.of() : List.copyOf(requiredPhrases);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record RunRequest(
            String runId,
            String mode,
            String gitBranch,
            String gitSha,
            String datasetId,
            String datasetHash,
            List<String> knowledgeBaseIds,
            RetrievalConfig config,
            Map<String, Object> runConfig,
            EvalRunManifest.Tuning tuning
    ) {
        RunRequest {
            knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
            runConfig = runConfig == null ? Map.of() : Map.copyOf(runConfig);
        }
    }

    record RunResult(
            Path runDirectory,
            Map<String, Object> metrics,
            List<Map<String, Object>> samples,
            String configFingerprint
    ) {
    }

    private interface MetricValue {
        double value(SampleMetrics metrics);
    }

    private record SampleMetrics(
            double hitAtK,
            double recallAtK,
            double precisionAtK,
            double mrr,
            double ndcgAtK,
            double phraseRecall,
            long latencyNanos
    ) {
    }
}
