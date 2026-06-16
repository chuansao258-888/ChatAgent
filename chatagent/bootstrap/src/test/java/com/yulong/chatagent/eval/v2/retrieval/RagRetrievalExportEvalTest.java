package com.yulong.chatagent.eval.v2.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.eval.v2.EvalArtifactWriter;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.retrieve.NoopRetrievalReranker;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexService;
import com.yulong.chatagent.rag.vector.milvus.model.KnowledgeBaseMilvusChunkDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("eval-v2")
class RagRetrievalExportEvalTest {

    private static final int SMOKE_QUERY_COUNT = 50;
    private static final int SMOKE_DOCUMENT_COUNT = 1_000;
    private static final String KNOWLEDGE_BASE_ID = "eval-v2-scifact-smoke";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exportsRealSciFactBaselineAndTopKSensitivityArtifacts() throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        Path phase3Root = repositoryRoot.resolve("artifacts/eval/phase3");
        Path datasetPath = phase3Root.resolve("datasets/rag/beir-scifact-rag-v1.jsonl");
        Path corpusPath = phase3Root.resolve("corpora/beir-scifact/documents.jsonl");
        Path manifestPath = phase3Root.resolve("manifests/datasets/beir-scifact-rag-v1.json");
        assumeTrue(Files.exists(datasetPath) && Files.exists(corpusPath) && Files.exists(manifestPath),
                "Run Phase 3 SciFact preparation before the Phase 4 real-data smoke suite");

        List<RagRetrievalExportRunner.RetrievalCase> cases = loadCases(datasetPath);
        Set<String> requiredDocumentIds = cases.stream()
                .flatMap(evalCase -> evalCase.referenceContextIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<KnowledgeBaseMilvusChunkDocument> chunks = loadChunks(corpusPath, requiredDocumentIds);
        JsonNode manifest = objectMapper.readTree(manifestPath.toFile());

        try (EvalOwnedKnowledgeBaseFixture fixture = new EvalOwnedKnowledgeBaseFixture()) {
            fixture.createKnowledgeBase(KNOWLEDGE_BASE_ID, chunks);
            RagRetrievalExportRunner runner = new RagRetrievalExportRunner(
                    new EvalArtifactWriter(repositoryRoot.resolve("artifacts/eval/phase4")),
                    config -> searcher(fixture, config)
            );

            RagRetrievalExportRunner.RunResult baseline = runner.run(
                    request(
                            "phase4-scifact-baseline",
                            manifest,
                            new RagRetrievalExportRunner.RetrievalConfig(3, 12, 60),
                            cases.size(),
                            chunks.size()
                    ),
                    cases
            );
            RagRetrievalExportRunner.RunResult sensitivity = runner.run(
                    request(
                            "phase4-scifact-topk5",
                            manifest,
                            new RagRetrievalExportRunner.RetrievalConfig(5, 12, 60),
                            cases.size(),
                            chunks.size()
                    ),
                    cases
            );

            assertThat(baseline.samples()).hasSize(SMOKE_QUERY_COUNT);
            assertThat(baseline.metrics()).containsKeys("hitAtK", "recallAtK", "mrr", "ndcgAtK", "sourceCoverage", "p95LatencyMs");
            assertThat(baseline.runDirectory()).exists();
            assertThat(sensitivity.runDirectory()).exists();
            assertThat(baseline.configFingerprint()).isNotEqualTo(sensitivity.configFingerprint());
        }
    }

