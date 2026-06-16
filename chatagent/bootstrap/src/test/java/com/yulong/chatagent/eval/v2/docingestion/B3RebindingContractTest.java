package com.yulong.chatagent.eval.v2.docingestion;

import com.yulong.chatagent.eval.v2.docingestion.ProductionDocumentIngestionEvalTest.BaselineDatasetRow;
import com.yulong.chatagent.eval.v2.docingestion.ProductionDocumentIngestionEvalTest.GroundedQuery;
import com.yulong.chatagent.eval.v2.docingestion.ProductionDocumentIngestionEvalTest.IngestedDocument;
import com.yulong.chatagent.eval.v2.docingestion.ProductionDocumentIngestionEvalTest.MineruTrace;
import com.yulong.chatagent.eval.v2.docingestion.ProductionDocumentIngestionEvalTest.MqTrace;
import com.yulong.chatagent.eval.v2.docingestion.ProductionDocumentIngestionEvalTest.SourceFile;
import com.yulong.chatagent.eval.v2.docingestion.ReferenceRebinder.RebindOutput;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher.RankedCandidateHit;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for B3.2 glue code that is easy to miss in pure rebinder tests.
 *
 * <p>These tests intentionally call package-private helpers in
 * {@link ProductionDocumentIngestionEvalTest}; they protect the accepted-size
 * replay contract without starting Spring or external infrastructure.</p>
 */
class B3RebindingContractTest {

    private static final Path PINNED_SOURCE_MANIFEST = Path.of(
            "..", "artifacts", "eval", "phase10a", "doc-ingestion-full-ee056c79", "source-manifest.json");
    private static final Path PINNED_BASELINE_DATASET = Path.of(
            "..", "artifacts", "eval", "phase10a", "doc-ingestion-full-ee056c79",
            "datasets", "doc-ingestion", "doc-ingestion-retrieval-v1.jsonl");

    @TempDir
    Path tempDir;

    @Nested
    class PinnedArtifacts {

        @Test
        void pinnedSourceManifestHashCountAndStableIdentitiesAreVerified() throws Exception {
            ProductionDocumentIngestionEvalTest.assertPinnedSourceManifest(PINNED_SOURCE_MANIFEST);

            Map<String, String> hashes = ProductionDocumentIngestionEvalTest.loadFrozenSourceHashes(PINNED_SOURCE_MANIFEST);

            assertThat(hashes).hasSize(200);
            assertThat(hashes).allSatisfy((identity, sha256) -> {
                assertThat(identity).contains("|");
                assertThat(sha256).isNotBlank();
            });
        }

        @Test
        void frozenArchiveSourceSeparatesDownloadUrlFromStableIdentity() {
            String sourceUrl = "https://example.com/materials.zip#Folder/Entry with spaces.docx";
            String archiveEntry = "Folder/Entry with spaces.docx";

            String downloadUrl = ProductionDocumentIngestionEvalTest.frozenArchiveDownloadUrl(sourceUrl, archiveEntry);

            assertThat(downloadUrl).isEqualTo("https://example.com/materials.zip");
            assertThat(downloadUrl + "#" + archiveEntry).isEqualTo(sourceUrl);
        }

        @Test
        void inconsistentFrozenArchiveIdentityFailsClosed() {
            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.frozenArchiveDownloadUrl(
                    "https://example.com/materials.zip#different.docx",
                    "Entry with spaces.docx"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("must end with its archiveEntry");
        }

        @Test
        void missingSourceManifestFailsClosed() {
            Path missing = tempDir.resolve("missing-source-manifest.json");

            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertPinnedSourceManifest(missing))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("requires frozen source manifest");
        }

        @Test
        void sourceManifestHashMismatchFailsClosed() throws Exception {
            Path manifest = tempDir.resolve("source-manifest.json");
            Files.writeString(manifest, "{\"sources\":[]}", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertPinnedSourceManifest(manifest))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("must match pinned Phase 10a bytes");
        }

        @Test
        void pinnedBaselineDatasetHashCountAndSingleReferencesAreVerified() throws Exception {
            ProductionDocumentIngestionEvalTest.assertPinnedBaselineDataset(PINNED_BASELINE_DATASET);

            List<BaselineDatasetRow> rows = ProductionDocumentIngestionEvalTest.loadBaselineDatasetRows(PINNED_BASELINE_DATASET);

            assertThat(rows).hasSize(585);
            assertThat(rows).allSatisfy(row -> assertThat(row.referenceContextCount()).isEqualTo(1));
        }

