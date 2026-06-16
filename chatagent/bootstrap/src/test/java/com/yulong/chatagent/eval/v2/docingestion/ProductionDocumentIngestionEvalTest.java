package com.yulong.chatagent.eval.v2.docingestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.knowledge.application.KnowledgeDocumentFacadeService;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.mq.outbox.OutboxPollingPublisher;
import com.yulong.chatagent.mq.outbox.OutboxRepository;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher.RankedCandidateHit;
import com.yulong.chatagent.rag.retrieve.RerankerProperties;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production document-ingestion real-retrieval evaluation.
 *
 * <p>The accepted-size path downloads 200+ real public documents across SEC HTML,
 * PDF, DOCX, XLSX, and web/Markdown families, uploads them through the production
 * administrator facade, publishes ingestion through outbox/RabbitMQ, lets
 * {@code KnowledgeIngestTaskListener} call the production ingestion service, verifies
 * chunks/Milvus, generates grounded retrieval rows, and exports a Phase 3-compatible
 * dataset root for {@code tune-suite --suite doc-ingestion-retrieval}.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "chatagent.rag.ingestion.contextual-enricher.enabled=false",
                "chatagent.rag.ingestion.document-enhancer.enabled=false",
                "chatagent.mq.dispatchers.agent-run-enabled=false",
                "rag.retrieval.top-k=${chatagent.eval.docIngestion.topK:8}",
                "rag.retrieval.candidate-k=${chatagent.eval.docIngestion.candidateK:24}",
                "rag.retrieval.rrf-k=${chatagent.eval.docIngestion.rrfK:60}",
                "rag.retrieval.reranker.max-candidates=${chatagent.eval.docIngestion.rerankerMaxCandidates:${chatagent.eval.docIngestion.candidateK:24}}"
        }
)
@ActiveProfiles("eval-real-doc-ingestion")
@Tag("eval-v2")
@Tag("eval-real")
@ExtendWith(ProductionDocumentIngestionInfrastructureCondition.class)
class ProductionDocumentIngestionEvalTest {

    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String DATASET_ID = "doc-ingestion-retrieval-v1";
    private static final int TOP_K = Integer.getInteger("chatagent.eval.docIngestion.topK", 8);
    private static final int CANDIDATE_K = Integer.getInteger("chatagent.eval.docIngestion.candidateK", 24);
    private static final int RRF_K = Integer.getInteger("chatagent.eval.docIngestion.rrfK", 60);
    private static final int RERANKER_MAX_CANDIDATES = Integer.getInteger(
            "chatagent.eval.docIngestion.rerankerMaxCandidates", CANDIDATE_K);
    private static final Duration DOCUMENT_TIMEOUT = Duration.ofSeconds(
            Long.getLong("chatagent.eval.docIngestion.documentTimeoutSeconds", 300L));
    private static final Pattern HREF_PATTERN = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_MANUAL_QUESTION_SET =
            "/eval/v2/datasets/doc-ingestion/manual-smoke-questions-v1.json";
    private static final String DEFAULT_ACCEPTED_QUESTION_SET =
            "/eval/v2/datasets/doc-ingestion/manual-accepted-questions-v1.json";
    private static final String DEFAULT_ACCEPTED_QUESTION_MANIFEST =
            "/eval/v2/datasets/doc-ingestion/manual-accepted-questions-v1.manifest.json";
    private static final Path FROZEN_SOURCE_MANIFEST_PATH = Path.of(
            "..", "artifacts", "eval", "phase10a", "doc-ingestion-full-ee056c79", "source-manifest.json");
    private static final String FROZEN_SOURCE_MANIFEST_HASH =
            "sha256:f190f7af6eb36959954937afef3540566a1e20d46d9f2e8f548fa48c22a29238";
    private static final Path BASELINE_DATASET_PATH = Path.of(
            "..", "artifacts", "eval", "phase10a", "doc-ingestion-full-ee056c79",
            "datasets", "doc-ingestion", "doc-ingestion-retrieval-v1.jsonl");
    private static final String BASELINE_DATASET_HASH =
            "sha256:cf63e75c1d242211771e4634640354287c762fe95d387a1a0a358cd0ddda4da5";
    private static final int FROZEN_SOURCE_COUNT = 200;
    private static final int BASELINE_DATASET_ROW_COUNT = 585;
    private static final Map<String, Double> BASELINE_ORACLE_RECALL_BY_FORMAT = Map.of(
            "PDF", 0.946,
            "DOCX", 0.904,
            "XLSX", 0.791
    );
    private static final double NON_HTML_ORACLE_REGRESSION_TOLERANCE = 0.03;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, Integer> ACCEPTED_FORMAT_TARGETS = Map.of(
            "SEC_HTML", 50,
            "PDF", 20,
            "DOCX", 40,
            "XLSX", 45,
            "WEB_MD", 45
    );