    private List<RagRetrievalExportRunner.RetrievalCase> loadCases(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path)) {
            return lines.map(this::readJson)
                    .filter(row -> "development".equals(row.path("split").asText()))
                    .limit(SMOKE_QUERY_COUNT)
                    .map(row -> new RagRetrievalExportRunner.RetrievalCase(
                            row.path("sampleId").asText(),
                            row.path("datasetId").asText(),
                            row.path("split").asText(),
                            row.path("userInput").asText(),
                            toStrings(row.path("referenceContextIds")),
                            List.of(),
                            Map.of(
                                    "sourceType", "real-public-corpus",
                                    "sourceId", "beir-scifact",
                                    "sourceGroupId", row.path("sourceGroupId").asText()
                            )
                    ))
                    .toList();
        }
    }

    private List<KnowledgeBaseMilvusChunkDocument> loadChunks(Path path, Set<String> requiredDocumentIds) throws IOException {
        Map<String, JsonNode> selected = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(path)) {
            lines.map(this::readJson)
                    .limit(SMOKE_DOCUMENT_COUNT)
                    .forEach(row -> selected.put(row.path("documentId").asText(), row));
        }
        if (!selected.keySet().containsAll(requiredDocumentIds)) {
            try (Stream<String> lines = Files.lines(path)) {
                lines.map(this::readJson)
                        .filter(row -> requiredDocumentIds.contains(row.path("documentId").asText()))
                        .forEach(row -> selected.put(row.path("documentId").asText(), row));
            }
        }
        assertThat(selected.keySet()).containsAll(requiredDocumentIds);
        return selected.values().stream().map(this::toChunk).toList();
    }

    private KnowledgeBaseMilvusChunkDocument toChunk(JsonNode row) {
        String documentId = row.path("documentId").asText();
        String title = row.path("title").asText();
        String text = row.path("text").asText();
        String searchable = title + " " + text;
        return new KnowledgeBaseMilvusChunkDocument(
                documentId + "#0",
                KNOWLEDGE_BASE_ID,
                documentId,
                0,
                title,
                title,
                text,
                title,
                searchable,
                searchable,
                true,
                0L,
                EvalOwnedKnowledgeBaseFixture.embedText(searchable)
        );
    }

    private RagRetrievalExportRunner.RunRequest request(
            String runId,
            JsonNode manifest,
            RagRetrievalExportRunner.RetrievalConfig config,
            int queryCount,
            int indexedDocumentCount
    ) {
        return new RagRetrievalExportRunner.RunRequest(
                runId,
                "smoke",
                runtimeMetadata("chatagent.eval.gitBranch", "GIT_BRANCH"),
                runtimeMetadata("chatagent.eval.gitSha", "GIT_COMMIT"),
                manifest.path("datasetId").asText(),
                manifest.path("datasetHash").asText(),
                List.of(KNOWLEDGE_BASE_ID),
                config,
                Map.of(
                        "split", "development",
                        "queryLimit", SMOKE_QUERY_COUNT,
                        "baseDocumentLimit", SMOKE_DOCUMENT_COUNT,
                        "queryCount", queryCount,
                        "indexedDocumentCount", indexedDocumentCount,
                        "retrievalBackend", "in-memory-eval-fixture",
                        "embeddingMode", "deterministic-hash",
                        "reranker", "noop"
                ),
                null
        );
    }

    private String runtimeMetadata(String propertyName, String environmentName) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank() ? "unknown" : environmentValue;
    }

    @SuppressWarnings("unchecked")
    private KnowledgeBaseSimilaritySearcher searcher(
            EvalOwnedKnowledgeBaseFixture fixture,
            RagRetrievalExportRunner.RetrievalConfig config
    ) {
        OllamaEmbeddingClient embeddingClient = mock(OllamaEmbeddingClient.class);
        when(embeddingClient.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> EvalOwnedKnowledgeBaseFixture.embedText(invocation.getArgument(0)));
        KnowledgeDocumentSignalService signalService = mock(KnowledgeDocumentSignalService.class);
        when(signalService.attachSignals(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectProvider<KnowledgeBaseMilvusIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fixture);
        return new KnowledgeBaseSimilaritySearcher(
                embeddingClient,
                config.topK(),
                config.candidateK(),
                config.rrfK(),
                signalService,
                new NoopRetrievalReranker(),
                provider
        );
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !isRepositoryRoot(current)) {
            current = current.getParent();
        }
        assumeTrue(current != null, "Repository root was not found");
        return current;
    }

    private boolean isRepositoryRoot(Path path) {
        return Files.exists(path.resolve("README.md"))
                && Files.exists(path.resolve("chatagent").resolve("pom.xml"));
    }

    private JsonNode readJson(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse real SciFact record", exception);
        }
    }

    private List<String> toStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
