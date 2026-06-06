package com.yulong.chatagent.eval.v2.retrieval;

import com.yulong.chatagent.eval.v2.DeterministicEvalMetrics;
import com.yulong.chatagent.eval.v2.EvalArtifactWriter;
import com.yulong.chatagent.eval.v2.EvalConfigFingerprint;
import com.yulong.chatagent.eval.v2.EvalRunManifest;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

        for (RetrievalCase evalCase : cases) {
            long startNanos = System.nanoTime();
            List<RetrievalHit> hits = searcher.searchByKnowledgeBaseIds(request.knowledgeBaseIds(), evalCase.userInput());
            long latencyNanos = System.nanoTime() - startNanos;

            List<String> retrievedDocumentIds = hits.stream().map(RetrievalHit::documentId).toList();
            Set<String> relevant = new LinkedHashSet<>(evalCase.referenceContextIds());
            expectedSources.addAll(relevant);
            retrievedDocumentIds.stream().filter(relevant::contains).forEach(retrievedRelevantSources::add);
            List<String> retrievedTexts = hits.stream().map(RetrievalHit::content).toList();
            sampleMetrics.add(new SampleMetrics(
                    DeterministicEvalMetrics.hitAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.recallAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.precisionAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.reciprocalRank(retrievedDocumentIds, relevant),
                    DeterministicEvalMetrics.ndcgAtK(retrievedDocumentIds, relevant, request.config().topK()),
                    DeterministicEvalMetrics.phraseRecall(retrievedTexts, evalCase.requiredPhrases()),
                    latencyNanos
            ));
            samples.add(toSample(evalCase, hits, latencyNanos));
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
                Instant.now().toString(),
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
        return new RunResult(runDirectory, metrics, samples, configFingerprint);
    }

    private Map<String, Object> toSample(RetrievalCase evalCase, List<RetrievalHit> hits, long latencyNanos) {
        Map<String, Object> metadata = new LinkedHashMap<>(evalCase.metadata());
        metadata.put("latencyMs", nanosToMillis(latencyNanos));
        metadata.put("retrievedCount", hits.size());

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleId", evalCase.sampleId());
        sample.put("datasetId", evalCase.datasetId());
        sample.put("split", evalCase.split());
        sample.put("userInput", evalCase.userInput());
        sample.put("retrievedContexts", hits.stream().map(this::toContext).toList());
        sample.put("referenceContextIds", evalCase.referenceContextIds());
        sample.put("metadata", metadata);
        return sample;
    }

    private Map<String, Object> toContext(RetrievalHit hit) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("id", hit.documentId());
        context.put("text", hit.content());
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
