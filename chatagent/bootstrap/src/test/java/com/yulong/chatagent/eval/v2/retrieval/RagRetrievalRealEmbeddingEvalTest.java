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
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

/**
 * Real Ollama bge-m3 embedding retrieval evaluation.
 *
 * <p>Requires a running Ollama instance with the {@code bge-m3} model pulled.
 * Skips gracefully via {@code Assumptions.assumeTrue} when Ollama is unavailable.
 * Tagged {@code eval-real} to exclude from default CI; activate with the
 * {@code eval-real} Maven profile or by clearing {@code surefire.excludedGroups}.
 */
@Tag("eval-v2")
@Tag("eval-real")
class RagRetrievalRealEmbeddingEvalTest {

    private static final String OLLAMA_BASE_URL = setting(
            "chatagent.eval.ollamaBaseUrl",
            "CHATAGENT_RAG_EMBEDDING_BASE_URL",
            "http://127.0.0.1:11434"
    );
    private static final String EMBEDDING_MODEL = setting(
            "chatagent.eval.embeddingModel",
            "CHATAGENT_RAG_EMBEDDING_MODEL",
            "bge-m3"
    );
    private static final int SMOKE_QUERY_COUNT = 50;
    private static final int SMOKE_DOCUMENT_COUNT = 1_000;
    private static final String KNOWLEDGE_BASE_ID = "eval-v2-scifact-ollama";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exportsRealOllamaEmbeddingSciFactRetrieval() throws Exception {
        assumeOllamaEndpointAvailable();

        OllamaEmbeddingClient embeddingClient = new OllamaEmbeddingClient(
                WebClient.builder(), OLLAMA_BASE_URL, EMBEDDING_MODEL
        );

        // Probe: verify bge-m3 model is loaded by embedding a short string
        float[] probeEmbedding;
        try {
            probeEmbedding = embeddingClient.embed("eval probe");
        } catch (Exception e) {
            assumeTrue(false, "Ollama embedding probe failed (model '" + EMBEDDING_MODEL + "' may not be pulled): " + e.getMessage());
            return;
        }
        assertThat(probeEmbedding).hasSize(EvalOwnedKnowledgeBaseFixture.OLLAMA_EMBEDDING_DIMENSION);

        Path repositoryRoot = findRepositoryRoot();
        Path phase3Root = repositoryRoot.resolve("artifacts/eval/phase3");
        Path datasetPath = phase3Root.resolve("datasets/rag/beir-scifact-rag-v1.jsonl");
        Path corpusPath = phase3Root.resolve("corpora/beir-scifact/documents.jsonl");
        Path manifestPath = phase3Root.resolve("manifests/datasets/beir-scifact-rag-v1.json");
        assumeTrue(Files.exists(datasetPath) && Files.exists(corpusPath) && Files.exists(manifestPath),
                "Run Phase 3 SciFact preparation before the Phase 10a real-embedding smoke");

        List<RagRetrievalExportRunner.RetrievalCase> cases = loadCases(datasetPath);
        Set<String> requiredDocumentIds = cases.stream()
                .flatMap(evalCase -> evalCase.referenceContextIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        JsonNode manifest = objectMapper.readTree(manifestPath.toFile());

        try (EvalOwnedKnowledgeBaseFixture fixture = new EvalOwnedKnowledgeBaseFixture(
                embeddingClient::embed, EvalOwnedKnowledgeBaseFixture.OLLAMA_EMBEDDING_DIMENSION
        )) {
            List<KnowledgeBaseMilvusChunkDocument> chunks = loadChunks(
                    corpusPath, requiredDocumentIds, fixture
            );
            fixture.createKnowledgeBase(KNOWLEDGE_BASE_ID, chunks);

            RagRetrievalExportRunner runner = new RagRetrievalExportRunner(
                    new EvalArtifactWriter(repositoryRoot.resolve("artifacts/eval/phase10a")),
                    config -> realSearcher(fixture, embeddingClient, config)
            );

            RagRetrievalExportRunner.RunResult baseline = runner.run(
                    request(
                            "phase10a-scifact-ollama-baseline",
                            manifest,
                            new RagRetrievalExportRunner.RetrievalConfig(3, 12, 60),
                            cases.size(),
                            chunks.size()
                    ),
                    cases
            );

            assertThat(baseline.samples()).hasSize(SMOKE_QUERY_COUNT);
            assertThat(baseline.metrics()).containsKeys("hitAtK", "recallAtK", "mrr", "ndcgAtK", "sourceCoverage");
            assertThat(baseline.runDirectory()).exists();

            // Verify manifest records real embedding metadata
            JsonNode runManifest = objectMapper.readTree(baseline.runDirectory().resolve("manifest.json").toFile());
            assertThat(runManifest.path("config").path("embeddingMode").asText()).isEqualTo("ollama-" + EMBEDDING_MODEL);
            assertThat(runManifest.path("config").path("embeddingDimension").intValue()).isEqualTo(1024);
            assertThat(runManifest.path("config").path("embeddingBaseUrl").asText()).isEqualTo(OLLAMA_BASE_URL);

            // Verify samples include real retrieval scores (not all zero)
            JsonNode firstSample = objectMapper.readTree(
                    Files.readAllLines(baseline.runDirectory().resolve("samples.jsonl")).get(0)
            );
            assertThat(firstSample.path("retrievedContexts").size()).isGreaterThan(0);
            double firstScore = firstSample.path("retrievedContexts").get(0).path("score").asDouble();
            assertThat(firstScore).isGreaterThan(0.0);
        }
    }

    private static void assumeOllamaEndpointAvailable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_BASE_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assumeTrue(response.statusCode() == 200,
                    "Ollama returned non-200 status: " + response.statusCode());
        } catch (ConnectException e) {
            assumeTrue(false, "Ollama is not running at " + OLLAMA_BASE_URL + ": " + e.getMessage());
        } catch (Exception e) {
            assumeTrue(false, "Ollama probe failed: " + e.getMessage());
        }
    }

    private static String setting(String propertyName, String environmentName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
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

    private List<KnowledgeBaseMilvusChunkDocument> loadChunks(
            Path path, Set<String> requiredDocumentIds, EvalOwnedKnowledgeBaseFixture fixture
    ) throws IOException {
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
        return selected.values().stream().map(row -> toChunk(row, fixture)).toList();
    }

    private KnowledgeBaseMilvusChunkDocument toChunk(JsonNode row, EvalOwnedKnowledgeBaseFixture fixture) {
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
                fixture.embed(searchable)
        );
    }

    @SuppressWarnings("unchecked")
    private KnowledgeBaseSimilaritySearcher realSearcher(
            EvalOwnedKnowledgeBaseFixture fixture,
            OllamaEmbeddingClient embeddingClient,
            RagRetrievalExportRunner.RetrievalConfig config
    ) {
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
                runConfig(queryCount, indexedDocumentCount),
                null
        );
    }

    private Map<String, Object> runConfig(int queryCount, int indexedDocumentCount) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("split", "development");
        config.put("queryLimit", SMOKE_QUERY_COUNT);
        config.put("baseDocumentLimit", SMOKE_DOCUMENT_COUNT);
        config.put("queryCount", queryCount);
        config.put("indexedDocumentCount", indexedDocumentCount);
        config.put("retrievalBackend", "in-memory-eval-fixture");
        config.put("sourceIds", List.of("beir-scifact"));
        config.put("embeddingMode", "ollama-" + EMBEDDING_MODEL);
        config.put("embeddingDimension", EvalOwnedKnowledgeBaseFixture.OLLAMA_EMBEDDING_DIMENSION);
        config.put("embeddingModel", EMBEDDING_MODEL);
        config.put("embeddingBaseUrl", OLLAMA_BASE_URL);
        config.put("reranker", "noop");
        return config;
    }

    private String runtimeMetadata(String propertyName, String environmentName) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank() ? "unknown" : environmentValue;
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("AGENTS.md"))) {
            current = current.getParent();
        }
        assumeTrue(current != null, "Repository root with AGENTS.md was not found");
        return current;
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
