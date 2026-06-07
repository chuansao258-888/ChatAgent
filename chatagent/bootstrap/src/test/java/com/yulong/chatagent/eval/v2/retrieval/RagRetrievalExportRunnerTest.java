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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrievalExportRunnerTest {

    private static final String KNOWLEDGE_BASE_ID = "eval-v2-retrieval-unit";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void exportsProductionRetrievalMetricsAndExactTrialConfig() throws Exception {
        try (EvalOwnedKnowledgeBaseFixture fixture = fixture()) {
            List<RagRetrievalExportRunner.RetrievalConfig> createdConfigs = new ArrayList<>();
            RagRetrievalExportRunner runner = new RagRetrievalExportRunner(
                    new EvalArtifactWriter(tempDirectory),
                    config -> {
                        createdConfigs.add(config);
                        return searcher(fixture, config);
                    }
            );

            RagRetrievalExportRunner.RunResult baseline = runner.run(
                    request("baseline", new RagRetrievalExportRunner.RetrievalConfig(2, 3, 60)),
                    cases()
            );
            RagRetrievalExportRunner.RunResult sensitivity = runner.run(
                    request("topk-3", new RagRetrievalExportRunner.RetrievalConfig(3, 3, 60)),
                    cases()
            );

            assertThat(createdConfigs).containsExactly(
                    new RagRetrievalExportRunner.RetrievalConfig(2, 3, 60),
                    new RagRetrievalExportRunner.RetrievalConfig(3, 3, 60)
            );
            assertThat(baseline.configFingerprint()).isNotEqualTo(sensitivity.configFingerprint());
            assertThat(baseline.metrics()).containsKeys(
                    "hitAtK",
                    "recallAtK",
                    "mrr",
                    "ndcgAtK",
                    "phraseRecall",
                    "sourceCoverage",
                    "averageLatencyMs",
                    "p95LatencyMs"
            );
            assertThat(baseline.metrics().get("sampleCount")).isEqualTo(2);
            assertThat((double) baseline.metrics().get("hitAtK")).isEqualTo(1.0d);
            assertThat((double) baseline.metrics().get("phraseRecall")).isEqualTo(1.0d);

            JsonNode manifest = objectMapper.readTree(baseline.runDirectory().resolve("manifest.json").toFile());
            assertThat(manifest.path("config").path("topK").intValue()).isEqualTo(2);
            assertThat(manifest.path("config").path("candidateK").intValue()).isEqualTo(3);
            assertThat(manifest.path("config").path("rrfK").intValue()).isEqualTo(60);
            assertThat(manifest.path("config").path("retrievalBackend").asText()).isEqualTo("in-memory-eval-fixture");

            JsonNode sample = objectMapper.readTree(Files.readAllLines(
                    baseline.runDirectory().resolve("samples.jsonl")
            ).get(0));
            assertThat(sample.path("retrievedContexts").get(0).has("sourceId")).isTrue();
            assertThat(sample.path("retrievedContexts").get(0).has("score")).isTrue();
            assertThat(sample.path("metadata").path("latencyMs").isNumber()).isTrue();
            assertThat(sample.path("metadata").path("candidateContexts").size()).isGreaterThan(1);
            JsonNode firstCandidateSignals = sample.path("metadata").path("candidateContexts").get(0).path("rankSignals");
            assertThat(firstCandidateSignals.path("candidateRank").intValue()).isEqualTo(1);
            assertThat(firstCandidateSignals.path("denseRank").isInt()).isTrue();
            assertThat(firstCandidateSignals.path("bm25Rank").isInt()).isTrue();
            assertThat(firstCandidateSignals.path("denseScore").isNumber()).isTrue();
            assertThat(firstCandidateSignals.path("bm25Score").isNumber()).isTrue();
            assertThat(firstCandidateSignals.path("fusedScore").isNumber()).isTrue();

            Path datasetManifestPath = baseline.runDirectory().resolve("manifests/datasets/unit-retrieval.json");
            Path splitManifestPath = baseline.runDirectory().resolve("manifests/splits/unit-retrieval.json");
            Path datasetPath = baseline.runDirectory().resolve("datasets/rag/unit-retrieval.jsonl");
            assertThat(datasetManifestPath).exists();
            assertThat(splitManifestPath).exists();
            assertThat(datasetPath).exists();
            JsonNode datasetManifest = objectMapper.readTree(datasetManifestPath.toFile());
            assertThat(datasetManifest.path("provenance").path("provider").asText()).isEqualTo("deterministic");
            assertThat(datasetManifest.path("sourceIds").get(0).asText()).isEqualTo("unit-retrieval");
            assertThat(datasetManifest.path("localPath").asText()).isEqualTo("datasets/rag/unit-retrieval.jsonl");
            JsonNode datasetRow = objectMapper.readTree(Files.readAllLines(datasetPath).get(0));
            assertThat(datasetRow.has("retrievedContexts")).isFalse();
            assertThat(datasetRow.path("metadata").path("candidateContexts").size()).isGreaterThan(1);
            assertThat(datasetRow.path("metadata").path("candidateContexts").get(0).path("rankSignals").has("denseRank")).isTrue();
            assertThat(datasetRow.path("metadata").path("candidateContexts").get(0).path("rankSignals").has("bm25Rank")).isTrue();
        }
    }

    @Test
    void cleanupRefusesNonEvalKnowledgeBasesAndDeletesOwnedChunks() {
        EvalOwnedKnowledgeBaseFixture fixture = fixture();

        assertThatThrownBy(() -> fixture.deleteByKnowledgeBaseId("business-kb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refusing non-eval");

        fixture.close();
        assertThat(fixture.chunkCount()).isZero();
    }

    @Test
    void fixtureRejectsMixedEmbeddingDimensions() {
        EvalOwnedKnowledgeBaseFixture badEmbedder = new EvalOwnedKnowledgeBaseFixture(ignored -> new float[]{1.0f}, 2);
        assertThatThrownBy(() -> badEmbedder.embed("query"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding dimension mismatch");

        EvalOwnedKnowledgeBaseFixture fixture = new EvalOwnedKnowledgeBaseFixture(ignored -> new float[]{1.0f, 0.0f}, 2);
        assertThatThrownBy(() -> fixture.createKnowledgeBase(KNOWLEDGE_BASE_ID, List.of(
                chunk("wrong-dimension", "Wrong dimension", "Wrong dimension text.", new float[]{1.0f})
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding dimension mismatch");

        EvalOwnedKnowledgeBaseFixture searchable = new EvalOwnedKnowledgeBaseFixture(ignored -> new float[]{1.0f, 0.0f}, 2);
        searchable.createKnowledgeBase(KNOWLEDGE_BASE_ID, List.of(
                chunk("doc-two-dimensional", "Two dimensional", "Two dimensional text.", new float[]{1.0f, 0.0f})
        ));
        assertThatThrownBy(() -> searchable.searchByKnowledgeBaseIds(List.of(KNOWLEDGE_BASE_ID), new float[]{1.0f}, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding dimension mismatch");
    }

    private EvalOwnedKnowledgeBaseFixture fixture() {
        EvalOwnedKnowledgeBaseFixture fixture = new EvalOwnedKnowledgeBaseFixture();
        fixture.createKnowledgeBase(KNOWLEDGE_BASE_ID, List.of(
                chunk("doc-a", "Alpha study", "Alpha evidence supports the treatment."),
                chunk("doc-b", "Beta study", "Beta evidence rejects the claim."),
                chunk("doc-c", "Gamma study", "Gamma evidence describes a different topic.")
        ));
        return fixture;
    }

    private List<RagRetrievalExportRunner.RetrievalCase> cases() {
        return List.of(
                new RagRetrievalExportRunner.RetrievalCase(
                        "sample-alpha",
                        "unit-retrieval",
                        "smoke",
                        "Which study supports the alpha treatment?",
                        List.of("doc-a"),
                        List.of("supports the treatment"),
                        Map.of("sourceType", "fixture-only", "sourceGroupId", "group-alpha")
                ),
                new RagRetrievalExportRunner.RetrievalCase(
                        "sample-beta",
                        "unit-retrieval",
                        "smoke",
                        "Which beta study rejects the claim?",
                        List.of("doc-b"),
                        List.of("rejects the claim"),
                        Map.of("sourceType", "fixture-only", "sourceGroupId", "group-beta")
                )
        );
    }

    private RagRetrievalExportRunner.RunRequest request(
            String runId,
            RagRetrievalExportRunner.RetrievalConfig config
    ) {
        return new RagRetrievalExportRunner.RunRequest(
                runId,
                "smoke",
                "test-branch",
                "test-sha",
                "unit-retrieval",
                "sha256:unit",
                List.of(KNOWLEDGE_BASE_ID),
                config,
                Map.of("retrievalBackend", "in-memory-eval-fixture", "sourceIds", List.of("unit-retrieval")),
                null
        );
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

    private KnowledgeBaseMilvusChunkDocument chunk(String documentId, String title, String text) {
        String searchable = title + " " + text;
        return chunk(documentId, title, text, EvalOwnedKnowledgeBaseFixture.embedText(searchable));
    }

    private KnowledgeBaseMilvusChunkDocument chunk(String documentId, String title, String text, float[] embedding) {
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
                embedding
        );
    }
}