    private static final List<SourceFile> SMOKE_CATALOG = List.of(
            direct("https://www.rfc-editor.org/rfc/rfc7540.txt", "rfc7540-http2.txt", "text/plain",
                    "ietf-rfc", "WEB_MD", "Trust Legal Provisions - freely distributable (IETF, 2015)", "calibration"),
            direct("https://www.rfc-editor.org/rfc/rfc9110.txt", "rfc9110-http-semantics.txt", "text/plain",
                    "ietf-rfc", "WEB_MD", "Trust Legal Provisions - freely distributable (IETF, 2022)", "development"),
            direct("https://www.irs.gov/pub/irs-pdf/p1.pdf", "irs-publication-1-taxpayer-rights.pdf", "application/pdf",
                    "irs-publications", "PDF", "Public domain (U.S. Internal Revenue Service)", "calibration"),
            direct("https://www.irs.gov/pub/irs-pdf/p501.pdf", "irs-publication-501-dependents.pdf", "application/pdf",
                    "irs-publications", "PDF", "Public domain (U.S. Internal Revenue Service)", "development"),
            direct("https://www.w3.org/TR/WCAG22/", "wcag-22.html", "text/html",
                    "w3c", "WEB_MD", "W3C Software and Document License", "calibration"),
            direct("https://www.w3.org/TR/png-3/", "png-third-edition.html", "text/html",
                    "w3c", "WEB_MD", "W3C Software and Document License", "holdout"),
            direct("https://raw.githubusercontent.com/asyncapi/spec/v2.6.0/spec/asyncapi.md", "asyncapi-spec.md", "text/markdown",
                    "asyncapi", "WEB_MD", "CC-BY-4.0 (AsyncAPI Initiative)", "calibration"),
            direct("https://raw.githubusercontent.com/OAI/OpenAPI-Specification/main/README.md", "openapi-readme.md", "text/markdown",
                    "oai", "WEB_MD", "Apache-2.0 (OpenAPI Initiative)", "holdout"),
            direct("https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/expanded-tables/wfl-girls-zscore-expanded-table.xlsx",
                    "wfl-girls-zscore-expanded-table.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "who-child-growth", "XLSX", "CC-BY-3.0-IGO (WHO Child Growth Standards)", "calibration"),
            direct("https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/expanded-tables/wfl-boys-zscore-expanded-table.xlsx",
                    "wfl-boys-zscore-expanded-table.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "who-child-growth", "XLSX", "CC-BY-3.0-IGO (WHO Child Growth Standards)", "development"),
            direct("https://www.cms.gov/files/document/determination-lis-ineligibility.docx",
                    "determination-lis-ineligibility.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "cms-model-materials", "DOCX", "Public CMS model material", "holdout")
    );

    @Autowired
    private KnowledgeDocumentFacadeService knowledgeDocumentFacadeService;

    @Autowired
    private OutboxPollingPublisher outboxPollingPublisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private DocumentStorageService documentStorageService;

    @Autowired
    private KnowledgeBaseSimilaritySearcher kbSearcher;

    @Autowired
    private RerankerProperties rerankerProperties;

    @Autowired
    private KnowledgeBaseMilvusIndexer milvusIndexer;

    @Autowired
    private UserRepository userRepository;

    private final List<String> evalKbIds = new ArrayList<>();
    private final List<String> evalDocIds = new ArrayList<>();
    private final List<String> evalStoragePaths = new ArrayList<>();
    private String evalUserId;
    private Map<String, String> frozenSourceHashes = Map.of();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final ObjectMapper jsonlMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @BeforeEach
    void createEvalKnowledgeBase() {
        String kbId = UUID.randomUUID().toString();
        KnowledgeBaseDTO kb = KnowledgeBaseDTO.builder()
                .id(kbId)
                .createdBy(evalUserId())
                .name("Eval Doc-Ingestion " + RUN_ID)
                .description("Phase 10a production document ingestion full-chain eval")
                .visibility("private")
                .status("ACTIVE")
                .metadata("{}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        knowledgeBaseRepository.save(kb);
        evalKbIds.add(kbId);
    }

    @AfterEach
    void cleanupEvalResources() {
        UserContext.clear();
        for (String docId : evalDocIds) {
            try {
                milvusIndexer.deleteByKnowledgeDocumentId(docId);
            } catch (Exception ignored) {
            }
            try {
                knowledgeChunkRepository.deleteByKnowledgeDocumentId(docId);
            } catch (Exception ignored) {
            }
        }
        for (String storagePath : evalStoragePaths) {
            try {
                documentStorageService.deleteFile(storagePath);
            } catch (Exception ignored) {
            }
        }
        for (String docId : evalDocIds) {
            try {
                knowledgeDocumentRepository.deleteById(docId);
            } catch (Exception ignored) {
            }
        }
        for (String kbId : evalKbIds) {
            try {
                knowledgeBaseRepository.deleteById(kbId);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void productionDocumentIngestionMqMineruRetrievalExport() throws Exception {
        boolean acceptedSize = acceptedSizeRun();
        String kbId = evalKbIds.get(0);
        Path artifactsDir = outputRoot().resolve((acceptedSize ? "doc-ingestion-full-" : "doc-ingestion-smoke-") + RUN_ID);
        Files.createDirectories(artifactsDir);

        List<SourceFile> catalog = acceptedSize ? buildAcceptedCatalog() : SMOKE_CATALOG;
        if (acceptedSize) {
            frozenSourceHashes = loadFrozenSourceHashes();
        }
        List<IngestedDocument> ingestedDocs = new ArrayList<>();
        List<DocumentFailure> failures = new ArrayList<>();
        Map<String, byte[]> archiveCache = new HashMap<>();
        Map<String, Integer> successByFormat = new LinkedHashMap<>();
        Map<String, Integer> targets = acceptedSize ? ACCEPTED_FORMAT_TARGETS : smokeTargets();

        for (SourceFile source : catalog) {
            if (acceptedSize && successByFormat.getOrDefault(source.format(), 0) >= targets.getOrDefault(source.format(), 0)) {
                continue;
            }
            try {
                IngestedDocument ingested = ingestThroughFacadeOutbox(kbId, source, archiveCache);
                ingestedDocs.add(ingested);
                successByFormat.merge(source.format(), 1, Integer::sum);
                if (acceptedSize && targetsSatisfied(successByFormat, targets)) {
                    break;
                }
            } catch (Exception e) {
                failures.add(new DocumentFailure(source, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        if (acceptedSize) {
            assertAcceptedSourceCoverage(successByFormat, targets, failures);
            assertAcceptedMineruCoverage(ingestedDocs);
            assertB3EvidenceExportGate(ingestedDocs);

            if (b3BaselineReplayRun()) {
                runB3BaselineReplay(ingestedDocs, failures, kbId, artifactsDir);
            } else {
                runB3CleanQuestionEvaluation(ingestedDocs, failures, kbId, artifactsDir);
            }
        } else {
            assertThat(ingestedDocs).hasSizeGreaterThanOrEqualTo(5);

            ManualQuestionSet manualQuestionSet = loadManualQuestionSet();
            List<GroundedQuery> allQueries = bindManualQuestions(ingestedDocs, manualQuestionSet);
            assertManualQuestionsSafe(allQueries);
            assertThat(allQueries).hasSize(manualQuestionSet.questions().size());

            List<QueryResult> results = performRetrieval(allQueries, kbId);
            double hitAtK = computeFinalHitAtK(results, TOP_K);
            double contextRecallAtK = computeFinalContextRecallAtK(results, TOP_K);
            double mrr = computeFinalMrr(results);

            exportDatasetRoot(results, ingestedDocs, failures, kbId, artifactsDir);

            assertThat(hitAtK)
                    .as("post-rerank hit@K should be > 0; a real retrieval export with all misses is not useful")
                    .isGreaterThan(0.0);

            System.out.printf("[%s] Phase 10a smoke complete: files=%d, queries=%d, hit@K=%.3f, recall@K=%.3f, MRR=%.3f%n",
                    RUN_ID, ingestedDocs.size(), allQueries.size(), hitAtK, contextRecallAtK, mrr);
            System.out.printf("[%s] Artifacts at: %s%n", RUN_ID, artifactsDir);
        }
    }

    private void runB3BaselineReplay(List<IngestedDocument> ingestedDocs,
                                     List<DocumentFailure> failures,
                                     String kbId,
                                     Path artifactsDir) throws Exception {
        Path baselineDatasetPath = baselineDatasetPath();
        assertPinnedBaselineDataset(baselineDatasetPath);

        List<ReferenceRebinder.NewChunk> chunkInventory = buildChunkInventory(ingestedDocs);
        Path inventoryPath = artifactsDir.resolve("chunk-inventory.json");
        writeChunkInventory(chunkInventory, ingestedDocs, inventoryPath);
        Path chunkEvidencePath = artifactsDir.resolve("chunk-evidence.json");
        writeChunkEvidence(chunkInventory, ingestedDocs, chunkEvidencePath);

        List<BaselineDatasetRow> baselineRows = loadBaselineDatasetRows(baselineDatasetPath);
        List<ReferenceRebinder.RebindOutput> rebindResults = ReferenceRebinder.rebind(
                baselineRows.stream().map(BaselineDatasetRow::toRebindInput).toList(),
                chunkInventory);
        Path receiptPath = artifactsDir.resolve("reference-rebind-receipt.json");
        writeRebindReceipt(rebindResults, baselineRows, receiptPath, baselineDatasetPath, inventoryPath);
        assertRebindGate(rebindResults);

        System.out.printf("[%s] B3.2 rebind: %d/%d bound, %d missing%n",
                RUN_ID,
                rebindResults.stream().filter(r -> "bound".equals(r.status())).count(),
                rebindResults.size(),
                rebindResults.stream().filter(r -> "missing".equals(r.status())).count());

        List<GroundedQuery> allQueries = buildReboundQueries(baselineRows, rebindResults);
        assertThat(allQueries).as("B3.2 must produce one query per baseline row").hasSize(baselineRows.size());

        List<QueryResult> results = performRetrieval(allQueries, kbId);
        assertAcceptedHybridCandidateCoverage(results.stream().map(QueryResult::candidates).toList());
        double hitAtK = computeFinalHitAtK(results, TOP_K);
        double contextRecallAtK = computeFinalContextRecallAtK(results, TOP_K);
        double mrr = computeFinalMrr(results);

        exportDatasetRoot(results, ingestedDocs, failures, kbId, artifactsDir);
        writeB3ExportManifest(receiptPath, inventoryPath, chunkEvidencePath, artifactsDir);

        assertThat(hitAtK)
                .as("post-rerank hit@K should be > 0; a real retrieval export with all misses is not useful")
                .isGreaterThan(0.0);
        System.out.printf("[%s] B3.2 legacy-query oracle diagnostics: %s%n", RUN_ID, oracleRecallByFormat(results));
        System.out.printf("[%s] B3.2 accepted-size complete: files=%d, chunks=%d, queries=%d, topK=%d, hit@K=%.3f, recall@K=%.3f, MRR=%.3f%n",
                RUN_ID, ingestedDocs.size(), ingestedDocs.stream().mapToInt(d -> d.chunks.size()).sum(),
                allQueries.size(), TOP_K, hitAtK, contextRecallAtK, mrr);
        System.out.printf("[%s] Artifacts at: %s%n", RUN_ID, artifactsDir);
    }

    private void runB3CleanQuestionEvaluation(List<IngestedDocument> ingestedDocs,
                                              List<DocumentFailure> failures,
                                              String kbId,
                                              Path artifactsDir) throws Exception {
        AcceptedQuestionRuntime accepted = loadAcceptedQuestionSet();
        List<GroundedQuery> allQueries = bindManualQuestions(ingestedDocs, accepted.questionSet());
        assertManualQuestionsSafe(allQueries);
        assertAcceptedQueryCoverage(allQueries);
        validateBoundOverlayReceipt(accepted.validation(), allQueries);
        writeAcceptedQuestionRuntimeManifest(accepted, artifactsDir);

        List<QueryResult> results = performRetrieval(allQueries, kbId);
        assertAcceptedHybridCandidateCoverage(results.stream().map(QueryResult::candidates).toList());
        double hitAtK = computeFinalHitAtK(results, TOP_K);
        double contextRecallAtK = computeFinalContextRecallAtK(results, TOP_K);
        double mrr = computeFinalMrr(results);
        exportDatasetRoot(results, ingestedDocs, failures, kbId, artifactsDir);

        assertThat(hitAtK)
                .as("post-rerank hit@K should be > 0; a real retrieval export with all misses is not useful")
                .isGreaterThan(0.0);
        Map<String, Double> oracleRecall = oracleRecallByFormat(results);
        assertCleanQuestionOracleRecallGate(oracleRecall);
        System.out.printf("[%s] B3.3 clean-question accepted-size complete: files=%d, queries=%d, hit@K=%.3f, recall@K=%.3f, MRR=%.3f, oracle=%s%n",
                RUN_ID, ingestedDocs.size(), allQueries.size(), hitAtK, contextRecallAtK, mrr, oracleRecall);
        System.out.printf("[%s] Artifacts at: %s%n", RUN_ID, artifactsDir);
    }

    private IngestedDocument ingestThroughFacadeOutbox(String kbId,
                                                       SourceFile source,
                                                       Map<String, byte[]> archiveCache) throws Exception {
        DownloadedFile downloaded = downloadSource(source, archiveCache);
        String sha256 = sha256Hex(downloaded.bytes());

        // B3.2: Verify SHA-256 against frozen manifest when available.
        if (acceptedSizeRun()) {
            assertThat(source.expectedSha256())
                    .as("Frozen source manifest must pin SHA-256 for %s", source.filename())
                    .isNotBlank();
            String identity = ReferenceRebinder.sourceIdentity(source.sourceUrl(), source.expectedSha256(), source.filename());
            assertThat(frozenSourceHashes)
                    .as("Frozen source hash map must contain stable identity %s", identity)
                    .containsEntry(identity, source.expectedSha256());
            assertThat(sha256)
                    .as("Frozen source SHA-256 mismatch for %s — source bytes changed since baseline", source.filename())
                    .isEqualTo(source.expectedSha256());
        }

        UserContext.set(LoginUser.builder()
                .userId(evalUserId())
                .username("phase10a-eval")
                .role("admin")
                .status("ACTIVE")
                .build());
        UploadKnowledgeDocumentResponse response = knowledgeDocumentFacadeService.uploadKnowledgeDocument(
                kbId,
                new MockMultipartFile("file", source.filename(), source.mimeType(), downloaded.bytes())
        );
        UserContext.clear();

        String docId = response.getDocumentId();
        evalDocIds.add(docId);
        KnowledgeDocumentDTO created = knowledgeDocumentRepository.findById(docId);
        assertThat(created).as("Uploaded document should exist: %s", source.filename()).isNotNull();
        if (StringUtils.hasText(created.getStoragePath())) {
            evalStoragePaths.add(created.getStoragePath());
        }

        String idempotencyKey = docId + ":" + sha256;
        MqTrace mqTrace = waitForMqCompletion(docId, idempotencyKey);
        KnowledgeDocumentDTO updated = knowledgeDocumentRepository.findById(docId);
        assertThat(updated.getParseStatus())
                .as("Document %s (%s) should complete MQ-backed ingestion", docId, source.filename())
                .isEqualTo("COMPLETED");

        List<KnowledgeChunkDTO> chunks = knowledgeChunkRepository.findByKnowledgeDocumentId(docId);
        assertThat(chunks)
                .as("Document %s (%s) should produce chunks", docId, source.filename())
                .isNotEmpty();
        boolean milvusEvidence = hasMilvusEvidence(kbId, chunks);

        MineruTrace mineru = mineruTrace(source, chunks);
        return new IngestedDocument(source, docId, sha256, downloaded.bytes().length, downloaded.downloadedAt(), chunks, mqTrace, mineru, milvusEvidence);
    }

    private MqTrace waitForMqCompletion(String docId, String idempotencyKey) throws Exception {
        Instant deadline = Instant.now().plus(DOCUMENT_TIMEOUT);
        MqOutbox observed = null;
        String parseStatus = "PENDING";
        while (Instant.now().isBefore(deadline)) {
            outboxPollingPublisher.publishDueRows();
            List<MqOutbox> rows = outboxRepository.findRecent(null, idempotencyKey, null, 5);
            if (!rows.isEmpty()) {
                observed = rows.get(0);
            }
            KnowledgeDocumentDTO document = knowledgeDocumentRepository.findById(docId);
            if (document != null && StringUtils.hasText(document.getParseStatus())) {
                parseStatus = document.getParseStatus();
            }
            if ("COMPLETED".equalsIgnoreCase(parseStatus)) {
                return new MqTrace(
                        observed == null ? null : observed.getId(),
                        idempotencyKey,
                        observed == null ? null : observed.getStatus(),
                        true,
                        parseStatus,
                        observed == null ? null : observed.getCreatedAt()
                );
            }
            if ("FAILED".equalsIgnoreCase(parseStatus) || "REJECTED".equalsIgnoreCase(parseStatus)) {
                throw new IllegalStateException("MQ ingestion ended with parseStatus=" + parseStatus + ", docId=" + docId);
            }
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Timed out waiting for MQ ingestion completion: docId=" + docId + ", lastStatus=" + parseStatus);
    }

    private boolean hasMilvusEvidence(String kbId, List<KnowledgeChunkDTO> chunks) {
        List<String> insertedChunkIds = chunks.stream()
                .map(KnowledgeChunkDTO::getId)
                .toList();
        String documentId = chunks.isEmpty() ? null : chunks.get(0).getKnowledgeDocumentId();
        List<KnowledgeChunkDTO> probes = chunks.stream()
                .filter(chunk -> StringUtils.hasText(chunk.getContent()) && chunk.getContent().length() >= 20)
                .limit(5)
                .toList();
        if (probes.isEmpty() && !chunks.isEmpty()) {
            probes = List.of(chunks.get(0));
        }
        for (KnowledgeChunkDTO probe : probes) {
            List<RankedCandidateHit> candidates = kbSearcher.searchRankedCandidateHitsByKnowledgeBaseIds(
                    List.of(kbId), extractFirstSentence(probe.getContent()));
            boolean found = candidates.stream().anyMatch(candidate ->
                    insertedChunkIds.contains(candidate.hit().chunkId())
                            || documentId.equals(candidate.hit().documentId()));
            if (found) {
                return true;
            }
        }
        return false;
    }

    private ManualQuestionSet loadManualQuestionSet() throws IOException {
        String resourcePath = System.getProperty(
                "chatagent.eval.docIngestion.manualQuestionSet",
                DEFAULT_MANUAL_QUESTION_SET
        );
        try (InputStream input = ProductionDocumentIngestionEvalTest.class.getResourceAsStream(resourcePath)) {
            assertThat(input).as("manual question set resource %s", resourcePath).isNotNull();
            ManualQuestionSet questionSet = objectMapper.readValue(input, ManualQuestionSet.class);
            assertThat(questionSet.version()).isNotBlank();
            assertThat(questionSet.questions()).isNotEmpty();
            assertThat(questionSet.questions().stream().map(ManualQuestion::id).toList())
                    .doesNotHaveDuplicates();
            return questionSet;
        }
    }

    private AcceptedQuestionRuntime loadAcceptedQuestionSet() throws Exception {
        String baseLocation = System.getProperty(
                "chatagent.eval.docIngestion.acceptedQuestionSet",
                DEFAULT_ACCEPTED_QUESTION_SET);
        String manifestLocation = System.getProperty(
                "chatagent.eval.docIngestion.acceptedQuestionManifest",
                DEFAULT_ACCEPTED_QUESTION_MANIFEST);
        JsonNode base = readJsonLocation(baseLocation);
        JsonNode manifest = readJsonLocation(manifestLocation);

        String overlayLocation = System.getProperty("chatagent.eval.docIngestion.acceptedQuestionOverlay");
        List<JsonNode> overlayRows = StringUtils.hasText(overlayLocation)
                ? readJsonLines(Path.of(overlayLocation).toAbsolutePath().normalize())
                : List.of();
        String receiptLocation = System.getProperty("chatagent.eval.docIngestion.acceptedQuestionOverlayReceipt");
        assertThat(!StringUtils.hasText(receiptLocation) || StringUtils.hasText(overlayLocation))
                .as("overlay validation receipt cannot be configured without an overlay")
                .isTrue();
        JsonNode receipt = StringUtils.hasText(receiptLocation) ? readJsonLocation(receiptLocation) : null;
        AcceptedQuestionSetContract.ValidatedQuestionSet validation =
                AcceptedQuestionSetContract.validate(base, manifest, overlayRows, receipt);
        verifyAcceptedEvidenceIdentity(manifest, validation.evidenceExportDatasetHash());

        ManualQuestionSet questionSet =
                objectMapper.treeToValue(validation.effectiveBase(), ManualQuestionSet.class);
        assertThat(questionSet.version()).isEqualTo("manual-accepted-questions-v1");
        assertThat(questionSet.questions()).isNotEmpty();
        assertThat(questionSet.questions().stream().map(ManualQuestion::id).toList()).doesNotHaveDuplicates();
        return new AcceptedQuestionRuntime(
                questionSet,
                validation,
                baseLocation,
                manifestLocation,
                StringUtils.hasText(overlayLocation) ? overlayLocation : null);
    }

    private JsonNode readJsonLocation(String location) throws IOException {
        if (location.startsWith("/")) {
            try (InputStream input = ProductionDocumentIngestionEvalTest.class.getResourceAsStream(location)) {
                assertThat(input).as("required JSON resource %s", location).isNotNull();
                return objectMapper.readTree(input);
            }
        }
        Path path = Path.of(location).toAbsolutePath().normalize();
        assertThat(path).as("required JSON file %s", path).exists();
        return objectMapper.readTree(path.toFile());
    }

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        assertThat(path).as("required JSONL file %s", path).exists();
        List<JsonNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(objectMapper.readTree(line));
            }
        }
        assertThat(rows).as("overlay file must contain at least one entry").isNotEmpty();
        return rows;
    }

    private void verifyAcceptedEvidenceIdentity(JsonNode manifest, String expectedDatasetHash) throws IOException {
        Path evidenceManifest = Path.of(manifest.path("evidenceExportManifestPath").asText())
                .toAbsolutePath().normalize();
        AcceptedQuestionSetContract.validateEvidenceExportIdentity(evidenceManifest, expectedDatasetHash);
    }

    private void validateBoundOverlayReceipt(AcceptedQuestionSetContract.ValidatedQuestionSet validation,
                                             List<GroundedQuery> queries) {
        if (validation.overlayHash() == null) {
            return;
        }
        Set<String> modifiedIds = validation.receiptRows().keySet();
        Map<String, AcceptedQuestionSetContract.BoundEvidence> boundById = queries.stream()
                .filter(query -> modifiedIds.contains(query.questionId()))
                .collect(Collectors.toMap(
                        GroundedQuery::questionId,
                        query -> new AcceptedQuestionSetContract.BoundEvidence(
                                query.queryText(), query.referenceAnswer(), query.referenceContent())));
        AcceptedQuestionSetContract.validateBoundReceipt(validation, boundById);
    }

    private void writeAcceptedQuestionRuntimeManifest(AcceptedQuestionRuntime accepted, Path artifactsDir)
            throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("questionSetVersion", accepted.questionSet().version());
        manifest.put("questionSetLocation", accepted.baseLocation());
        manifest.put("questionSetManifestLocation", accepted.manifestLocation());
        manifest.put("baseHash", accepted.validation().baseHash());
        manifest.put("evidenceExportDatasetHash", accepted.validation().evidenceExportDatasetHash());
        if (accepted.validation().overlayHash() != null) {
            manifest.put("overlayLocation", accepted.overlayLocation());
            manifest.put("overlayHash", accepted.validation().overlayHash());
        }
        objectMapper.writeValue(artifactsDir.resolve("accepted-question-runtime-manifest.json").toFile(), manifest);
    }

    static List<GroundedQuery> bindManualQuestions(List<IngestedDocument> docs, ManualQuestionSet questionSet) {
        List<GroundedQuery> queries = new ArrayList<>();
        for (ManualQuestion manual : questionSet.questions()) {
            List<IngestedDocument> matchingDocs = StringUtils.hasText(manual.sourceSha256())
                    ? docs.stream().filter(doc -> manual.sourceSha256().equals(doc.sha256())).toList()
                    : docs.stream().filter(doc -> manual.filename().equals(doc.source.filename())).toList();
            assertThat(matchingDocs)
                    .as("manual question %s must bind exactly one ingested document", manual.id())
                    .hasSize(1);
            IngestedDocument doc = matchingDocs.get(0);
            if (StringUtils.hasText(manual.format())) {
                assertThat(doc.source.format())
                        .as("manual question %s format must match source format", manual.id())
                        .isEqualTo(manual.format());
            }
            assertThat(doc.source.split())
                    .as("manual question %s split must match source split", manual.id())
                    .isEqualTo(manual.split());
            List<KnowledgeChunkDTO> matchingChunks = doc.chunks.stream()
                    .filter(chunk -> StringUtils.hasText(chunk.getContent()))
                    .filter(chunk -> manual.chunkIndex() == null || manual.chunkIndex().equals(chunk.getChunkIndex()))
                    .filter(chunk -> normalizedMatchText(chunk.getContent())
                            .contains(normalizedMatchText(manual.referenceNeedle())))
                    .toList();
            assertThat(matchingChunks)
                    .as("manual question %s referenceNeedle must bind exactly one chunk", manual.id())
                    .hasSize(1);
            KnowledgeChunkDTO chunk = matchingChunks.get(0);
            queries.add(new GroundedQuery(
                    manual.question(),
                    chunk.getId(),
                    doc.docId,
                    doc.source.filename(),
                    doc.source.format(),
                    chunk.getContent(),
                    firstNonBlank(manual.generationMethod(), "manual-reviewed-no-source-v1"),
                    manual.auditStatus(),
                    manual.llmProvenance(),
                    doc.source.split(),
                    querySourceHint(doc.source),
                    manual.id(),
                    questionSet.version(),
                    manual.referenceAnswer(),
                    null,
                    null,
                    null,
                    doc.source.sourceUrl(),
                    doc.sha256(),
                    doc.source.sourceGroup(),
                    doc.source.license()));
        }
        return queries;
    }

    private static void assertManualQuestionsSafe(List<GroundedQuery> queries) {
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.queryText()).isNotBlank();
            assertThat(query.referenceAnswer()).isNotBlank();
            assertThat(normalizedMatchText(query.queryText()))
                    .doesNotContain(normalizedMatchText(query.referenceAnswer()));
            assertThat(query.queryText()).doesNotContainIgnoringCase(query.referenceDocFilename());
            assertThat(query.queryText()).doesNotContainIgnoringCase(query.sourceHint());
            assertThat(query.queryText()).doesNotContain("http://", "https://", "\\");
        });
    }

    private static String normalizedMatchText(String value) {
        return StringUtils.hasText(value)
                ? value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim()
                : "";
    }

    private static String querySourceHint(SourceFile source) {
        List<String> parts = new ArrayList<>();
        addSourceHintPart(parts, source.format());
        addSourceHintPart(parts, source.sourceGroup());
        addSourceHintPart(parts, source.filename().replaceFirst("\\.[^.]+$", ""));
        String hint = parts.stream()
                .distinct()
                .collect(Collectors.joining(" / "));
        return hint.length() <= 160 ? hint : hint.substring(0, 160);
    }

    private static void addSourceHintPart(List<String> parts, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String cleaned = value.replaceAll("[_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (StringUtils.hasText(cleaned)) {
            parts.add(cleaned);
        }
    }

    private static String extractFirstSentence(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String cleaned = content.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < cleaned.length()) {
                char next = cleaned.charAt(i + 1);
                if (Character.isWhitespace(next)) {
                    return cleaned.substring(0, i + 1).strip();
                }
            }
        }
        return cleaned.length() > 220 ? cleaned.substring(0, 220) : cleaned;
    }

    private static double computeCandidateHitAtK(List<QueryResult> results, int k) {
        if (results.isEmpty()) {
            return 0.0;
        }
        long hits = results.stream()
                .filter(r -> r.candidates.stream().limit(k)
                        .anyMatch(candidate -> {
                            MilvusSearchHit hit = candidate.hit();
                            return hit.chunkId() != null && hit.chunkId().equals(r.query.referenceChunkId());
                        }))
                .count();
        return (double) hits / results.size();
    }

    private static double computeCandidateContextRecallAtK(List<QueryResult> results, int k) {
        return computeCandidateHitAtK(results, k);
    }

    static Map<String, Double> oracleRecallByFormat(List<QueryResult> results) {
        return results.stream()
                .collect(Collectors.groupingBy(r -> r.query.format(), LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> computeCandidateContextRecallAtK(entry.getValue(), CANDIDATE_K),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    static void assertCleanQuestionOracleRecallGate(Map<String, Double> oracleRecallByFormat) {
        assertThat(oracleRecallByFormat.getOrDefault("SEC_HTML", 0.0))
                .as("Post-B3.3 clean-question hard gate: SEC_HTML oracle recall >= 0.80")
                .isGreaterThanOrEqualTo(0.80);
        assertThat(oracleRecallByFormat.getOrDefault("WEB_MD", 0.0))
                .as("Post-B3.3 clean-question hard gate: WEB_MD oracle recall >= 0.85")
                .isGreaterThanOrEqualTo(0.85);
        for (Map.Entry<String, Double> baseline : BASELINE_ORACLE_RECALL_BY_FORMAT.entrySet()) {
            double minimum = baseline.getValue() - NON_HTML_ORACLE_REGRESSION_TOLERANCE;
            assertThat(oracleRecallByFormat.getOrDefault(baseline.getKey(), 0.0))
                    .as("Post-B3.3 clean-question hard gate: %s oracle recall must not regress by more than %.2f absolute from %.3f",
                            baseline.getKey(), NON_HTML_ORACLE_REGRESSION_TOLERANCE, baseline.getValue())
                    .isGreaterThanOrEqualTo(minimum);
        }
    }

    static void assertAcceptedHybridCandidateCoverage(List<List<RankedCandidateHit>> candidateLists) {
        assertThat(candidateLists)
                .as("Accepted-size evaluation must produce candidate lists")
                .isNotEmpty();
        assertThat(candidateLists)
                .as("Accepted-size candidate export must include raw dense and BM25 rank signals for every query")
                .allSatisfy(candidates -> {
                    assertThat(candidates).isNotEmpty();
                    assertThat(candidates).anyMatch(candidate -> candidate.denseRank() != null);
                    assertThat(candidates).anyMatch(candidate -> candidate.bm25Rank() != null);
                });
    }

    private double computeCandidateMrr(List<QueryResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (QueryResult r : results) {
            for (int i = 0; i < r.candidates.size(); i++) {
                MilvusSearchHit hit = r.candidates.get(i).hit();
                if (hit.chunkId() != null && hit.chunkId().equals(r.query.referenceChunkId())) {
                    sum += 1.0 / (i + 1);
                    break;
                }
            }
        }
        return sum / results.size();
    }

    private double computeFinalHitAtK(List<QueryResult> results, int k) {
        if (results.isEmpty()) {
            return 0.0;
        }
        long hits = results.stream()
                .filter(r -> r.finalHits.stream().limit(k)
                        .map(hit -> finalChunkId(hit, r.candidates))
                        .anyMatch(r.query.referenceChunkId()::equals))
                .count();
        return (double) hits / results.size();
    }

    private double computeFinalContextRecallAtK(List<QueryResult> results, int k) {
        return computeFinalHitAtK(results, k);
    }

    private double computeFinalMrr(List<QueryResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (QueryResult r : results) {
            for (int i = 0; i < r.finalHits.size(); i++) {
                if (r.query.referenceChunkId().equals(finalChunkId(r.finalHits.get(i), r.candidates))) {
                    sum += 1.0 / (i + 1);
                    break;
                }
            }
        }
        return sum / results.size();
    }

    private double computeFinalPhraseRecall(List<QueryResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        return results.stream()
                .mapToDouble(this::finalPhraseRecall)
                .average()
                .orElse(0.0);
    }

    private double finalPhraseRecall(QueryResult result) {
        Set<String> meaningful = Arrays.stream(result.query.referenceContent().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> word.length() >= 4)
                .collect(Collectors.toSet());
        if (meaningful.isEmpty()) {
            return 1.0;
        }
        String combined = result.finalHits.stream()
                .map(hit -> StringUtils.hasText(hit.content()) ? hit.content() : hit.contextText())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        long found = meaningful.stream().filter(combined::contains).count();
        return (double) found / meaningful.size();
    }

    private String finalChunkId(RetrievalHit hit, List<RankedCandidateHit> candidates) {
        return findCandidateMatch(hit, candidates)
                .map(match -> match.hit().chunkId())
                .orElse("");
    }

    private void exportDatasetRoot(List<QueryResult> results,
                                   List<IngestedDocument> docs,
                                   List<DocumentFailure> failures,
                                   String kbId,
                                   Path root) throws Exception {
        Map<String, IngestedDocument> docMap = docs.stream()
                .collect(Collectors.toMap(d -> d.docId, d -> d));
        Map<String, IngestedDocument> docBySourceIdentity = docs.stream()
                .collect(Collectors.toMap(
                        d -> ReferenceRebinder.sourceIdentity(d.source.sourceUrl(), d.sha256(), d.source.filename()),
                        d -> d,
                        (left, right) -> left,
                        LinkedHashMap::new));

        Path datasetPath = root.resolve(Path.of("datasets", "doc-ingestion", DATASET_ID + ".jsonl"));
        Files.createDirectories(datasetPath.getParent());

        List<Map<String, Object>> datasetRows = new ArrayList<>();
        try (var writer = Files.newBufferedWriter(datasetPath, StandardCharsets.UTF_8)) {
            for (QueryResult r : results) {
                IngestedDocument doc = docForQuery(r.query, docMap, docBySourceIdentity);
                Map<String, Object> metadata = rowMetadata(r, doc, kbId);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sampleId", firstNonBlank(r.query.sampleId(), UUID.randomUUID().toString()));
                row.put("datasetId", DATASET_ID);
                row.put("sourceGroupId", firstNonBlank(r.query.sourceGroupId(), r.query.referenceDocId()));
                row.put("split", firstNonBlank(r.query.split(), doc != null ? doc.source.split() : "calibration"));
                row.put("fileId", firstNonBlank(r.query.fileId(), r.query.referenceDocId()));
                row.put("fileFormat", r.query.format());
                row.put("sourceUrl", firstNonBlank(r.query.sourceUrl(), doc != null ? doc.source.sourceUrl() : null));
                row.put("userInput", r.query.queryText());
                row.put("referenceContextIds", List.of(r.query.referenceChunkId()));
                row.put("metadata", metadata);

                writer.write(jsonlMapper.writeValueAsString(row));
                writer.newLine();
                datasetRows.add(row);
            }
        }

        Map<String, Object> splitManifest = buildSplitManifest(datasetRows);
        Path splitPath = root.resolve(Path.of("manifests", "splits", DATASET_ID + ".json"));
        Files.createDirectories(splitPath.getParent());
        objectMapper.writeValue(splitPath.toFile(), splitManifest);
        String splitManifestHash = manifestHash(splitPath);

        String datasetHash = manifestHash(datasetPath);
        Map<String, Object> datasetManifest = buildDatasetManifest(datasetRows, datasetHash, splitManifest, splitManifestHash);
        Path manifestPath = root.resolve(Path.of("manifests", "datasets", DATASET_ID + ".json"));
        Files.createDirectories(manifestPath.getParent());
        objectMapper.writeValue(manifestPath.toFile(), datasetManifest);

        writeMetrics(results, docs, root, datasetHash, splitManifest);
        writeSourceManifest(docs, failures, root);
    }

    private IngestedDocument docForQuery(GroundedQuery query,
                                         Map<String, IngestedDocument> docMap,
                                         Map<String, IngestedDocument> docBySourceIdentity) {
        IngestedDocument doc = docMap.get(query.referenceDocId());
        if (doc != null) {
            return doc;
        }
        if (StringUtils.hasText(query.sourceUrl())
                && StringUtils.hasText(query.sourceSha256())
                && StringUtils.hasText(query.referenceDocFilename())) {
            return docBySourceIdentity.get(ReferenceRebinder.sourceIdentity(
                    query.sourceUrl(), query.sourceSha256(), query.referenceDocFilename()));
        }
        return null;
    }

    private Map<String, Object> rowMetadata(QueryResult r, IngestedDocument doc, String kbId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", r.query.format());
        metadata.put("referenceContent", r.query.referenceContent());
        metadata.put("generationMethod", r.query.generationMethod());
        metadata.put("queryDisambiguation", Map.of(
                "enabled", false,
                "method", "metadata-only-source-identity",
                "sourceHint", r.query.sourceHint()
        ));
        metadata.putAll(questionAuthorshipMetadata(r.query));
        if (StringUtils.hasText(r.query.referenceAnswer())) {
            metadata.put("referenceAnswer", r.query.referenceAnswer());
        }
        metadata.put("sourceUrl", firstNonBlank(r.query.sourceUrl(), doc != null ? doc.source.sourceUrl() : null));
        metadata.put("sourceSha256", firstNonBlank(r.query.sourceSha256(), doc != null ? doc.sha256 : null));
        metadata.put("sourceGroup", firstNonBlank(r.query.sourceGroup(), doc != null ? doc.source.sourceGroup() : null));
        metadata.put("license", firstNonBlank(r.query.license(), doc != null ? doc.source.license() : null));
        metadata.put("knowledgeBaseId", kbId);
        metadata.put("referenceDocId", r.query.referenceDocId());
        if (doc != null) {
            metadata.put("newReferenceDocId", doc.docId());
        }
        metadata.put("referenceDocFilename", r.query.referenceDocFilename());
        metadata.put("parser", doc != null ? parserForSource(doc.source) : parserForFormat(r.query.format()));
        addChunkerMetadata(metadata, doc != null ? doc.source : null, r.query.format());
        metadata.put("embeddingModel", "bge-m3");
        metadata.put("embeddingProvider", "ollama");
        metadata.put("embeddingDimension", 1024);
        metadata.put("vectorIndex", "milvus");
        metadata.put("vectorMetric", "COSINE");
        metadata.put("ingestionPath", "mq-outbox");
        metadata.put("mqEnabled", true);
        metadata.put("mineruEnabled", doc != null && doc.mineru.enabled());
        if (doc != null) {
            metadata.put("sourceBytes", doc.sizeBytes());
            metadata.put("downloadedAt", doc.downloadedAt());
            metadata.put("mq", mqMetadata(doc.mqTrace(), doc.chunks().size(), doc.milvusEvidence()));
            metadata.put("mineru", mineruMetadata(doc.mineru()));
        }
        metadata.put("candidateContexts", candidateContexts(r.candidates));
        metadata.put("retrievedContexts", retrievedContextsFromFinalHits(r.finalHits, r.candidates));
        metadata.put("retrievalProvenance", Map.of(
                "candidateK", CANDIDATE_K,
                "finalTopK", TOP_K,
                "rrfK", RRF_K,
                "finalPath", "KnowledgeBaseSimilaritySearcher.searchByKnowledgeBaseIds",
                "candidatePath", "KnowledgeBaseSimilaritySearcher.searchRankedCandidateHitsByKnowledgeBaseIds",
                "rerankerExpected", true,
                "rerankerMaxCandidates", RERANKER_MAX_CANDIDATES,
                "rerankerProvider", rerankerProperties.getProvider(),
                "rerankerModel", rerankerProperties.getModelId(),
                "primaryMetricsSource", "post-rerank-final-hits"
        ));
        metadata.put("retrievalLatency", Map.of(
                "candidatePathMs", r.candidatePathMs,
                "finalProductionPathMs", r.finalPathMs
        ));
        return metadata;
    }

    static Map<String, Object> questionAuthorshipMetadata(GroundedQuery query) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if ("manual-reviewed-no-source-v1".equals(query.generationMethod())
                || "llm-generated-llm-reviewed-v1".equals(query.generationMethod())
                || "codex-manual-assisted-llm-reviewed-v1".equals(query.generationMethod())) {
            metadata.put("questionProvenance", Map.of(
                    "method", "codex-manual-assisted-llm-reviewed-v1".equals(query.generationMethod())
                            ? "codex-manual-assisted-no-source-v1"
                            : "llm-generated-llm-reviewed-v1".equals(query.generationMethod())
                                    ? "llm-assisted-no-source-v1"
                                    : "manual-reviewed-no-source-v1",
                    "questionId", query.questionId(),
                    "questionSetVersion", query.questionSetVersion(),
                    "sourceIdentityInjected", false,
                    "referenceNeedleInjected", false,
                    "referenceAnswerInjected", false
            ));
        }
        if (query.llmProvenance() != null) {
            metadata.put("llmProvenance", Map.of(
                    "generatorModel", query.llmProvenance().generatorModel(),
                    "generatorTool", query.llmProvenance().generatorTool(),
                    "reviewerModel", query.llmProvenance().reviewerModel(),
                    "reviewerTool", query.llmProvenance().reviewerTool(),
                    "reviewerPromptVersion", query.llmProvenance().reviewerPromptVersion()
            ));
        }
        if (StringUtils.hasText(query.auditStatus())) {
            metadata.put("auditStatus", query.auditStatus());
        }
        return metadata;
    }

    private List<Map<String, Object>> candidateContexts(List<RankedCandidateHit> candidates) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RankedCandidateHit candidate = candidates.get(i);
            MilvusSearchHit hit = candidate.hit();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", hit.chunkId());
            m.put("chunkId", hit.chunkId());
            m.put("documentId", hit.documentId());
            m.put("documentName", hit.documentName());
            m.put("chunkIndex", hit.chunkIndex());
            m.put("score", hit.score());
            m.put("content", StringUtils.hasText(hit.content()) ? hit.content() : hit.retrievalText());
            Map<String, Object> rankSignals = new LinkedHashMap<>();
            rankSignals.put("candidateRank", i + 1);
            rankSignals.put("denseRank", candidate.denseRank());
            rankSignals.put("bm25Rank", candidate.bm25Rank());
            rankSignals.put("denseScore", candidate.denseScore());
            rankSignals.put("bm25Score", candidate.bm25Score());
            rankSignals.put("fusedScore", candidate.fusedScore());
            m.put("rankSignals", rankSignals);
            contexts.add(m);
        }
        return contexts;
    }

    private List<Map<String, Object>> retrievedContexts(List<RankedCandidateHit> candidates) {
        return candidates.stream()
                .map(RankedCandidateHit::hit)
                .map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chunkId", h.chunkId());
                    m.put("documentId", h.documentId());
                    m.put("documentName", h.documentName());
                    m.put("chunkIndex", h.chunkIndex());
                    m.put("score", h.score());
                    m.put("content", StringUtils.hasText(h.content()) ? h.content() : h.retrievalText());
                    return m;
                })
                .toList();
    }

    private List<Map<String, Object>> retrievedContextsFromFinalHits(List<RetrievalHit> hits, List<RankedCandidateHit> candidates) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit h = hits.get(i);
            Optional<CandidateMatch> candidateMatch = findCandidateMatch(h, candidates);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", candidateMatch.map(match -> match.hit().chunkId())
                    .orElseGet(() -> "unmatched-final-hit:" + h.documentId() + ":" + h.chunkIndex()));
            m.put("documentId", h.documentId());
            m.put("documentName", h.documentName());
            m.put("chunkIndex", h.chunkIndex());
            m.put("score", h.score());
            m.put("scoreType", h.scoreType());
            m.put("finalRank", i + 1);
            m.put("candidateRank", candidateMatch.map(CandidateMatch::candidateRank).orElse(null));
            m.put("rerankerRank", i + 1);
            m.put("rerankerScore", h.score());
            m.put("rerankerScoreType", h.scoreType());
            m.put("reranked", "reranker".equals(h.scoreType()));
            m.put("fallbackReason", candidateMatch.isEmpty()
                    ? "candidate-match-missing"
                    : ("reranker".equals(h.scoreType()) ? null : h.scoreType()));
            m.put("content", StringUtils.hasText(h.content()) ? h.content() : h.contextText());
            contexts.add(m);
        }
        return contexts;
    }

    private Optional<CandidateMatch> findCandidateMatch(RetrievalHit hit, List<RankedCandidateHit> candidates) {
        String content = StringUtils.hasText(hit.content()) ? hit.content() : hit.contextText();
        for (int i = 0; i < candidates.size(); i++) {
            MilvusSearchHit candidate = candidates.get(i).hit();
            if (!Objects.equals(candidate.documentId(), hit.documentId())) {
                continue;
            }
            Integer candidateChunkIndex = candidate.chunkIndex() != null && candidate.chunkIndex() >= 0
                    ? candidate.chunkIndex()
                    : null;
            if (!Objects.equals(candidateChunkIndex, hit.chunkIndex())) {
                continue;
            }
            if (!Objects.equals(
                    StringUtils.hasText(candidate.content()) ? candidate.content() : candidate.retrievalText(),
                    content
            )) {
                continue;
            }
            return Optional.of(new CandidateMatch(i + 1, candidate));
        }
        return Optional.empty();
    }

    private Map<String, Object> mqMetadata(MqTrace trace, int chunkCount, boolean milvusEvidence) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("outboxEventId", trace.eventId());
        metadata.put("idempotencyKey", trace.idempotencyKey());
        metadata.put("outboxStatus", trace.outboxStatus());
        metadata.put("consumerCompleted", trace.consumerCompleted());
        metadata.put("parseStatus", trace.parseStatus());
        metadata.put("chunkCount", chunkCount);
        metadata.put("milvusInserted", chunkCount > 0);
        metadata.put("milvusRetrievalProbeMatched", milvusEvidence);
        metadata.put("outboxCreatedAt", trace.createdAt() == null ? null : trace.createdAt().toString());
        return metadata;
    }

    private Map<String, Object> mineruMetadata(MineruTrace trace) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enabled", trace.enabled());
        metadata.put("selected", trace.selected());
        metadata.put("engineId", trace.engineId());
        metadata.put("visualChunkCount", trace.visualChunkCount());
        metadata.put("evidenceFields", trace.evidenceFields());
        return metadata;
    }

    private void writeMetrics(List<QueryResult> results,
                              List<IngestedDocument> docs,
                              Path root,
                              String datasetHash,
                              Map<String, Object> splitManifest) throws Exception {
        Map<String, String> splitHashes = splitHashes(splitManifest);
        writeMetricsArtifact(results, docs, root.resolve("metrics.json"), datasetHash, splitHashes);
        List<QueryResult> searchResults = results.stream()
                .filter(result -> Set.of("calibration", "development").contains(result.query.split()))
                .toList();
        if (!searchResults.isEmpty()) {
            writeMetricsArtifact(
                    searchResults,
                    docs,
                    root.resolve("metrics-calibration-development.json"),
                    datasetHash,
                    splitHashes
            );
        }
        List<QueryResult> holdoutResults = results.stream()
                .filter(result -> "holdout".equals(result.query.split()))
                .toList();
        if (!holdoutResults.isEmpty()) {
            writeMetricsArtifact(
                    holdoutResults,
                    docs,
                    root.resolve("metrics-holdout.json"),
                    datasetHash,
                    splitHashes
            );
        }
    }

    private void writeMetricsArtifact(List<QueryResult> results,
                                      List<IngestedDocument> docs,
                                      Path path,
                                      String datasetHash,
                                      Map<String, String> allSplitHashes) throws Exception {
        Map<String, Long> filesByFormat = docs.stream()
                .collect(Collectors.groupingBy(d -> d.source.format(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> queriesByFormat = results.stream()
                .collect(Collectors.groupingBy(r -> r.query.format(), LinkedHashMap::new, Collectors.counting()));
        Set<String> splits = results.stream().map(result -> result.query.split()).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("runId", RUN_ID);
        metrics.put("acceptedSize", acceptedSizeRun());
        metrics.put("datasetHash", datasetHash);
        metrics.put("splits", splits);
        metrics.put("splitHashes", allSplitHashes.entrySet().stream()
                .filter(entry -> splits.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new)));
        metrics.put("documentCount", docs.size());
        metrics.put("queryCount", results.size());
        metrics.put("chunkCount", docs.stream().mapToInt(d -> d.chunks.size()).sum());
        metrics.put("hitAt3", computeFinalHitAtK(results, 3));
        metrics.put("contextRecallAt3", computeFinalContextRecallAtK(results, 3));
        metrics.put("hitAtK", computeFinalHitAtK(results, TOP_K));
        metrics.put("contextRecallAtK", computeFinalContextRecallAtK(results, TOP_K));
        metrics.put("mrr", computeFinalMrr(results));
        metrics.put("preRerankHitAtK", computeCandidateHitAtK(results, TOP_K));
        metrics.put("preRerankContextRecallAtK", computeCandidateContextRecallAtK(results, TOP_K));
        metrics.put("preRerankMrr", computeCandidateMrr(results));
        metrics.put("phraseRecall", computeFinalPhraseRecall(results));
        metrics.put("topK", TOP_K);
        metrics.put("candidateK", CANDIDATE_K);
        metrics.put("rrfK", RRF_K);
        metrics.put("rerankerMaxCandidates", RERANKER_MAX_CANDIDATES);
        metrics.put("retrievalConfig", retrievalConfig());
        metrics.put("perFormat", finalMetricsByFormat(results));
        metrics.put("filesByFormat", filesByFormat);
        metrics.put("queriesByFormat", queriesByFormat);
        metrics.put("timestamp", LocalDateTime.now().toString());
        objectMapper.writeValue(path.toFile(), metrics);
    }

    private Map<String, Object> retrievalConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topK", TOP_K);
        config.put("candidateK", CANDIDATE_K);
        config.put("rrfK", RRF_K);
        config.put("embeddingProvider", "ollama");
        config.put("embeddingModel", "bge-m3");
        config.put("vectorProvider", "milvus");
        config.put("rerankerProvider", rerankerProperties.getProvider());
        config.put("rerankerModel", rerankerProperties.getModelId());
        config.put("rerankerMaxCandidates", rerankerProperties.getMaxCandidates());
        config.put("rerankerMaxChunkChars", rerankerProperties.getMaxChunkChars());
        config.put("rerankerConfidenceFilterEnabled", rerankerProperties.isEnableConfidenceFilter());
        config.put("rerankerScoreThreshold", rerankerProperties.getScoreThreshold());
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> splitHashes(Map<String, Object> splitManifest) {
        Map<String, Object> splits = (Map<String, Object>) splitManifest.get("splits");
        return splits.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.valueOf(((Map<String, Object>) entry.getValue()).get("groupHash")),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, Map<String, Double>> finalMetricsByFormat(List<QueryResult> results) {
        return results.stream()
                .collect(Collectors.groupingBy(r -> r.query.format(), LinkedHashMap::new, Collectors.toList()))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Map.of(
                                "hitAtK", computeFinalHitAtK(entry.getValue(), TOP_K),
                                "contextRecallAtK", computeFinalContextRecallAtK(entry.getValue(), TOP_K),
                                "oracleRecallAtCandidateK", computeCandidateContextRecallAtK(entry.getValue(), CANDIDATE_K),
                                "mrr", computeFinalMrr(entry.getValue()),
                                "phraseRecall", computeFinalPhraseRecall(entry.getValue())
                        ),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private void writeSourceManifest(List<IngestedDocument> docs, List<DocumentFailure> failures, Path root) throws Exception {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (IngestedDocument doc : docs) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("documentId", doc.docId());
            source.put("sourceUrl", doc.source().sourceUrl());
            source.put("filename", doc.source().filename());
            source.put("mimeType", doc.source().mimeType());
            source.put("sourceGroup", doc.source().sourceGroup());
            source.put("format", doc.source().format());
            source.put("license", doc.source().license());
            source.put("split", doc.source().split());
            source.put("archiveEntry", doc.source().archiveEntry());
            source.put("sha256", doc.sha256());
            source.put("sizeBytes", doc.sizeBytes());
            source.put("downloadedAt", doc.downloadedAt());
            source.put("parser", parserForSource(doc.source()));
            source.put("chunkCount", doc.chunks().size());
            source.put("mq", mqMetadata(doc.mqTrace(), doc.chunks().size(), doc.milvusEvidence()));
            source.put("mineru", mineruMetadata(doc.mineru()));
            sources.add(source);
        }
        List<Map<String, Object>> failureRows = failures.stream()
                .map(failure -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sourceUrl", failure.source().sourceUrl());
                    row.put("filename", failure.source().filename());
                    row.put("format", failure.source().format());
                    row.put("split", failure.source().split());
                    row.put("error", failure.error());
                    return row;
                })
                .toList();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", RUN_ID);
        manifest.put("acceptedSize", acceptedSizeRun());
        manifest.put("sources", sources);
        manifest.put("failures", failureRows);
        objectMapper.writeValue(root.resolve("source-manifest.json").toFile(), manifest);
    }

    private Map<String, Object> buildSplitManifest(List<Map<String, Object>> rows) {
        Map<String, LinkedHashSet<String>> groupsBySplit = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            groupsBySplit.computeIfAbsent(row.get("split").toString(), ignored -> new LinkedHashSet<>())
                    .add(row.get("sourceGroupId").toString());
        }
        Map<String, Object> splits = new LinkedHashMap<>();
        groupsBySplit.forEach((split, groupIds) -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("groupIds", new ArrayList<>(groupIds));
            details.put("groupHash", manifestHash(groupIds));
            splits.put(split, details);
        });
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("datasetId", DATASET_ID);
        manifest.put("splits", splits);
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDatasetManifest(List<Map<String, Object>> rows,
                                                     String datasetHash,
                                                     Map<String, Object> splitManifest,
                                                     String splitManifestHash) {
        Map<String, Object> splitDetails = (Map<String, Object>) splitManifest.get("splits");
        Map<String, Object> splits = new LinkedHashMap<>();
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
        Set<String> sourceIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            groupIds.add(row.get("sourceGroupId").toString());
            Map<String, Object> meta = (Map<String, Object>) row.get("metadata");
            if (meta != null && meta.get("sourceGroup") != null) {
                sourceIds.add(meta.get("sourceGroup").toString());
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("datasetId", DATASET_ID);
        manifest.put("version", 1);
        manifest.put("sourceIds", List.copyOf(sourceIds));
        manifest.put("recordSchema", "eval-doc-ingestion-dataset-record.schema.json");
        manifest.put("localPath", "datasets/doc-ingestion/" + DATASET_ID + ".jsonl");
        manifest.put("datasetHash", datasetHash);
        manifest.put("splitManifestPath", "manifests/splits/" + DATASET_ID + ".json");
        manifest.put("splitManifestHash", splitManifestHash);
        manifest.put("recordCount", rows.size());
        manifest.put("groupCount", groupIds.size());
        manifest.put("splits", splits);
        manifest.put("provenance", Map.of(
                "provider", "ollama",
                "modelName", "bge-m3",
                "embeddingModel", "bge-m3",
                "exportTimestamp", LocalDateTime.now().toString()
        ));
        return manifest;
    }

    private static List<SourceFile> buildAcceptedCatalog() throws Exception {
        // B3.2 fail-closed: frozen source manifest is mandatory for accepted-size runs.
        // Dynamic discovery is not permitted — recall gate must compare against Phase 10 baseline.
        Path manifestPath = frozenSourceManifestPath();
        assertPinnedSourceManifest(manifestPath);
        return buildFrozenSourceCatalog(manifestPath);
    }

    // -----------------------------------------------------------------------
    // B3.2: Frozen-source replay mode
    // -----------------------------------------------------------------------

    /**
     * Loads the frozen source catalog from a pinned manifest, preserving exact
     * source-group/split/format mapping from the baseline run. Returns source files
     * with their expected SHA-256 hashes stored for verification during download.
     */
    private static List<SourceFile> buildFrozenSourceCatalog(Path manifestPath) throws Exception {
        assertPinnedSourceManifest(manifestPath);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(manifestPath.toFile());
        JsonNode sources = root.path("sources");
        assertThat(sources.isArray())
                .as("Frozen source manifest must contain a sources array")
                .isTrue();

        List<SourceFile> catalog = new ArrayList<>();
        for (JsonNode src : sources) {
            String url = src.path("sourceUrl").asText();
            String filename = src.path("filename").asText();
            String mimeType = src.path("mimeType").asText();
            String sourceGroup = src.path("sourceGroup").asText();
            String format = src.path("format").asText();
            String license = src.path("license").asText();
            String split = src.path("split").asText();
            String sha256 = src.path("sha256").asText();
            String archiveEntry = src.path("archiveEntry").asText(null);
            if (archiveEntry != null && archiveEntry.isEmpty()) {
                archiveEntry = null;
            }

            SourceFile sourceFile = archiveEntry != null
                    ? archive(frozenArchiveDownloadUrl(url, archiveEntry), archiveEntry,
                            filename, mimeType, sourceGroup, format, license, split)
                    : direct(url, filename, mimeType, sourceGroup, format, license, split);
            catalog.add(sourceFile.withExpectedSha256(sha256));
        }
        assertThat(catalog).as("Frozen source manifest must contain exactly 200 sources for B3.2 reproducible gate")
                .hasSize(FROZEN_SOURCE_COUNT);
        return catalog;
    }

    /**
     * Loads expected SHA-256 hashes from the frozen source manifest for download verification.
     * Fail-closed: throws if the manifest is missing.
     */
    private static Map<String, String> loadFrozenSourceHashes() throws Exception {
        return loadFrozenSourceHashes(frozenSourceManifestPath());
    }

    static Map<String, String> loadFrozenSourceHashes(Path manifestPath) throws Exception {
        assertPinnedSourceManifest(manifestPath);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(manifestPath.toFile());
        Map<String, String> hashes = new LinkedHashMap<>();
        for (JsonNode src : root.path("sources")) {
            String sourceUrl = src.path("sourceUrl").asText();
            String archiveEntry = src.path("archiveEntry").asText(null);
            if (StringUtils.hasText(archiveEntry)) {
                frozenArchiveDownloadUrl(sourceUrl, archiveEntry);
            }
            String filename = src.path("filename").asText();
            String sha256 = src.path("sha256").asText(null);
            String identity = ReferenceRebinder.sourceIdentity(sourceUrl, sha256, filename);
            hashes.put(identity, sha256);
        }
        assertThat(hashes)
                .as("Frozen source hash map must verify all 200 source identities")
                .hasSize(FROZEN_SOURCE_COUNT);
        return hashes;
    }

    private static Path frozenSourceManifestPath() {
        return configuredPath(
                "chatagent.eval.docIngestion.frozenSourceManifestPath",
                FROZEN_SOURCE_MANIFEST_PATH);
    }

    private static Path baselineDatasetPath() {
        return configuredPath(
                "chatagent.eval.docIngestion.baselineDatasetPath",
                BASELINE_DATASET_PATH);
    }

    private static Path configuredPath(String propertyName, Path defaultPath) {
        String configured = System.getProperty(propertyName);
        return StringUtils.hasText(configured) ? Path.of(configured) : defaultPath;
    }

    static void assertPinnedSourceManifest(Path manifestPath) throws Exception {
        assertThat(Files.exists(manifestPath))
                .as("B3.2 accepted-size run requires frozen source manifest at %s; "
                        + "dynamic discovery is not permitted for reproducible recall gate", manifestPath)
                .isTrue();
        assertThat(manifestHash(manifestPath))
                .as("Frozen source manifest must match pinned Phase 10a bytes")
                .isEqualTo(FROZEN_SOURCE_MANIFEST_HASH);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode sources = mapper.readTree(manifestPath.toFile()).path("sources");
        assertThat(sources.isArray())
                .as("Frozen source manifest must contain a sources array")
                .isTrue();
        assertThat(sources.size())
                .as("Frozen source manifest must contain exactly 200 sources")
                .isEqualTo(FROZEN_SOURCE_COUNT);
        Set<String> identities = new LinkedHashSet<>();
        for (JsonNode src : sources) {
            String sourceUrl = src.path("sourceUrl").asText();
            String archiveEntry = src.path("archiveEntry").asText(null);
            if (StringUtils.hasText(archiveEntry)) {
                frozenArchiveDownloadUrl(sourceUrl, archiveEntry);
            }
            String filename = src.path("filename").asText();
            String sha256 = src.path("sha256").asText();
            assertThat(sourceUrl).as("sourceUrl is required for frozen source").isNotBlank();
            assertThat(filename).as("filename is required for frozen source").isNotBlank();
            assertThat(src.path("mimeType").asText()).as("mimeType is required for frozen source").isNotBlank();
            assertThat(src.path("sourceGroup").asText()).as("sourceGroup is required for frozen source").isNotBlank();
            assertThat(src.path("format").asText()).as("format is required for frozen source").isNotBlank();
            assertThat(src.path("license").asText()).as("license is required for frozen source").isNotBlank();
            assertThat(src.path("split").asText()).as("split is required for frozen source").isNotBlank();
            assertThat(sha256).as("sha256 is required for frozen source %s", filename).isNotBlank();
            identities.add(ReferenceRebinder.sourceIdentity(sourceUrl, sha256, filename));
        }
        assertThat(identities)
                .as("Frozen source manifest must have 200 unique stable identities")
                .hasSize(FROZEN_SOURCE_COUNT);
    }

    static String frozenArchiveDownloadUrl(String sourceUrl, String archiveEntry) {
        String suffix = "#" + archiveEntry;
        assertThat(sourceUrl)
                .as("Frozen archive sourceUrl must end with its archiveEntry")
                .endsWith(suffix);
        String archiveUrl = sourceUrl.substring(0, sourceUrl.length() - suffix.length());
        assertThat(archiveUrl)
                .as("Frozen archive download URL is required")
                .isNotBlank();
        return archiveUrl;
    }

    static void assertPinnedBaselineDataset(Path datasetPath) throws Exception {
        assertThat(Files.exists(datasetPath))
                .as("B3.2 accepted-size run requires baseline dataset at %s", datasetPath)
                .isTrue();
        assertThat(manifestHash(datasetPath))
                .as("Baseline dataset must match pinned Phase 10a bytes")
                .isEqualTo(BASELINE_DATASET_HASH);
        List<BaselineDatasetRow> rows = loadBaselineDatasetRows(datasetPath);
        assertThat(rows)
                .as("Baseline dataset must contain exactly 585 frozen rows")
                .hasSize(BASELINE_DATASET_ROW_COUNT);
        assertThat(rows)
                .as("Baseline dataset rows must remain single-reference")
                .allSatisfy(row -> {
                    assertThat(row.referenceContextCount()).isEqualTo(1);
                    assertThat(row.oldReferenceChunkId()).isNotBlank();
                });
    }

    // -----------------------------------------------------------------------
    // B3.2: Chunk inventory, rebinding, and receipt writing
    // -----------------------------------------------------------------------

    /**
     * Builds a deterministic chunk inventory sorted by source identity then chunkIndex.
     */
    private static List<ReferenceRebinder.NewChunk> buildChunkInventory(List<IngestedDocument> docs) {
        List<ReferenceRebinder.NewChunk> inventory = new ArrayList<>();
        for (IngestedDocument doc : docs) {
            for (KnowledgeChunkDTO chunk : doc.chunks) {
                inventory.add(new ReferenceRebinder.NewChunk(
                        doc.source.sourceUrl(),
                        doc.sha256,
                        doc.source.filename(),
                        chunk.getId(),
                        chunk.getChunkIndex(),
                        chunk.getContent()));
            }
        }
        inventory.sort(Comparator
                .comparing(ReferenceRebinder.NewChunk::sourceUrl)
                .thenComparing(ReferenceRebinder.NewChunk::sourceSha256)
                .thenComparing(ReferenceRebinder.NewChunk::filename)
                .thenComparingInt(ReferenceRebinder.NewChunk::chunkIndex));
        return inventory;
    }

    /**
     * Writes the deterministic chunk inventory as a JSON array.
     */
    private void writeChunkInventory(List<ReferenceRebinder.NewChunk> inventory,
                                     List<IngestedDocument> docs,
                                     Path path) throws Exception {
        // Build lookup from (sourceUrl, sourceSha256, filename) → document metadata.
        Map<String, IngestedDocument> docByKey = docs.stream()
                .collect(Collectors.toMap(
                        d -> ReferenceRebinder.sourceIdentity(d.source.sourceUrl(), d.sha256, d.source.filename()),
                        d -> d,
                        (a, b) -> a));
        objectMapper.writeValue(path.toFile(), inventory.stream()
                .map(chunk -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("sourceUrl", chunk.sourceUrl());
                    entry.put("sourceSha256", chunk.sourceSha256());
                    entry.put("filename", chunk.filename());
                    entry.put("chunkId", chunk.chunkId());
                    entry.put("chunkIndex", chunk.chunkIndex());
                    entry.put("contentLength", chunk.content().length());
                    entry.put("contentHash", manifestHashOrThrow(chunk.content().getBytes(StandardCharsets.UTF_8)));
                    String key = ReferenceRebinder.sourceIdentity(chunk.sourceUrl(), chunk.sourceSha256(), chunk.filename());
                    IngestedDocument doc = docByKey.get(key);
                    if (doc != null) {
                        entry.put("parser", parserForSource(doc.source));
                        Map<String, Object> chunkerMeta = new LinkedHashMap<>();
                        addChunkerMetadata(chunkerMeta, doc.source, doc.source.format());
                        entry.put("chunker", chunkerMeta.get("chunker"));
                    }
                    return entry;
                })
                .toList());
    }

    /**
     * Writes exact pre-retrieval chunk content for B3.3 evidence-only generation.
     */
    void writeChunkEvidence(List<ReferenceRebinder.NewChunk> inventory,
                            List<IngestedDocument> docs,
                            Path path) throws Exception {
        Map<String, IngestedDocument> docByKey = docs.stream()
                .collect(Collectors.toMap(
                        d -> ReferenceRebinder.sourceIdentity(d.source.sourceUrl(), d.sha256, d.source.filename()),
                        d -> d,
                        (a, b) -> a));
        objectMapper.writeValue(path.toFile(), inventory.stream()
                .map(chunk -> {
                    String key = ReferenceRebinder.sourceIdentity(chunk.sourceUrl(), chunk.sourceSha256(), chunk.filename());
                    IngestedDocument doc = Objects.requireNonNull(
                            docByKey.get(key), "Chunk evidence must resolve to an ingested source");
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("sourceUrl", chunk.sourceUrl());
                    entry.put("sourceSha256", chunk.sourceSha256());
                    entry.put("filename", chunk.filename());
                    entry.put("sourceGroup", doc.source.sourceGroup());
                    entry.put("format", doc.source.format());
                    entry.put("split", doc.source.split());
                    entry.put("chunkId", chunk.chunkId());
                    entry.put("chunkIndex", chunk.chunkIndex());
                    entry.put("contentLength", chunk.content().length());
                    entry.put("contentHash", manifestHashOrThrow(chunk.content().getBytes(StandardCharsets.UTF_8)));
                    entry.put("content", chunk.content());
                    entry.put("parser", parserForSource(doc.source));
                    Map<String, Object> chunkerMeta = new LinkedHashMap<>();
                    addChunkerMetadata(chunkerMeta, doc.source, doc.source.format());
                    entry.put("chunker", chunkerMeta.get("chunker"));
                    return entry;
                })
                .toList());
    }

    /**
     * Loads baseline dataset rows as narrow RebindInput records for reference rebinding.
     * Only carries source identity + reference content — no query/retrieval/scoring fields.
     */
    static List<BaselineDatasetRow> loadBaselineDatasetRows(Path datasetPath) throws Exception {
        List<BaselineDatasetRow> rows = new ArrayList<>();
        try (var reader = Files.newBufferedReader(datasetPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = new ObjectMapper().readTree(line);
                JsonNode meta = node.path("metadata");
                JsonNode referenceContextIds = node.path("referenceContextIds");
                rows.add(new BaselineDatasetRow(
                        node.path("sampleId").asText(),
                        node.path("sourceGroupId").asText(null),
                        node.path("split").asText(null),
                        node.path("fileId").asText(null),
                        node.path("fileFormat").asText(null),
                        node.path("sourceUrl").asText(null),
                        node.path("userInput").asText(null),
                        meta.path("sourceSha256").asText(null),
                        meta.path("referenceDocFilename").asText(null),
                        meta.path("sourceGroup").asText(null),
                        meta.path("license").asText(null),
                        meta.path("generationMethod").asText(null),
                        referenceContextIds.path(0).asText(null),
                        referenceContextIds.isArray() ? referenceContextIds.size() : 0,
                        meta.path("referenceContent").asText(null),
                        meta.path("referenceAnswer").asText(null)));
            }
        }
        return rows;
    }

    /**
     * Writes the reference-rebind receipt sorted by sampleId, with per-row details
     * and aggregate hashes for the baseline dataset, source manifest, and chunk inventory.
     */
    private void writeRebindReceipt(List<ReferenceRebinder.RebindOutput> results,
                                     List<BaselineDatasetRow> baselineRows,
                                     Path receiptPath,
                                     Path baselinePath,
                                     Path inventoryPath) throws Exception {
        Map<String, Object> receipt = buildRebindReceipt(
                results,
                baselineRows,
                manifestHash(baselinePath),
                manifestHash(frozenSourceManifestPath()),
                manifestHash(inventoryPath));
        objectMapper.writeValue(receiptPath.toFile(), receipt);
    }

    static Map<String, Object> buildRebindReceipt(List<ReferenceRebinder.RebindOutput> results,
                                                   List<BaselineDatasetRow> baselineRows,
                                                   String baselineDatasetHash,
                                                   String sourceManifestHash,
                                                   String chunkInventoryHash) {
        long bound = results.stream().filter(r -> "bound".equals(r.status())).count();
        long missing = results.stream().filter(r -> "missing".equals(r.status())).count();

        Map<String, BaselineDatasetRow> rowBySampleId = baselineRows.stream()
                .collect(Collectors.toMap(BaselineDatasetRow::sampleId, r -> r));

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("baselineDatasetHash", baselineDatasetHash);
        receipt.put("sourceManifestHash", sourceManifestHash);
        receipt.put("chunkInventoryHash", chunkInventoryHash);
        receipt.put("totalRows", results.size());
        receipt.put("bound", bound);
        receipt.put("missing", missing);
        receipt.put("rows", results.stream()
                .sorted(Comparator.comparing(ReferenceRebinder.RebindOutput::sampleId))
                .map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sampleId", r.sampleId());
            row.put("status", r.status());
            row.put("sourceIdentity", r.sourceIdentity());
            BaselineDatasetRow input = rowBySampleId.get(r.sampleId());
            if (input != null) {
                row.put("split", input.split());
                row.put("format", input.fileFormat());
                String contentHash = input.referenceContent() != null && !input.referenceContent().isEmpty()
                        ? manifestHashOrThrow(input.referenceContent().getBytes(StandardCharsets.UTF_8))
                        : "sha256:empty";
                row.put("oldContentHash", contentHash);
            }
            row.put("oldReferenceChunkId", r.oldReferenceChunkId());
            row.put("newReferenceChunkId", r.newReferenceChunkId());
            row.put("auditWindowChunkIds", r.auditWindowChunkIds());
            row.put("windowLength", r.windowLength());
            row.put("matchMethod", r.matchMethod());
            row.put("matchCoverage", r.matchCoverage());
            row.put("tieBreak", r.tieBreak());
            return row;
        }).toList());
        return receipt;
    }

    /**
     * Asserts the B3.2 rebind gate: all rows must be bound and no rows may be missing.
     */
    private void assertRebindGate(List<ReferenceRebinder.RebindOutput> results) {
        long missing = results.stream().filter(r -> "missing".equals(r.status())).count();
        assertThat(missing)
                .as("B3.2 rebind gate: no rows should be missing (content not found in new chunks)")
                .isZero();
        assertThat(results.stream().filter(r -> "bound".equals(r.status())).count())
                .as("B3.2 rebind gate: all rows must be bound")
                .isEqualTo((long) results.size());
    }

    /**
     * Builds GroundedQuery list from baseline dataset rows and rebind outputs.
     * Each query uses the rebound primary chunk as referenceChunkId.
     */
    static List<GroundedQuery> buildReboundQueries(
            List<BaselineDatasetRow> baselineRows,
            List<ReferenceRebinder.RebindOutput> rebindResults) {
        Map<String, ReferenceRebinder.RebindOutput> bySampleId = rebindResults.stream()
                .collect(Collectors.toMap(ReferenceRebinder.RebindOutput::sampleId, r -> r));
        List<GroundedQuery> queries = new ArrayList<>();
        for (BaselineDatasetRow row : baselineRows) {
            ReferenceRebinder.RebindOutput rebind = bySampleId.get(row.sampleId());
            assertThat(rebind)
                    .as("Every baseline row must have a rebind output: sampleId=%s", row.sampleId())
                    .isNotNull();
            assertThat(rebind.status())
                    .as("Rebind must be bound for sampleId=%s (gate passed above)", row.sampleId())
                    .isEqualTo("bound");
            queries.add(new GroundedQuery(
                    row.userInput(),
                    rebind.newReferenceChunkId(),
                    row.fileId(),
                    row.referenceDocFilename(),
                    row.fileFormat(),
                    row.referenceContent(),
                    row.generationMethod(),
                    null,
                    null,
                    row.split(),
                    row.fileFormat(),
                    row.sampleId(),
                    "baseline-v1",
                    row.referenceAnswer(),
                    row.sampleId(),
                    row.sourceGroupId(),
                    row.fileId(),
                    row.sourceUrl(),
                    row.sourceSha256(),
                    row.sourceGroup(),
                    row.license()));
        }
        return queries;
    }

    /**
     * Runs retrieval for each query, returning QueryResult list.
     */
    private List<QueryResult> performRetrieval(List<GroundedQuery> allQueries, String kbId) throws Exception {
        List<QueryResult> results = new ArrayList<>();
        for (GroundedQuery query : allQueries) {
            long candidateStarted = System.nanoTime();
            List<RankedCandidateHit> candidates = kbSearcher.searchRankedCandidateHitsByKnowledgeBaseIds(
                    List.of(kbId), query.queryText());
            long candidatePathMs = (System.nanoTime() - candidateStarted) / 1_000_000;
            long finalStarted = System.nanoTime();
            List<RetrievalHit> finalHits = kbSearcher.searchByKnowledgeBaseIds(List.of(kbId), query.queryText());
            long finalPathMs = (System.nanoTime() - finalStarted) / 1_000_000;
            results.add(new QueryResult(
                    query,
                    candidates.stream().limit(CANDIDATE_K).toList(),
                    finalHits,
                    candidatePathMs,
                    finalPathMs
            ));
        }
        return results;
    }

    /**
     * Writes the B3.2 export manifest recording receipt hash and final dataset hash.
     */
    void writeB3ExportManifest(Path receiptPath,
                               Path inventoryPath,
                               Path chunkEvidencePath,
                               Path artifactsDir) throws Exception {
        String receiptHash = manifestHash(receiptPath);
        Path datasetPath = artifactsDir.resolve(Path.of("datasets", "doc-ingestion", DATASET_ID + ".jsonl"));
        Path sourceManifestPath = artifactsDir.resolve("source-manifest.json");
        assertThat(Files.exists(datasetPath))
                .as("B3.2 export manifest requires final dataset at %s", datasetPath)
                .isTrue();
        assertThat(Files.exists(sourceManifestPath))
                .as("B3.2 export manifest requires source manifest at %s", sourceManifestPath)
                .isTrue();
        String datasetHash = manifestHash(datasetPath);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", RUN_ID);
        manifest.put("phase", "B3.2");
        manifest.put("rebindReceiptHash", receiptHash);
        manifest.put("datasetHash", datasetHash);
        manifest.put("sourceManifestHash", manifestHash(sourceManifestPath));
        manifest.put("chunkInventoryHash", manifestHash(inventoryPath));
        manifest.put("chunkEvidenceHash", manifestHash(chunkEvidencePath));
        manifest.put("timestamp", LocalDateTime.now().toString());
        Path manifestPath = artifactsDir.resolve("b3-export-manifest.json");
        objectMapper.writeValue(manifestPath.toFile(), manifest);
    }

    private static List<SourceFile> discoverSecFilings(int target) throws Exception {
        List<String> ciks = List.of(
                "0000320193", "0000789019", "0001018724", "0001652044", "0001326801",
                "0001318605", "0001045810", "0000104169", "0000034088", "0000019617",
                "0001067983", "0000731766", "0000200406", "0001403161", "0000080424",
                "0001141391", "0000093410", "0000885725", "0000021344", "0000077476"
        );
        List<SourceFile> sources = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String paddedCik : ciks) {
            if (sources.size() >= target) {
                break;
            }
            String submissionsUrl = "https://data.sec.gov/submissions/CIK" + paddedCik + ".json";
            JsonNode root = mapper.readTree(downloadDirectFile(submissionsUrl, 8_000_000));
            JsonNode recent = root.path("filings").path("recent");
            JsonNode forms = recent.path("form");
            JsonNode accessionNumbers = recent.path("accessionNumber");
            JsonNode primaryDocuments = recent.path("primaryDocument");
            JsonNode filingDates = recent.path("filingDate");
            for (int i = 0; i < forms.size() && sources.size() < target; i++) {
                String form = forms.get(i).asText("");
                String primaryDocument = primaryDocuments.get(i).asText("");
                if (!form.startsWith("8-K") || !(primaryDocument.endsWith(".htm") || primaryDocument.endsWith(".html"))) {
                    continue;
                }
                String accession = accessionNumbers.get(i).asText("");
                String cikNoLeadingZero = paddedCik.replaceFirst("^0+", "");
                String url = "https://www.sec.gov/Archives/edgar/data/"
                        + cikNoLeadingZero + "/" + accession.replace("-", "") + "/" + primaryDocument;
                String filename = sanitizeFilename("sec-" + cikNoLeadingZero + "-" + accession + "-" + primaryDocument);
                int index = sources.size();
                sources.add(direct(url, filename, "text/html", "sec-edgar", "SEC_HTML",
                        "Public SEC EDGAR company disclosure (" + form + ", " + filingDates.get(i).asText("") + ")",
                        splitFor(index)));
            }
            Thread.sleep(120L);
        }
        return sources;
    }

    private static List<SourceFile> pdfSources(int target) {
        List<SourceFile> sources = new ArrayList<>();
        List<String> whoUrls = List.of(
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-boys-z-0-13.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-boys-z-0-5.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-boys-p-0-13.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-boys-p-0-5.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-girls-z-0-13.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-girls-z-0-5.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-girls-p-0-13.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/sft-wfa-girls-p-0-5.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/sft-wfl-boys-z-0-2.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/sft-wfl-boys-p-0-2.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/sft-wfl-girls-z-0-2.pdf",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/sft-wfl-girls-p-0-2.pdf"
        );
        for (String url : whoUrls) {
            if (sources.size() >= target) {
                break;
            }
            int index = sources.size();
            sources.add(direct(url, filenameFromUrl(url), "application/pdf", "who-child-growth", "PDF",
                    "CC-BY-3.0-IGO (WHO Child Growth Standards)", splitFor(index)));
        }
        List<String> irsIds = List.of("p1", "p3", "p15", "p17", "p54", "p225", "p334", "p463",
                "p501", "p502", "p503", "p504", "p505", "p509", "p514", "p515");
        for (String id : irsIds) {
            if (sources.size() >= target) {
                break;
            }
            String url = "https://www.irs.gov/pub/irs-pdf/" + id + ".pdf";
            int index = sources.size();
            sources.add(direct(url, "irs-" + id + ".pdf", "application/pdf", "irs-publications", "PDF",
                    "Public domain (U.S. Internal Revenue Service)", splitFor(index)));
        }
        return sources;
    }

    private static List<SourceFile> discoverCmsOfficeSources(String extension,
                                                             String format,
                                                             String mimeType,
                                                             int target) throws Exception {
        List<SourceFile> sources = new ArrayList<>();
        List<String> pages = List.of(
                "https://www.cms.gov/medicare/prescription-drug-coverage/prescriptiondrugcovcontra/part-d-model-materials",
                "https://www.cms.gov/medicare/medicaid-coordination/about/dsnps",
                "https://www.cms.gov/medicare/coverage/summary-notice",
                "https://www.cms.gov/medicare/coverage/determination-process/local",
                "https://www.cms.gov/CCIIO/Resources/Data-Resources/QHP-Choice-Premiums.html",
                "https://www.cms.gov/marketplace/about/health-insurance-quality-initiatives/downloads",
                "https://www.cms.gov/about-cms/work-us/business-resources/contract-opportunities-0"
        );
        LinkedHashSet<String> directLinks = new LinkedHashSet<>();
        LinkedHashSet<String> zipLinks = new LinkedHashSet<>();
        for (String page : pages) {
            String html;
            try {
                html = new String(downloadDirectFile(page, 6_000_000), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                continue;
            }
            for (String link : hrefs(page, html)) {
                String lower = link.toLowerCase(Locale.ROOT);
                if (lower.endsWith(extension)) {
                    directLinks.add(link);
                } else if (lower.endsWith(".zip")) {
                    zipLinks.add(link);
                }
            }
        }
        for (String url : directLinks) {
            if (sources.size() >= target) {
                return sources;
            }
            int index = sources.size();
            sources.add(direct(url, filenameFromUrl(url), mimeType, "cms-public", format,
                    "Public CMS downloadable file", splitFor(index)));
        }
        for (String zipUrl : zipLinks) {
            if (sources.size() >= target) {
                break;
            }
            for (String entry : zipEntries(zipUrl, extension)) {
                if (sources.size() >= target) {
                    break;
                }
                int index = sources.size();
                sources.add(archive(zipUrl, entry, sanitizeFilename(filenameFromUrl(entry)), mimeType, "cms-public", format,
                        "Public CMS downloadable ZIP entry", splitFor(index)));
            }
        }
        return sources;
    }

    private static List<SourceFile> whoXlsxSources() {
        List<String> urls = List.of(
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/expanded-tables/wfa-girls-zscore-expanded-tables.xlsx?sfvrsn=f01bc813_10",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/expanded-tables/wfa-girls-percentiles-expanded-tables.xlsx?sfvrsn=54cfa5e8_9",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/expanded-tables/wfa-boys-zscore-expanded-tables.xlsx?sfvrsn=65cce121_10",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-age/expanded-tables/wfa-boys-percentiles-expanded-tables.xlsx?sfvrsn=c2f79259_11",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/length-height-for-age/expandable-tables/lhfa-girls-zscore-expanded-tables.xlsx?sfvrsn=27f1e2cb_10",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/length-height-for-age/expandable-tables/lhfa-girls-percentiles-expanded-tables.xlsx?sfvrsn=478569a5_9",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/length-height-for-age/expandable-tables/lhfa-boys-zscore-expanded-tables.xlsx?sfvrsn=7b4a3428_12",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/length-height-for-age/expandable-tables/lhfa-boys-percentiles-expanded-tables.xlsx?sfvrsn=bc36d818_9",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/expanded-tables/wfl-girls-zscore-expanded-table.xlsx",
                "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/weight-for-length-height/expanded-tables/wfl-boys-zscore-expanded-table.xlsx"
        );
        List<SourceFile> sources = new ArrayList<>();
        for (String url : urls) {
            int index = sources.size();
            sources.add(direct(url, filenameFromUrl(url),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "who-child-growth", "XLSX", "CC-BY-3.0-IGO (WHO Child Growth Standards)", splitFor(index)));
        }
        return sources;
    }

    private static List<SourceFile> webMarkdownSources(int target) {
        List<SourceFile> sources = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (int rfc : List.of(9110, 9111, 9112, 9113, 9114, 9115, 7540, 7230, 7231, 7232, 7233, 7234, 7235,
                3986, 6455, 8446, 9000, 9001, 9002, 9204, 9457, 8259, 8941, 8288, 8289, 8290, 8890, 8615, 9116,
                9277, 9308, 9334, 9335, 9336, 9337, 9338, 9339, 9440, 9441, 9442)) {
            urls.add("https://www.rfc-editor.org/rfc/rfc" + rfc + ".html");
        }
        urls.addAll(List.of(
                "https://raw.githubusercontent.com/OAI/OpenAPI-Specification/main/README.md",
                "https://raw.githubusercontent.com/asyncapi/spec/v2.6.0/spec/asyncapi.md",
                "https://raw.githubusercontent.com/json-schema-org/json-schema-spec/main/README.md",
                "https://raw.githubusercontent.com/graphql/graphql-spec/main/README.md",
                "https://raw.githubusercontent.com/kubernetes/community/master/contributors/guide/README.md",
                "https://raw.githubusercontent.com/cncf/toc/main/README.md",
                "https://www.w3.org/TR/WCAG22/",
                "https://www.w3.org/TR/png-3/",
                "https://www.w3.org/TR/css-color-4/",
                "https://www.w3.org/TR/webgpu/",
                "https://www.w3.org/TR/webauthn-3/",
                "https://www.w3.org/TR/activitypub/"
        ));
        for (String url : urls) {
            if (sources.size() >= target) {
                break;
            }
            int index = sources.size();
            String filename = filenameFromUrl(url);
            if (!filename.contains(".")) {
                filename = sanitizeFilename(filename) + ".html";
            }
            String mime = filename.endsWith(".md") ? "text/markdown" : "text/html";
            sources.add(direct(url, filename, mime, "web-standards", "WEB_MD",
                    "Public standards or open-source documentation", splitFor(index)));
        }
        return sources;
    }

    private static List<String> hrefs(String baseUrl, String html) {
        List<String> links = new ArrayList<>();
        Matcher matcher = HREF_PATTERN.matcher(html);
        URI base = URI.create(baseUrl);
        while (matcher.find()) {
            try {
                links.add(base.resolve(matcher.group(1)).toString().replace("&amp;", "&"));
            } catch (Exception ignored) {
            }
        }
        return links;
    }

    private static List<String> zipEntries(String zipUrl, String extension) {
        try {
            byte[] zipBytes = downloadDirectFile(zipUrl, 60_000_000);
            List<String> entries = new ArrayList<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(extension)) {
                        entries.add(entry.getName());
                    }
                }
            }
            entries.sort(String::compareTo);
            return entries;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static DownloadedFile downloadSource(SourceFile source, Map<String, byte[]> archiveCache) throws Exception {
        byte[] bytes;
        if (StringUtils.hasText(source.archiveEntry())) {
            byte[] zipBytes = archiveCache.computeIfAbsent(source.url(), url -> {
                try {
                    return downloadDirectFile(url, 60_000_000);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            bytes = readZipEntry(zipBytes, source.archiveEntry());
        } else {
            bytes = downloadDirectFile(source.url(), 30_000_000);
        }
        if (bytes.length == 0) {
            throw new IOException("Downloaded file is empty: " + source.sourceUrl());
        }
        if (bytes.length > 30_000_000) {
            throw new IOException("Downloaded file exceeds 30MB upload limit: " + source.sourceUrl());
        }
        return new DownloadedFile(bytes, Instant.now().toString());
    }

    private static byte[] readZipEntry(byte[] zipBytes, String entryName) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals(entryName)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zis.transferTo(out);
                    return out.toByteArray();
                }
            }
        }
        throw new IOException("ZIP entry not found: " + entryName);
    }

    private static byte[] downloadDirectFile(String url, int maxBytes) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", userAgent())
                .GET();
        HttpResponse<byte[]> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed: " + url + " HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length > maxBytes) {
            throw new IOException("Download exceeded max bytes: " + url + " size=" + body.length);
        }
        return body;
    }

    private MineruTrace mineruTrace(SourceFile source, List<KnowledgeChunkDTO> chunks) {
        boolean enabled = "PDF".equals(source.format());
        int visualCount = 0;
        String engineId = null;
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        for (KnowledgeChunkDTO chunk : chunks) {
            Map<String, Object> metadata = parseMetadata(chunk.getMetadata());
            if (metadata.isEmpty()) {
                continue;
            }
            Object contentOrigin = metadata.get("contentOrigin");
            Object vdpStatus = metadata.get("vdpStatus");
            Object metadataEngine = metadata.get("engineId");
            Object pageRoute = metadata.get("pageRoute");
            if (metadataEngine != null) {
                engineId = metadataEngine.toString();
                evidence.add("engineId");
            }
            if (vdpStatus != null) {
                evidence.add("vdpStatus");
            }
            if (contentOrigin != null) {
                evidence.add("contentOrigin");
            }
            if (pageRoute != null) {
                evidence.add("pageRoute");
            }
            boolean visual = "VDP_TRANSCRIBED".equals(contentOrigin)
                    || vdpStatus != null
                    || "mineru".equalsIgnoreCase(String.valueOf(metadataEngine));
            if (visual) {
                visualCount++;
            }
        }
        return new MineruTrace(enabled, visualCount > 0, engineId, visualCount, List.copyOf(evidence));
    }

    private Map<String, Object> parseMetadata(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(metadata, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void assertAcceptedSourceCoverage(Map<String, Integer> successByFormat,
                                              Map<String, Integer> targets,
                                              List<DocumentFailure> failures) {
        for (Map.Entry<String, Integer> target : targets.entrySet()) {
            assertThat(successByFormat.getOrDefault(target.getKey(), 0))
                    .as("Accepted-size run needs %d successful %s documents; failures=%s",
                            target.getValue(), target.getKey(), failures.stream().limit(5).map(DocumentFailure::error).toList())
                    .isGreaterThanOrEqualTo(target.getValue());
        }
        assertThat(successByFormat.values().stream().mapToInt(Integer::intValue).sum()).isGreaterThanOrEqualTo(200);
    }

    private void assertAcceptedMineruCoverage(List<IngestedDocument> ingestedDocs) {
        long pdfCount = ingestedDocs.stream()
                .filter(doc -> "PDF".equals(doc.source().format()))
                .count();
        long mineruSelected = ingestedDocs.stream()
                .filter(doc -> "PDF".equals(doc.source().format()) && doc.mineru().selected())
                .count();
        assertThat(pdfCount).as("Accepted-size run should include PDF documents").isGreaterThan(0);
        assertThat(mineruSelected)
                .as("Accepted-size PDF coverage must prove MinerU/VDP visual routing on at least one real PDF")
                .isGreaterThan(0);
    }

    static void assertB3EvidenceExportGate(List<IngestedDocument> ingestedDocs) {
        List<IngestedDocument> htmlBacked = ingestedDocs.stream()
                .filter(doc -> {
                    String filename = doc.source().filename().toLowerCase(Locale.ROOT);
                    return filename.endsWith(".html") || filename.endsWith(".htm");
                })
                .toList();
        assertThat(htmlBacked)
                .as("B3.2 evidence-export gate must include HTML-backed documents")
                .isNotEmpty();
        assertThat(htmlBacked)
                .as("B3.2 HTML-backed documents must route through HtmlDocumentParser and "
                        + "StructureAwareMarkdownChunker and produce at least one chunk")
                .allSatisfy(doc -> {
                    assertThat(parserForSource(doc.source())).isEqualTo("HtmlDocumentParser");
                    Map<String, Object> chunkerMetadata = new LinkedHashMap<>();
                    addChunkerMetadata(chunkerMetadata, doc.source(), doc.source().format());
                    assertThat(chunkerMetadata.get("chunker")).isEqualTo("StructureAwareMarkdownChunker");
                    assertThat(doc.chunks()).isNotEmpty();
                });
    }

    private void assertAcceptedQueryCoverage(List<GroundedQuery> queries) {
        assertThat(queries).as("Accepted-size 10a must produce at least 500 grounded recall rows")
                .hasSizeGreaterThanOrEqualTo(500);
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.generationMethod())
                    .isIn("llm-generated-llm-reviewed-v1", "codex-manual-assisted-llm-reviewed-v1");
            assertThat(query.auditStatus()).isEqualTo("llm-reviewed");
            assertThat(query.llmProvenance()).isNotNull();
            assertThat(query.llmProvenance().reviewerModel()).isNotBlank();
            assertThat(query.llmProvenance().reviewerTool()).isNotBlank();
            assertThat(query.llmProvenance().reviewerPromptVersion()).isNotBlank();
            assertThat(query.sourceSha256()).isNotBlank();
        });
        Map<String, Long> perFormat = queries.stream()
                .collect(Collectors.groupingBy(GroundedQuery::format, Collectors.counting()));
        for (String format : ACCEPTED_FORMAT_TARGETS.keySet()) {
            assertThat(perFormat.getOrDefault(format, 0L))
                    .as("Accepted-size 10a must produce at least 50 queries for format %s", format)
                    .isGreaterThanOrEqualTo(50L);
        }
        Map<String, Map<String, Long>> splitsByFormat = queries.stream()
                .collect(Collectors.groupingBy(
                        GroundedQuery::format,
                        Collectors.groupingBy(GroundedQuery::split, Collectors.counting())
                ));
        for (String format : ACCEPTED_FORMAT_TARGETS.keySet()) {
            Map<String, Long> counts = splitsByFormat.getOrDefault(format, Map.of());
            assertThat(counts.getOrDefault("calibration", 0L))
                    .as("%s needs at least 20 calibration questions", format).isGreaterThanOrEqualTo(20L);
            assertThat(counts.getOrDefault("development", 0L))
                    .as("%s needs at least 10 development questions", format).isGreaterThanOrEqualTo(10L);
            assertThat(counts.getOrDefault("holdout", 0L))
                    .as("%s needs at least 10 holdout questions", format).isGreaterThanOrEqualTo(10L);
        }
    }

    private boolean targetsSatisfied(Map<String, Integer> successByFormat, Map<String, Integer> targets) {
        return targets.entrySet().stream()
                .allMatch(entry -> successByFormat.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private Map<String, Integer> smokeTargets() {
        return SMOKE_CATALOG.stream()
                .collect(Collectors.toMap(SourceFile::format, ignored -> 1, Integer::sum, LinkedHashMap::new));
    }

    private static boolean acceptedSizeRun() {
        return Boolean.parseBoolean(System.getProperty("chatagent.eval.docIngestion.acceptedSize", "false"));
    }

    private static boolean b3BaselineReplayRun() {
        return Boolean.parseBoolean(System.getProperty("chatagent.eval.docIngestion.b3BaselineReplay", "false"));
    }

    private static Path outputRoot() {
        String configured = System.getProperty("chatagent.eval.docIngestion.outputRoot");
        if (StringUtils.hasText(configured)) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("..", "artifacts", "eval", "phase10a").toAbsolutePath().normalize();
    }

    private String evalUserId() {
        if (evalUserId == null) {
            List<UserDTO> users = userRepository.findPage(null, "ACTIVE", 1, 0);
            assertThat(users).as("Need at least one active t_user row for eval knowledge-base FK").isNotEmpty();
            evalUserId = users.get(0).getId();
        }
        return evalUserId;
    }

    private static SourceFile direct(String url,
                                     String filename,
                                     String mimeType,
                                     String sourceGroup,
                                     String format,
                                     String license,
                                     String split) {
        return new SourceFile(url, null, filename, mimeType, sourceGroup, format, license, split, null);
    }

    private static SourceFile archive(String archiveUrl,
                                       String archiveEntry,
                                       String filename,
                                       String mimeType,
                                       String sourceGroup,
                                       String format,
                                       String license,
                                       String split) {
        return new SourceFile(archiveUrl, archiveEntry, filename, mimeType, sourceGroup, format, license, split, null);
    }

    private static String splitFor(int index) {
        int mod = Math.floorMod(index, 4);
        return switch (mod) {
            case 0, 1 -> "calibration";
            case 2 -> "development";
            default -> "holdout";
        };
    }

    private static String filenameFromUrl(String url) {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            path = url;
        }
        String filename = path == null || path.isBlank() ? "document" : path.substring(path.lastIndexOf('/') + 1);
        filename = filename.substring(filename.lastIndexOf('\\') + 1);
        return sanitizeFilename(StringUtils.hasText(filename) ? filename : "document");
    }

    private static String sanitizeFilename(String filename) {
        String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }

    private static String manifestHash(byte[] data) throws Exception {
        return "sha256:" + sha256Hex(data);
    }

    private static String manifestHash(Path path) throws Exception {
        return manifestHash(Files.readAllBytes(path));
    }

    private static String manifestHashOrThrow(byte[] data) {
        try {
            return manifestHash(data);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute required SHA-256 audit hash", e);
        }
    }

    private static String manifestHash(Set<String> items) {
        String joined = String.join(",", items.stream().sorted().toList());
        return manifestHashOrThrow(joined.getBytes(StandardCharsets.UTF_8));
    }

    static boolean probeAll() {
        return probePostgres()
                && probeRedis()
                && probeRabbit()
                && probeOllama()
                && probeMilvus()
                && probeMinerU();
    }

    private static boolean probeOllama() {
        try {
            String baseUrl = configValue("chatagent.eval.ollamaBaseUrl", "CHATAGENT_RAG_EMBEDDING_BASE_URL", "http://127.0.0.1:11434");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeMinerU() {
        try {
            String baseUrl = configValue("chatagent.eval.mineruBaseUrl", "CHATAGENT_RAG_VDP_MINERU_BASE_URL", "http://127.0.0.1:8000");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeMilvus() {
        String host = configValue("chatagent.eval.milvusHost", "CHATAGENT_MILVUS_HOST", "localhost");
        int port = Integer.parseInt(configValue("chatagent.eval.milvusPort", "CHATAGENT_MILVUS_PORT", "19530"));
        return tcpProbe(host, port);
    }

    private static boolean probeRabbit() {
        String host = configValue("chatagent.eval.rabbitHost", "SPRING_RABBITMQ_HOST", "localhost");
        int port = Integer.parseInt(configValue("chatagent.eval.rabbitPort", "SPRING_RABBITMQ_PORT", "5672"));
        return tcpProbe(host, port);
    }

    private static boolean probeRedis() {
        String host = configValue("chatagent.eval.redisHost", "SPRING_DATA_REDIS_HOST", "localhost");
        int port = Integer.parseInt(configValue("chatagent.eval.redisPort", "SPRING_DATA_REDIS_PORT", "6379"));
        return tcpProbe(host, port);
    }

    private static boolean probePostgres() {
        String dbUrl = configValue("chatagent.eval.dbUrl", "CHATAGENT_DB_URL", "jdbc:postgresql://localhost:5432/chatagent");
        String host = "localhost";
        int port = 5432;
        try {
            int hostStart = dbUrl.indexOf("//") + 2;
            int hostEnd = dbUrl.indexOf(":", hostStart);
            int portEnd = dbUrl.indexOf("/", hostEnd);
            host = dbUrl.substring(hostStart, hostEnd > hostStart ? hostEnd : dbUrl.length());
            if (hostEnd > hostStart && portEnd > hostEnd) {
                port = Integer.parseInt(dbUrl.substring(hostEnd + 1, portEnd));
            }
        } catch (Exception ignored) {
        }
        return tcpProbe(host, port);
    }

    private static boolean tcpProbe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String configValue(String propertyName, String envName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (StringUtils.hasText(propertyValue)) {
            return propertyValue;
        }
        String envValue = System.getenv(envName);
        if (StringUtils.hasText(envValue)) {
            return envValue;
        }
        return defaultValue;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private static String userAgent() {
        return configValue("chatagent.eval.secUserAgent", "CHATAGENT_EVAL_SEC_USER_AGENT", "ChatAgentEval/1.0 contact@example.invalid");
    }

    private static String parserForSource(SourceFile source) {
        String filename = source.filename().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".pdf")) {
            return "PdfDocumentParser";
        }
        if (filename.endsWith(".docx") || filename.endsWith(".doc")) {
            return "WordDocumentParser";
        }
        if (filename.endsWith(".xlsx")) {
            return "SpreadsheetDocumentParser";
        }
        if (filename.endsWith(".html") || filename.endsWith(".htm")) {
            return "HtmlDocumentParser";
        }
        if (filename.endsWith(".md") || filename.endsWith(".markdown")) {
            return "MarkdownDocumentParser";
        }
        return "TikaDocumentParser";
    }

    private static String parserForFormat(String format) {
        return switch (format) {
            case "PDF" -> "PdfDocumentParser";
            case "DOCX" -> "WordDocumentParser";
            case "XLSX" -> "SpreadsheetDocumentParser";
            case "SEC_HTML" -> "HtmlDocumentParser";
            default -> "TikaDocumentParser";
        };
    }

    private static void addChunkerMetadata(Map<String, Object> metadata, SourceFile source, String format) {
        String filename = source == null ? "" : source.filename().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".md") || filename.endsWith(".markdown")) {
            metadata.put("chunker", "StructureAwareMarkdownChunker");
            metadata.put("chunkerTargetChars", 1400);
            metadata.put("chunkerOverlapChars", 0);
            return;
        }
        if (filename.endsWith(".html") || filename.endsWith(".htm")) {
            metadata.put("chunker", "StructureAwareMarkdownChunker");
            metadata.put("chunkerTargetChars", 1400);
            metadata.put("chunkerOverlapChars", 0);
            return;
        }
        if ("PDF".equals(format)) {
            metadata.put("chunker", "SegmentAwareChunkerRouter");
            metadata.put("chunkerTargetChars", 1200);
            metadata.put("chunkerOverlapChars", 0);
            return;
        }
        if ("XLSX".equals(format)) {
            metadata.put("chunker", "TableAwareChunker");
            metadata.put("chunkerMaxRowsPerChunk", 50);
            metadata.put("chunkerOverlapRows", 2);
            return;
        }
        metadata.put("chunker", "PlainTextChunker");
        metadata.put("chunkerTargetChars", 1200);
        metadata.put("chunkerOverlapChars", 150);
    }

    record SourceFile(String url,
                      String archiveEntry,
                      String filename,
                      String mimeType,
                      String sourceGroup,
                      String format,
                      String license,
                      String split,
                      String expectedSha256) {
        String sourceUrl() {
            return StringUtils.hasText(archiveEntry) ? url + "#" + archiveEntry : url;
        }

        SourceFile withExpectedSha256(String sha256) {
            return new SourceFile(url, archiveEntry, filename, mimeType, sourceGroup, format, license, split, sha256);
        }
    }

    record DownloadedFile(byte[] bytes, String downloadedAt) {
    }

    record IngestedDocument(SourceFile source,
                            String docId,
                            String sha256,
                            int sizeBytes,
                            String downloadedAt,
                            List<KnowledgeChunkDTO> chunks,
                            MqTrace mqTrace,
                            MineruTrace mineru,
                            boolean milvusEvidence) {
    }

    record DocumentFailure(SourceFile source, String error) {
    }

    record MqTrace(String eventId,
                   String idempotencyKey,
                   String outboxStatus,
                   boolean consumerCompleted,
                   String parseStatus,
                   LocalDateTime createdAt) {
    }

    record MineruTrace(boolean enabled,
                       boolean selected,
                       String engineId,
                       int visualChunkCount,
                       List<String> evidenceFields) {
    }

    record GroundedQuery(String queryText,
                         String referenceChunkId,
                         String referenceDocId,
                         String referenceDocFilename,
                         String format,
                         String referenceContent,
                         String generationMethod,
                         String auditStatus,
                         LlmProvenance llmProvenance,
                         String split,
                         String sourceHint,
                         String questionId,
                         String questionSetVersion,
                         String referenceAnswer,
                         String sampleId,
                         String sourceGroupId,
                         String fileId,
                         String sourceUrl,
                         String sourceSha256,
                         String sourceGroup,
                         String license) {
    }

    record BaselineDatasetRow(String sampleId,
                              String sourceGroupId,
                              String split,
                              String fileId,
                              String fileFormat,
                              String sourceUrl,
                              String userInput,
                              String sourceSha256,
                              String referenceDocFilename,
                              String sourceGroup,
                              String license,
                              String generationMethod,
                              String oldReferenceChunkId,
                              int referenceContextCount,
                              String referenceContent,
                              String referenceAnswer) {
        ReferenceRebinder.RebindInput toRebindInput() {
            return new ReferenceRebinder.RebindInput(
                    sampleId,
                    sourceUrl,
                    sourceSha256,
                    referenceDocFilename,
                    fileFormat,
                    split,
                    oldReferenceChunkId,
                    referenceContent);
        }
    }

    record ManualQuestionSet(String version, List<ManualQuestion> questions) {
    }

    record ManualQuestion(String id,
                          String evidenceId,
                          String filename,
                          String sourceSha256,
                          String format,
                          String split,
                          Integer chunkIndex,
                          String referenceChunkId,
                          String question,
                          String referenceNeedle,
                          String referenceAnswer,
                          String generationMethod,
                          String auditStatus,
                          LlmProvenance llmProvenance) {
    }

    record LlmProvenance(String generatorModel,
                         String generatorTool,
                         String reviewerModel,
                         String reviewerTool,
                         String reviewerPromptVersion) {
    }

    record AcceptedQuestionRuntime(ManualQuestionSet questionSet,
                                   AcceptedQuestionSetContract.ValidatedQuestionSet validation,
                                   String baseLocation,
                                   String manifestLocation,
                                   String overlayLocation) {
    }

    record QueryResult(GroundedQuery query,
                       List<RankedCandidateHit> candidates,
                       List<RetrievalHit> finalHits,
                       long candidatePathMs,
                       long finalPathMs) {
    }

    record CandidateMatch(int candidateRank, MilvusSearchHit hit) {
    }
}
