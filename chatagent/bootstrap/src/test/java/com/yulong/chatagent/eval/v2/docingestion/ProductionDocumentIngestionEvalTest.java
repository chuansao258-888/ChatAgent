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
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher.RankedCandidateHit;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                "chatagent.mq.dispatchers.agent-run-enabled=false"
        }
)
@ActiveProfiles("eval-real-doc-ingestion")
@Tag("eval-v2")
@Tag("eval-real")
@ExtendWith(ProductionDocumentIngestionInfrastructureCondition.class)
class ProductionDocumentIngestionEvalTest {

    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);
    private static final String DATASET_ID = "doc-ingestion-retrieval-v1";
    private static final int TOP_K = 3;
    private static final int CANDIDATE_K = 12;
    private static final Duration DOCUMENT_TIMEOUT = Duration.ofSeconds(
            Long.getLong("chatagent.eval.docIngestion.documentTimeoutSeconds", 300L));
    private static final Pattern HREF_PATTERN = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
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
    private KnowledgeBaseMilvusIndexer milvusIndexer;

    @Autowired
    private UserRepository userRepository;

    private final List<String> evalKbIds = new ArrayList<>();
    private final List<String> evalDocIds = new ArrayList<>();
    private final List<String> evalStoragePaths = new ArrayList<>();
    private String evalUserId;

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
        } else {
            assertThat(ingestedDocs).hasSizeGreaterThanOrEqualTo(5);
        }

        List<GroundedQuery> directQueries = generateDirectEvidenceQueries(ingestedDocs, directQueriesPerDocument(acceptedSize));
        List<GroundedQuery> templateQueries = generateTemplateQueries(ingestedDocs);
        List<GroundedQuery> allQueries = new ArrayList<>(directQueries);
        allQueries.addAll(templateQueries);

        if (acceptedSize) {
            assertAcceptedQueryCoverage(allQueries);
        } else {
            assertThat(allQueries).hasSizeGreaterThanOrEqualTo(15);
        }

        List<QueryResult> results = new ArrayList<>();
        for (GroundedQuery query : allQueries) {
            List<RankedCandidateHit> candidates = kbSearcher.searchRankedCandidateHitsByKnowledgeBaseIds(
                    List.of(kbId), query.queryText());
            results.add(new QueryResult(query, candidates.stream().limit(CANDIDATE_K).toList()));
        }

        double hitAt3 = computeHitAtK(results, TOP_K);
        double contextRecallAt3 = computeContextRecallAtK(results, TOP_K);
        double mrr = computeMrr(results);

        exportDatasetRoot(results, ingestedDocs, failures, kbId, artifactsDir);

        assertThat(hitAt3)
                .as("hit@3 should be > 0; a real retrieval export with all misses is not useful")
                .isGreaterThan(0.0);

        Map<String, Long> queryFormatCounts = allQueries.stream()
                .collect(Collectors.groupingBy(GroundedQuery::format, LinkedHashMap::new, Collectors.counting()));
        System.out.printf("[%s] Phase 10a %s complete: files=%d, chunks=%d, queries=%d, hit@3=%.3f, recall@3=%.3f, MRR=%.3f%n",
                RUN_ID,
                acceptedSize ? "accepted-size full-chain" : "smoke",
                ingestedDocs.size(),
                ingestedDocs.stream().mapToInt(d -> d.chunks.size()).sum(),
                allQueries.size(),
                hitAt3,
                contextRecallAt3,
                mrr);
        System.out.printf("[%s] Artifacts at: %s%n", RUN_ID, artifactsDir);
        System.out.printf("[%s] Per-format query counts: %s%n", RUN_ID, queryFormatCounts);
    }

    private IngestedDocument ingestThroughFacadeOutbox(String kbId,
                                                       SourceFile source,
                                                       Map<String, byte[]> archiveCache) throws Exception {
        DownloadedFile downloaded = downloadSource(source, archiveCache);
        String sha256 = sha256Hex(downloaded.bytes());

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

    private List<GroundedQuery> generateDirectEvidenceQueries(List<IngestedDocument> docs, int maxPerDocument) {
        List<GroundedQuery> queries = new ArrayList<>();
        for (IngestedDocument doc : docs) {
            int emitted = 0;
            for (KnowledgeChunkDTO chunk : doc.chunks) {
                if (emitted >= maxPerDocument) {
                    break;
                }
                String content = chunk.getContent();
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                String firstSentence = extractFirstSentence(content);
                if (!StringUtils.hasText(firstSentence) || firstSentence.length() < 20) {
                    continue;
                }
                queries.add(new GroundedQuery(
                        firstSentence, chunk.getId(), doc.docId,
                        doc.source.filename(), doc.source.format(), content,
                        "direct-evidence-preflight", doc.source.split()));
                emitted++;
            }
        }
        return queries;
    }

    private List<GroundedQuery> generateTemplateQueries(List<IngestedDocument> docs) {
        List<GroundedQuery> queries = new ArrayList<>();
        for (IngestedDocument doc : docs) {
            Optional<KnowledgeChunkDTO> referenceChunk = doc.chunks.stream()
                    .filter(chunk -> StringUtils.hasText(chunk.getContent()))
                    .filter(chunk -> chunk.getContent().length() >= 40)
                    .min(Comparator.comparingInt(chunk -> Math.abs(chunk.getContent().length() - 600)));
            if (referenceChunk.isEmpty()) {
                continue;
            }
            KnowledgeChunkDTO chunk = referenceChunk.get();
            String phrase = extractKeyPhrase(chunk.getContent());
            if (!StringUtils.hasText(phrase)) {
                continue;
            }
            queries.add(new GroundedQuery(
                    "What does the document say about " + phrase + "?",
                    chunk.getId(),
                    doc.docId,
                    doc.source.filename(),
                    doc.source.format(),
                    chunk.getContent(),
                    "template-question",
                    doc.source.split()));
        }
        return queries;
    }

    private String extractKeyPhrase(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String cleaned = content.replaceAll("[^A-Za-z0-9\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() < 12) {
            return null;
        }
        String[] words = cleaned.split(" ");
        List<String> meaningful = new ArrayList<>();
        for (String word : words) {
            String normalized = word.trim();
            if (normalized.length() >= 4 && meaningful.size() < 6) {
                meaningful.add(normalized);
            }
        }
        if (meaningful.isEmpty()) {
            return null;
        }
        return String.join(" ", meaningful);
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

    private double computeHitAtK(List<QueryResult> results, int k) {
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

    private double computeContextRecallAtK(List<QueryResult> results, int k) {
        return computeHitAtK(results, k);
    }

    private double computeMrr(List<QueryResult> results) {
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

    private void exportDatasetRoot(List<QueryResult> results,
                                   List<IngestedDocument> docs,
                                   List<DocumentFailure> failures,
                                   String kbId,
                                   Path root) throws Exception {
        Map<String, IngestedDocument> docMap = docs.stream()
                .collect(Collectors.toMap(d -> d.docId, d -> d));

        Path datasetPath = root.resolve(Path.of("datasets", "doc-ingestion", DATASET_ID + ".jsonl"));
        Files.createDirectories(datasetPath.getParent());

        List<Map<String, Object>> datasetRows = new ArrayList<>();
        try (var writer = Files.newBufferedWriter(datasetPath, StandardCharsets.UTF_8)) {
            for (QueryResult r : results) {
                IngestedDocument doc = docMap.get(r.query.referenceDocId());
                Map<String, Object> metadata = rowMetadata(r, doc, kbId);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sampleId", UUID.randomUUID().toString());
                row.put("datasetId", DATASET_ID);
                row.put("sourceGroupId", r.query.referenceDocId());
                row.put("split", doc != null ? doc.source.split() : "calibration");
                row.put("fileId", r.query.referenceDocId());
                row.put("fileFormat", r.query.format());
                row.put("sourceUrl", doc != null ? doc.source.sourceUrl() : null);
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

        writeMetrics(results, docs, root);
        writeSourceManifest(docs, failures, root);
    }

    private Map<String, Object> rowMetadata(QueryResult r, IngestedDocument doc, String kbId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", r.query.format());
        metadata.put("referenceContent", r.query.referenceContent());
        metadata.put("generationMethod", r.query.generationMethod());
        metadata.put("sourceUrl", doc != null ? doc.source.sourceUrl() : null);
        metadata.put("sourceSha256", doc != null ? doc.sha256 : null);
        metadata.put("sourceGroup", doc != null ? doc.source.sourceGroup() : null);
        metadata.put("license", doc != null ? doc.source.license() : null);
        metadata.put("knowledgeBaseId", kbId);
        metadata.put("referenceDocId", r.query.referenceDocId());
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
        metadata.put("retrievedContexts", retrievedContexts(r.candidates.stream().limit(TOP_K).toList()));
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

    private void writeMetrics(List<QueryResult> results, List<IngestedDocument> docs, Path root) throws Exception {
        Map<String, Long> filesByFormat = docs.stream()
                .collect(Collectors.groupingBy(d -> d.source.format(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> queriesByFormat = results.stream()
                .collect(Collectors.groupingBy(r -> r.query.format(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("runId", RUN_ID);
        metrics.put("acceptedSize", acceptedSizeRun());
        metrics.put("documentCount", docs.size());
        metrics.put("queryCount", results.size());
        metrics.put("chunkCount", docs.stream().mapToInt(d -> d.chunks.size()).sum());
        metrics.put("hitAt3", computeHitAtK(results, TOP_K));
        metrics.put("contextRecallAt3", computeContextRecallAtK(results, TOP_K));
        metrics.put("mrr", computeMrr(results));
        metrics.put("topK", TOP_K);
        metrics.put("candidateK", CANDIDATE_K);
        metrics.put("filesByFormat", filesByFormat);
        metrics.put("queriesByFormat", queriesByFormat);
        metrics.put("timestamp", LocalDateTime.now().toString());
        objectMapper.writeValue(root.resolve("metrics.json").toFile(), metrics);
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
        List<SourceFile> catalog = new ArrayList<>();
        catalog.addAll(discoverSecFilings(65));
        catalog.addAll(pdfSources(28));
        catalog.addAll(discoverCmsOfficeSources(".docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 55));
        catalog.addAll(whoXlsxSources());
        catalog.addAll(discoverCmsOfficeSources(".xlsx", "XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 60));
        catalog.addAll(webMarkdownSources(60));
        assertThat(catalog.size()).as("Accepted-size catalog should have surplus candidates").isGreaterThanOrEqualTo(220);
        return catalog;
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

    private void assertAcceptedQueryCoverage(List<GroundedQuery> queries) {
        assertThat(queries).as("Accepted-size 10a must produce at least 500 grounded recall rows")
                .hasSizeGreaterThanOrEqualTo(500);
        Map<String, Long> perFormat = queries.stream()
                .collect(Collectors.groupingBy(GroundedQuery::format, Collectors.counting()));
        for (String format : ACCEPTED_FORMAT_TARGETS.keySet()) {
            assertThat(perFormat.getOrDefault(format, 0L))
                    .as("Accepted-size 10a must produce at least 50 queries for format %s", format)
                    .isGreaterThanOrEqualTo(50L);
        }
        Map<String, Set<String>> splitsByFormat = queries.stream()
                .collect(Collectors.groupingBy(
                        GroundedQuery::format,
                        Collectors.mapping(GroundedQuery::split, Collectors.toSet())
                ));
        for (String format : ACCEPTED_FORMAT_TARGETS.keySet()) {
            assertThat(splitsByFormat.getOrDefault(format, Set.of()))
                    .as("All accepted-size splits must include format %s", format)
                    .contains("calibration", "development", "holdout");
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

    private int directQueriesPerDocument(boolean acceptedSize) {
        if (acceptedSize) {
            return Integer.getInteger("chatagent.eval.docIngestion.directQueriesPerDocument", 2);
        }
        return Integer.MAX_VALUE;
    }

    private static boolean acceptedSizeRun() {
        return Boolean.parseBoolean(System.getProperty("chatagent.eval.docIngestion.acceptedSize", "false"));
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
        return new SourceFile(url, null, filename, mimeType, sourceGroup, format, license, split);
    }

    private static SourceFile archive(String archiveUrl,
                                      String archiveEntry,
                                      String filename,
                                      String mimeType,
                                      String sourceGroup,
                                      String format,
                                      String license,
                                      String split) {
        return new SourceFile(archiveUrl, archiveEntry, filename, mimeType, sourceGroup, format, license, split);
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

    private static String manifestHash(Set<String> items) {
        try {
            String joined = String.join(",", items.stream().sorted().toList());
            return manifestHash(joined.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "sha256:error";
        }
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
                      String split) {
        String sourceUrl() {
            return StringUtils.hasText(archiveEntry) ? url + "#" + archiveEntry : url;
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
                         String split) {
    }

    record QueryResult(GroundedQuery query, List<RankedCandidateHit> candidates) {
    }
}