        @Test
        void baselineDatasetHashMismatchFailsClosed() throws Exception {
            Path dataset = tempDir.resolve("doc-ingestion-retrieval-v1.jsonl");
            Files.writeString(dataset, jsonlRow("s1", "question", "old-chunk-1"), StandardCharsets.UTF_8);

            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertPinnedBaselineDataset(dataset))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("must match pinned Phase 10a bytes");
        }
    }

    @Nested
    class BaselineRows {

        @Test
        void baselineLoaderPreservesExportIdentityButSelectorInputStaysNarrow() throws Exception {
            Path dataset = tempDir.resolve("baseline.jsonl");
            Files.writeString(dataset,
                    jsonlRow("sample-1", "original user query", "old-chunk-1")
                            + System.lineSeparator()
                            + "   "
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);

            List<BaselineDatasetRow> rows = ProductionDocumentIngestionEvalTest.loadBaselineDatasetRows(dataset);

            assertThat(rows).hasSize(1);
            BaselineDatasetRow row = rows.get(0);
            assertThat(row.sampleId()).isEqualTo("sample-1");
            assertThat(row.sourceGroupId()).isEqualTo("old-source-group");
            assertThat(row.split()).isEqualTo("development");
            assertThat(row.fileId()).isEqualTo("old-file-id");
            assertThat(row.userInput()).isEqualTo("original user query");
            assertThat(row.referenceContextCount()).isEqualTo(1);

            assertThat(row.toRebindInput().getClass().getRecordComponents()).hasSize(8);
            assertThat(row.toRebindInput().referenceContent()).isEqualTo("reference evidence text");
        }
    }

    @Nested
    class RebindExportWiring {

        @Test
        void reboundQueryUsesBaselineUserInputAndPreservesExportIdentity() {
            BaselineDatasetRow row = baselineRow("sample-1", "original user query");
            RebindOutput output = boundOutput(row, "new-chunk-42");

            List<GroundedQuery> queries = ProductionDocumentIngestionEvalTest.buildReboundQueries(
                    List.of(row), List.of(output));

            assertThat(queries).hasSize(1);
            GroundedQuery query = queries.get(0);
            assertThat(query.queryText()).isEqualTo("original user query");
            assertThat(query.queryText()).isNotEqualTo(row.referenceContent());
            assertThat(query.referenceChunkId()).isEqualTo("new-chunk-42");
            assertThat(query.sampleId()).isEqualTo("sample-1");
            assertThat(query.sourceGroupId()).isEqualTo("old-source-group");
            assertThat(query.fileId()).isEqualTo("old-file-id");
            assertThat(query.split()).isEqualTo("development");
            assertThat(query.sourceUrl()).isEqualTo("https://example.com/doc.html");
            assertThat(query.sourceSha256()).isEqualTo("abc123");
        }

        @Test
        void missingRebindOutputFailsBeforeExport() {
            BaselineDatasetRow row = baselineRow("sample-1", "original user query");

            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.buildReboundQueries(List.of(row), List.of()))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Every baseline row must have a rebind output");
        }

        @Test
        void unboundRebindOutputFailsBeforeExport() {
            BaselineDatasetRow row = baselineRow("sample-1", "original user query");
            RebindOutput output = new RebindOutput(
                    "sample-1",
                    row.oldReferenceChunkId(),
                    null,
                    List.of(),
                    0,
                    "missing",
                    ReferenceRebinder.sourceIdentity(row.toRebindInput()),
                    "none",
                    0.0,
                    "none");

            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.buildReboundQueries(List.of(row), List.of(output)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Rebind must be bound");
        }
    }

    @Nested
    class ReceiptContract {

        @Test
        void receiptIsStableSortedAndContainsNoRunLocalFieldsOrErrorHashes() {
            BaselineDatasetRow first = baselineRow("sample-a", "first query");
            BaselineDatasetRow second = baselineRow("sample-b", "second query");

            Map<String, Object> receipt = ProductionDocumentIngestionEvalTest.buildRebindReceipt(
                    List.of(boundOutput(second, "chunk-b"), boundOutput(first, "chunk-a")),
                    List.of(second, first),
                    "sha256:baseline",
                    "sha256:sources",
                    "sha256:inventory");
            Map<String, Object> repeated = ProductionDocumentIngestionEvalTest.buildRebindReceipt(
                    List.of(boundOutput(second, "chunk-b"), boundOutput(first, "chunk-a")),
                    List.of(second, first),
                    "sha256:baseline",
                    "sha256:sources",
                    "sha256:inventory");

            assertThat(receipt).isEqualTo(repeated);
            assertThat(receipt).doesNotContainKeys("runId", "timestamp");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) receipt.get("rows");
            assertThat(rows).extracting(row -> row.get("sampleId"))
                    .containsExactly("sample-a", "sample-b");
            assertThat(rows).allSatisfy(row ->
                    assertThat(row.get("oldContentHash").toString()).startsWith("sha256:").doesNotContain("error"));
        }

        @Test
        void exportManifestBindsDatasetSourceInventoryEvidenceAndReceiptBytes() throws Exception {
            Path dataset = tempDir.resolve(Path.of(
                    "datasets", "doc-ingestion", "doc-ingestion-retrieval-v1.jsonl"));
            Files.createDirectories(dataset.getParent());
            Files.writeString(dataset, "dataset\n");
            Path sourceManifest = tempDir.resolve("source-manifest.json");
            Path inventory = tempDir.resolve("chunk-inventory.json");
            Path evidence = tempDir.resolve("chunk-evidence.json");
            Path receipt = tempDir.resolve("reference-rebind-receipt.json");
            Files.writeString(sourceManifest, "sources\n");
            Files.writeString(inventory, "inventory\n");
            Files.writeString(evidence, "evidence\n");
            Files.writeString(receipt, "receipt\n");

            new ProductionDocumentIngestionEvalTest()
                    .writeB3ExportManifest(receipt, inventory, evidence, tempDir);

            String manifest = Files.readString(tempDir.resolve("b3-export-manifest.json"));
            assertThat(manifest)
                    .contains("\"datasetHash\"")
                    .contains("\"sourceManifestHash\"")
                    .contains("\"chunkInventoryHash\"")
                    .contains("\"chunkEvidenceHash\"")
                    .contains("\"rebindReceiptHash\"");
        }
    }

    @Nested
    class EvidenceExportGate {

        @Test
        void acceptsHtmlParserChunkerRouteWithNonEmptyChunks() {
            ProductionDocumentIngestionEvalTest.assertB3EvidenceExportGate(List.of(
                    ingestedHtmlDocument(List.of(KnowledgeChunkDTO.builder()
                            .id("chunk-1")
                            .chunkIndex(0)
                            .content("# Heading\n\nEvidence")
                            .build()))));
        }

        @Test
        void rejectsCompletedHtmlDocumentWithZeroChunks() {
            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertB3EvidenceExportGate(
                    List.of(ingestedHtmlDocument(List.of()))))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("produce at least one chunk");
        }

        @Test
        void rejectsRunWithoutHtmlBackedDocuments() {
            SourceFile source = new SourceFile(
                    "https://example.com/doc.pdf", null, "doc.pdf", "application/pdf",
                    "group", "PDF", "Public test fixture", "development", "abc123");
            IngestedDocument document = new IngestedDocument(
                    source, "doc-1", "abc123", 1, "now", List.of(), emptyMqTrace(), emptyMineruTrace(), true);

            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertB3EvidenceExportGate(List.of(document)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("must include HTML-backed documents");
        }

        @Test
        void fullChunkEvidenceIsDeterministicExactAndContainsNoRetrievalFeedback() throws Exception {
            KnowledgeChunkDTO chunk = KnowledgeChunkDTO.builder()
                    .id("chunk-1")
                    .chunkIndex(0)
                    .content("# Heading\n\nDistinct evidence")
                    .build();
            IngestedDocument document = ingestedHtmlDocument(List.of(chunk));
            List<ReferenceRebinder.NewChunk> inventory = List.of(new ReferenceRebinder.NewChunk(
                    document.source().sourceUrl(),
                    document.sha256(),
                    document.source().filename(),
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    chunk.getContent()));
            Path first = tempDir.resolve("chunk-evidence-1.json");
            Path second = tempDir.resolve("chunk-evidence-2.json");
            ProductionDocumentIngestionEvalTest runner = new ProductionDocumentIngestionEvalTest();

            runner.writeChunkEvidence(inventory, List.of(document), first);
            runner.writeChunkEvidence(inventory, List.of(document), second);

            String content = Files.readString(first, StandardCharsets.UTF_8);
            assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
            assertThat(content)
                    .contains("\"content\" : \"# Heading\\n\\nDistinct evidence\"")
                    .contains("\"contentHash\" : \"sha256:")
                    .doesNotContain("query", "retrieval", "rank", "score", "ragas");
        }
    }

    @Nested
    class HybridCandidateGate {

        @Test
        void acceptsCandidateListWithDenseAndBm25Signals() {
            ProductionDocumentIngestionEvalTest.assertAcceptedHybridCandidateCoverage(List.of(List.of(
                    rankedCandidate("dense-only", 1, null),
                    rankedCandidate("hybrid", 2, 1))));
        }

        @Test
        void rejectsDenseOnlyCandidateList() {
            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertAcceptedHybridCandidateCoverage(
                    List.of(List.of(rankedCandidate("dense-only", 1, null)))))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("BM25");
        }
    }

    @Nested
    class OracleGate {

        @Test
        void oracleGateAcceptsHtmlThresholdsAndNonHtmlRegressionLimit() {
            ProductionDocumentIngestionEvalTest.assertCleanQuestionOracleRecallGate(Map.of(
                    "SEC_HTML", 0.80,
                    "WEB_MD", 0.85,
                    "PDF", 0.916,
                    "DOCX", 0.874,
                    "XLSX", 0.761
            ));
        }

        @Test
        void oracleGateRejectsSecHtmlBelowThreshold() {
            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertCleanQuestionOracleRecallGate(Map.of(
                    "SEC_HTML", 0.79,
                    "WEB_MD", 0.90,
                    "PDF", 0.95,
                    "DOCX", 0.90,
                    "XLSX", 0.80
            )))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("SEC_HTML oracle recall");
        }

        @Test
        void oracleGateRejectsNonHtmlRegressionBeyondTolerance() {
            assertThatThrownBy(() -> ProductionDocumentIngestionEvalTest.assertCleanQuestionOracleRecallGate(Map.of(
                    "SEC_HTML", 0.90,
                    "WEB_MD", 0.90,
                    "PDF", 0.91,
                    "DOCX", 0.90,
                    "XLSX", 0.80
            )))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("PDF oracle recall");
        }
    }

    private static BaselineDatasetRow baselineRow(String sampleId, String userInput) {
        return new BaselineDatasetRow(
                sampleId,
                "old-source-group",
                "development",
                "old-file-id",
                "SEC_HTML",
                "https://example.com/doc.html",
                userInput,
                "abc123",
                "doc.html",
                "sec-edgar",
                "Public test fixture",
                "direct-evidence-preflight",
                "old-chunk-1",
                1,
                "reference evidence text",
                null);
    }

    private static RebindOutput boundOutput(BaselineDatasetRow row, String newChunkId) {
        return new RebindOutput(
                row.sampleId(),
                row.oldReferenceChunkId(),
                newChunkId,
                List.of(newChunkId),
                1,
                "bound",
                ReferenceRebinder.sourceIdentity(row.toRebindInput()),
                "exact",
                1.0,
                "none");
    }

    private static IngestedDocument ingestedHtmlDocument(List<KnowledgeChunkDTO> chunks) {
        SourceFile source = new SourceFile(
                "https://example.com/doc.html", null, "doc.html", "text/html",
                "group", "SEC_HTML", "Public test fixture", "development", "abc123");
        return new IngestedDocument(
                source, "doc-1", "abc123", 1, "now", chunks, emptyMqTrace(), emptyMineruTrace(), true);
    }

    private static MqTrace emptyMqTrace() {
        return new MqTrace(null, null, null, false, null, null);
    }

    private static MineruTrace emptyMineruTrace() {
        return new MineruTrace(false, false, null, 0, List.of());
    }

    private static RankedCandidateHit rankedCandidate(String chunkId, Integer denseRank, Integer bm25Rank) {
        MilvusSearchHit hit = MilvusSearchHit.builder()
                .chunkId(chunkId)
                .chunkIndex(0)
                .content("candidate content")
                .score(0.5)
                .build();
        return new RankedCandidateHit(
                hit,
                denseRank,
                bm25Rank,
                denseRank == null ? null : 0.5,
                bm25Rank == null ? null : 0.5,
                0.5);
    }

    private static String jsonlRow(String sampleId, String userInput, String oldChunkId) {
        return """
                {"sampleId":"%s","datasetId":"doc-ingestion-retrieval-v1","sourceGroupId":"old-source-group","split":"development","fileId":"old-file-id","fileFormat":"SEC_HTML","sourceUrl":"https://example.com/doc.html","userInput":"%s","referenceContextIds":["%s"],"metadata":{"sourceSha256":"abc123","referenceDocFilename":"doc.html","sourceGroup":"sec-edgar","license":"Public test fixture","generationMethod":"direct-evidence-preflight","referenceContent":"reference evidence text"}}
                """.formatted(sampleId, userInput, oldChunkId).strip();
    }
}
