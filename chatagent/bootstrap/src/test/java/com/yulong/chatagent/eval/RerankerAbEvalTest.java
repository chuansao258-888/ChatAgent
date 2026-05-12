package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.knowledge.application.KnowledgeBaseFacadeService;
import com.yulong.chatagent.knowledge.application.KnowledgeDocumentFacadeService;
import com.yulong.chatagent.knowledge.model.request.UpsertKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.retrieve.NoopRetrievalReranker;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A/B evaluation comparing BGE reranker vs Noop (no reranking) on the same golden queries.
 * Measures Hit@3, Hit@5, MRR, NDCG@5 delta to quantify reranker effectiveness.
 *
 * <p>Requires: PostgreSQL, Redis, Milvus, Ollama (bge-m3), BGE reranker service.
 * <br>Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-rag-retrieval -Dtest=RerankerAbEvalTest
 */
@Tag("eval-rag-retrieval")
@SpringBootTest
@ActiveProfiles("local-gpu")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerankerAbEvalTest {

    private static final String SC6109_DIR = "C:\\Users\\guany\\OneDrive - Nanyang Technological University\\桌面\\SC6109-BLOCKCHAIN PRIVACY & SCALABILITY";
    private static final String SC6116_DIR = "C:\\Users\\guany\\OneDrive - Nanyang Technological University\\桌面\\SC6116-GAME THEORY & BLOCKCHAIN";
    private static final String CHATAGENT_DIR = "C:\\Users\\guany\\OneDrive - Nanyang Technological University\\桌面\\ChatAgent";

    @Autowired
    private KnowledgeBaseFacadeService kbFacade;

    @Autowired
    private KnowledgeDocumentFacadeService docFacade;

    @Autowired
    private KnowledgeDocumentIngestionService ingestionService;

    @Autowired
    private KnowledgeBaseSimilaritySearcher kbSearcher;

    @Autowired
    private KnowledgeDocumentSignalService signalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${rag.retrieval.top-k:3}")
    private int topK;

    private final NoopRetrievalReranker noopReranker = new NoopRetrievalReranker();

    private String sc6109KbId;
    private String sc6116KbId;
    private String chatagentKbId;
    private Map<String, String> shortNameToDocId;
    private List<EvalQuery> goldenQueries;

    @BeforeAll
    void setUp() throws Exception {
        String adminUserId = findOrCreateAdminUser();
        UserContext.set(LoginUser.builder()
                .userId(adminUserId)
                .role("ADMIN")
                .build());

        System.out.println("=== Reranker A/B Setup: Cleaning existing KBs ===");
        for (KnowledgeBaseVO kb : kbFacade.getKnowledgeBases()) {
            System.out.println("  Deleting KB: " + kb.getName() + " (" + kb.getId() + ")");
            kbFacade.deleteKnowledgeBase(kb.getId());
        }

        System.out.println("=== Reranker A/B Setup: Creating 3 KBs ===");
        sc6109KbId = createKb("SC6109 - Blockchain Privacy", "Blockchain privacy and scalability course materials");
        sc6116KbId = createKb("SC6116 - Game Theory", "Game theory and blockchain course materials");
        chatagentKbId = createKb("ChatAgent - Project Docs", "ChatAgent project technical documentation");

        System.out.println("=== Reranker A/B Setup: Uploading & ingesting documents ===");
        shortNameToDocId = new LinkedHashMap<>();
        List<FileSpec> allSpecs = buildFileSpecs();

        boolean smoke = Boolean.getBoolean("eval.smoke");
        int maxDocs = smoke ? Math.min(5, allSpecs.size()) : allSpecs.size();

        for (int i = 0; i < maxDocs; i++) {
            FileSpec spec = allSpecs.get(i);
            System.out.println("  [" + (i + 1) + "/" + maxDocs + "] " + spec.shortName + " ...");
            try {
                String docId = uploadAndIngest(spec);
                shortNameToDocId.put(spec.shortName, docId);
            } catch (Exception e) {
                System.out.println("    FAILED: " + e.getMessage());
            }
        }

        System.out.println("=== Reranker A/B: " + shortNameToDocId.size() + " documents ingested ===");

        goldenQueries = buildGoldenQueries();
        if (smoke) {
            goldenQueries = goldenQueries.stream()
                    .collect(Collectors.groupingBy(EvalQuery::category))
                    .values().stream()
                    .flatMap(list -> list.stream().limit(3))
                    .toList();
        }
        System.out.println("=== Reranker A/B: " + goldenQueries.size() + " queries prepared ===");
    }

    @Test
    void evaluateRerankerAb() throws Exception {
        List<String> allKbIds = List.of(sc6109KbId, sc6116KbId, chatagentKbId);
        List<PerQueryAbResult> results = new ArrayList<>();

        for (int i = 0; i < goldenQueries.size(); i++) {
            EvalQuery q = goldenQueries.get(i);
            System.out.println("  [" + (i + 1) + "/" + goldenQueries.size() + "] " + q.id + ": " + q.query);

            Set<String> relevantDocIds = resolveDocIds(q.expectedLectureShortNames);
            Map<String, Integer> grades = resolveGrades(q.relevanceGrades);

            // --- Arm A: BGE (full pipeline via KnowledgeBaseSimilaritySearcher) ---
            List<String> bgeDocIds;
            try {
                List<RetrievalHit> bgeHits = kbSearcher.searchByKnowledgeBaseIds(allKbIds, q.query);
                bgeDocIds = bgeHits.stream().map(RetrievalHit::documentId).toList();
            } catch (Exception e) {
                System.out.println("    BGE retrieval FAILED: " + e.getMessage());
                results.add(new PerQueryAbResult(q, List.of(), List.of(), 0, 0, 0, 0, 0, 0, 0, 0));
                continue;
            }

            // --- Arm B: Noop (raw fusion, no reranking) ---
            List<String> noopDocIds;
            try {
                List<MilvusSearchHit> rawCandidates = kbSearcher.searchCandidateHitsByKnowledgeBaseIds(allKbIds, q.query);
                List<MilvusSearchHit> noopReranked = noopReranker.rerank(q.query, signalService.attachSignals(rawCandidates));
                noopDocIds = noopReranked.stream()
                        .limit(topK)
                        .map(MilvusSearchHit::documentId)
                        .toList();
            } catch (Exception e) {
                System.out.println("    Noop retrieval FAILED: " + e.getMessage());
                results.add(new PerQueryAbResult(q, bgeDocIds, List.of(), 0, 0, 0, 0, 0, 0, 0, 0));
                continue;
            }

            // Compute metrics for both arms
            double bgeHitAt3 = EvalMetrics.hitAtK(bgeDocIds, relevantDocIds, 3);
            double bgeHitAt5 = EvalMetrics.hitAtK(bgeDocIds, relevantDocIds, 5);
            double bgeMrr = EvalMetrics.mrr(bgeDocIds, relevantDocIds);
            double bgeNdcgAt5 = EvalMetrics.ndcgAtK(bgeDocIds, grades, 5);

            double noopHitAt3 = EvalMetrics.hitAtK(noopDocIds, relevantDocIds, 3);
            double noopHitAt5 = EvalMetrics.hitAtK(noopDocIds, relevantDocIds, 5);
            double noopMrr = EvalMetrics.mrr(noopDocIds, relevantDocIds);
            double noopNdcgAt5 = EvalMetrics.ndcgAtK(noopDocIds, grades, 5);

            results.add(new PerQueryAbResult(q, bgeDocIds, noopDocIds,
                    bgeHitAt3, bgeHitAt5, bgeMrr, bgeNdcgAt5,
                    noopHitAt3, noopHitAt5, noopMrr, noopNdcgAt5));

            System.out.println("    BGE:  hit@3=" + round4(bgeHitAt3) + " ndcg5=" + round4(bgeNdcgAt5) + " mrr=" + round4(bgeMrr));
            System.out.println("    Noop: hit@3=" + round4(noopHitAt3) + " ndcg5=" + round4(noopNdcgAt5) + " mrr=" + round4(noopMrr));
        }

        // Aggregate
        ArmMetrics bgeAgg = aggregateArm(results, true);
        ArmMetrics noopAgg = aggregateArm(results, false);

        double ndcgLift = bgeAgg.ndcgAt5 - noopAgg.ndcgAt5;
        double mrrLift = bgeAgg.mrr - noopAgg.mrr;
        double hitAt3Lift = bgeAgg.hitAt3 - noopAgg.hitAt3;

        // Per-category breakdown
        Map<String, Map<String, Object>> byCategory = new LinkedHashMap<>();
        results.stream()
                .collect(Collectors.groupingBy(r -> r.query.category()))
                .forEach((cat, list) -> {
                    ArmMetrics bgeCat = aggregateArm(list, true);
                    ArmMetrics noopCat = aggregateArm(list, false);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("count", list.size());
                    m.put("bgeNdcgAt5", round4(bgeCat.ndcgAt5));
                    m.put("noopNdcgAt5", round4(noopCat.ndcgAt5));
                    m.put("ndcgLift", round4(bgeCat.ndcgAt5 - noopCat.ndcgAt5));
                    m.put("bgeMrr", round4(bgeCat.mrr));
                    m.put("noopMrr", round4(noopCat.mrr));
                    m.put("bgeHitAt3", round4(bgeCat.hitAt3));
                    m.put("noopHitAt3", round4(noopCat.hitAt3));
                    byCategory.put(cat, m);
                });

        // Report
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "reranker-ab");
        report.put("totalQueries", results.size());
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("bge", Map.of(
                "avgHitAt3", round4(bgeAgg.hitAt3),
                "avgHitAt5", round4(bgeAgg.hitAt5),
                "avgMrr", round4(bgeAgg.mrr),
                "avgNdcgAt5", round4(bgeAgg.ndcgAt5)
        ));
        metrics.put("noop", Map.of(
                "avgHitAt3", round4(noopAgg.hitAt3),
                "avgHitAt5", round4(noopAgg.hitAt5),
                "avgMrr", round4(noopAgg.mrr),
                "avgNdcgAt5", round4(noopAgg.ndcgAt5)
        ));
        metrics.put("delta", Map.of(
                "ndcgLift", round4(ndcgLift),
                "mrrLift", round4(mrrLift),
                "hitAt3Lift", round4(hitAt3Lift)
        ));
        metrics.put("byCategory", byCategory);
        report.put("metrics", metrics);

        Path reportPath = EvalReportWriter.writeReport("reranker-ab-eval", report);

        // Print summary
        System.out.println("\n=== Reranker A/B Results ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Queries: " + results.size());
        System.out.println();
        System.out.println("BGE (with reranking):");
        System.out.println("  Hit@3:   " + round4(bgeAgg.hitAt3));
        System.out.println("  Hit@5:   " + round4(bgeAgg.hitAt5));
        System.out.println("  MRR:     " + round4(bgeAgg.mrr));
        System.out.println("  NDCG@5:  " + round4(bgeAgg.ndcgAt5));
        System.out.println();
        System.out.println("Noop (no reranking):");
        System.out.println("  Hit@3:   " + round4(noopAgg.hitAt3));
        System.out.println("  Hit@5:   " + round4(noopAgg.hitAt5));
        System.out.println("  MRR:     " + round4(noopAgg.mrr));
        System.out.println("  NDCG@5:  " + round4(noopAgg.ndcgAt5));
        System.out.println();
        System.out.println("BGE Lift:");
        System.out.println("  NDCG@5: " + (ndcgLift >= 0 ? "+" : "") + round4(ndcgLift));
        System.out.println("  MRR:    " + (mrrLift >= 0 ? "+" : "") + round4(mrrLift));
        System.out.println("  Hit@3:  " + (hitAt3Lift >= 0 ? "+" : "") + round4(hitAt3Lift));
        System.out.println();
        System.out.println("By Category:");
        byCategory.forEach((cat, m) ->
                System.out.printf("  %-15s: ndcg_lift=%s  bge=%s noop=%s (%d queries)%n",
                        cat, m.get("ndcgLift"), m.get("bgeNdcgAt5"), m.get("noopNdcgAt5"), m.get("count")));

        // Assertions
        assertThat(results.size()).as("Should have results").isGreaterThan(0);
    }

    @AfterAll
    void tearDown() {
        System.out.println("=== Reranker A/B Teardown ===");
        try {
            if (sc6109KbId != null) kbFacade.deleteKnowledgeBase(sc6109KbId);
            if (sc6116KbId != null) kbFacade.deleteKnowledgeBase(sc6116KbId);
            if (chatagentKbId != null) kbFacade.deleteKnowledgeBase(chatagentKbId);
        } catch (Exception e) {
            System.out.println("  Teardown error: " + e.getMessage());
        } finally {
            UserContext.clear();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ArmMetrics aggregateArm(List<PerQueryAbResult> results, boolean bge) {
        return new ArmMetrics(
                results.stream().mapToDouble(r -> bge ? r.bgeHitAt3 : r.noopHitAt3).average().orElse(0),
                results.stream().mapToDouble(r -> bge ? r.bgeHitAt5 : r.noopHitAt5).average().orElse(0),
                results.stream().mapToDouble(r -> bge ? r.bgeMrr : r.noopMrr).average().orElse(0),
                results.stream().mapToDouble(r -> bge ? r.bgeNdcgAt5 : r.noopNdcgAt5).average().orElse(0)
        );
    }

    private String createKb(String name, String description) {
        UpsertKnowledgeBaseRequest req = new UpsertKnowledgeBaseRequest();
        req.setName(name);
        req.setDescription(description);
        return kbFacade.createKnowledgeBase(req);
    }

    private String findOrCreateAdminUser() {
        List<String> adminIds = jdbcTemplate.queryForList(
                "SELECT id FROM t_user WHERE role = 'ADMIN' LIMIT 1", String.class);
        if (!adminIds.isEmpty()) return adminIds.get(0);
        List<String> anyIds = jdbcTemplate.queryForList(
                "SELECT id FROM t_user LIMIT 1", String.class);
        if (!anyIds.isEmpty()) return anyIds.get(0);
        String testId = "a0000000-0000-0000-0000-000000000001";
        jdbcTemplate.update(
                "INSERT INTO t_user (id, username, password_hash, role, status, created_at, updated_at) " +
                "VALUES (?, 'eval-admin', '$2a$10$dummy.hash.for.eval.test', 'ADMIN', 'ACTIVE', NOW(), NOW())",
                testId);
        return testId;
    }

    private String uploadAndIngest(FileSpec spec) throws Exception {
        Path filePath = Path.of(spec.baseDir).resolve(spec.fileName);
        byte[] content = Files.readAllBytes(filePath);
        MockMultipartFile file = new MockMultipartFile(
                "file", filePath.getFileName().toString(), spec.mimeType, content);
        UploadKnowledgeDocumentResponse resp = docFacade.uploadKnowledgeDocument(spec.kbId, file);
        KnowledgeDocumentDTO docDto = docFacade.getKnowledgeDocument(resp.getDocumentId());
        ingestionService.ingestSync(spec.kbId, docDto);
        return resp.getDocumentId();
    }

    private Set<String> resolveDocIds(List<String> shortNames) {
        Set<String> ids = new LinkedHashSet<>();
        for (String sn : shortNames) {
            String docId = shortNameToDocId.get(sn);
            if (docId != null) ids.add(docId);
        }
        return ids;
    }

    private Map<String, Integer> resolveGrades(Map<String, Integer> shortNameGrades) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : shortNameGrades.entrySet()) {
            String docId = shortNameToDocId.get(entry.getKey());
            if (docId != null) resolved.put(docId, entry.getValue());
        }
        return resolved;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // =========================================================================
    // Data structures
    // =========================================================================

    private record FileSpec(String baseDir, String fileName, String kbId, String shortName, String mimeType) {}

    private record EvalQuery(
            String id, String category, String query,
            List<String> expectedLectureShortNames,
            Map<String, Integer> relevanceGrades
    ) {}

    private record PerQueryAbResult(
            EvalQuery query,
            List<String> bgeDocIds,
            List<String> noopDocIds,
            double bgeHitAt3, double bgeHitAt5, double bgeMrr, double bgeNdcgAt5,
            double noopHitAt3, double noopHitAt5, double noopMrr, double noopNdcgAt5
    ) {}

    private record ArmMetrics(double hitAt3, double hitAt5, double mrr, double ndcgAt5) {}

    // =========================================================================
    // File specs (same as RetrievalQualityIntegrationEvalTest)
    // =========================================================================

    private List<FileSpec> buildFileSpecs() {
        List<FileSpec> specs = new ArrayList<>();
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 01 - Discrete_Logarithm.pdf", sc6109KbId, "lec01-dl", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 01 - Privacy Issues.pdf", sc6109KbId, "lec01-privacy", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 01 - Digitial Signature 2026.pdf", sc6109KbId, "lec01-sig", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 02 - Factoring_and_Continued_Fractions w Annotation.pdf", sc6109KbId, "lec02-factoring", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 03 - Group Ring Signature w Annotation.pdf", sc6109KbId, "lec03-grs", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SE6019 - Lecture 04 - MPC ZK.pdf", sc6109KbId, "lec04-mpczk", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SE6109 - Lecture 05 - PQC.pdf", sc6109KbId, "lec05-pqc", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "1.Introduction.pdf", sc6116KbId, "lec01-intro", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "2.Basics of Probability.pdf", sc6116KbId, "lec02-prob", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "3.Decision Theory.pdf", sc6116KbId, "lec03-decision", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "4.Basics of Game Theory.pdf", sc6116KbId, "lec04-game", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "5.Auctions.pdf", sc6116KbId, "lec05-auction", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "6.Coordination.pdf", sc6116KbId, "lec06-coord", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "7.Selfish.pdf", sc6116KbId, "lec07-selfish", "application/pdf"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/01-llm-routing.md", chatagentKbId, "ca-llm-routing", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/02-agent-runtime.md", chatagentKbId, "ca-agent-runtime", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/04-rag-pipeline.md", chatagentKbId, "ca-rag-pipeline", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/05-intent-routing.md", chatagentKbId, "ca-intent-routing", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/06-mcp-integration.md", chatagentKbId, "ca-mcp-integration", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/03-conversation-orchestration.md", chatagentKbId, "ca-conv-orch", "text/markdown"));
        return specs;
    }

    // =========================================================================
    // Golden queries (same as RetrievalQualityIntegrationEvalTest)
    // =========================================================================

    private List<EvalQuery> buildGoldenQueries() {
        List<EvalQuery> queries = new ArrayList<>();

        // SC6109 factual (10)
        queries.add(new EvalQuery("sc6109-f-01", "factual", "What is the discrete logarithm problem", List.of("lec01-dl"), grades("lec01-dl", 3)));
        queries.add(new EvalQuery("sc6109-f-02", "factual", "How does the discrete logarithm relate to cryptography", List.of("lec01-dl"), grades("lec01-dl", 3)));
        queries.add(new EvalQuery("sc6109-f-03", "factual", "What are the privacy issues in blockchain", List.of("lec01-privacy"), grades("lec01-privacy", 3)));
        queries.add(new EvalQuery("sc6109-f-04", "factual", "How do digital signatures work in blockchain", List.of("lec01-sig"), grades("lec01-sig", 3)));
        queries.add(new EvalQuery("sc6109-f-05", "factual", "What is integer factorization and continued fractions", List.of("lec02-factoring"), grades("lec02-factoring", 3)));
        queries.add(new EvalQuery("sc6109-f-06", "factual", "Explain group signatures and ring signatures", List.of("lec03-grs"), grades("lec03-grs", 3)));
        queries.add(new EvalQuery("sc6109-f-07", "factual", "What is multi-party computation MPC", List.of("lec04-mpczk"), grades("lec04-mpczk", 3)));
        queries.add(new EvalQuery("sc6109-f-08", "factual", "How do zero-knowledge proofs work", List.of("lec04-mpczk"), grades("lec04-mpczk", 3)));
        queries.add(new EvalQuery("sc6109-f-09", "factual", "What is post-quantum cryptography", List.of("lec05-pqc"), grades("lec05-pqc", 3)));
        queries.add(new EvalQuery("sc6109-f-10", "factual", "How are lattice-based cryptographic schemes constructed", List.of("lec05-pqc"), grades("lec05-pqc", 3)));

        // SC6116 factual (10)
        queries.add(new EvalQuery("sc6116-f-01", "factual", "What is game theory introduction", List.of("lec01-intro"), grades("lec01-intro", 3)));
        queries.add(new EvalQuery("sc6116-f-02", "factual", "What are the basic concepts of probability in games", List.of("lec02-prob"), grades("lec02-prob", 3)));
        queries.add(new EvalQuery("sc6116-f-03", "factual", "What is decision theory and expected utility", List.of("lec03-decision"), grades("lec03-decision", 3)));
        queries.add(new EvalQuery("sc6116-f-04", "factual", "What are the types of games in game theory", List.of("lec04-game"), grades("lec04-game", 3)));
        queries.add(new EvalQuery("sc6116-f-05", "factual", "How do auctions work in mechanism design", List.of("lec05-auction"), grades("lec05-auction", 3)));
        queries.add(new EvalQuery("sc6116-f-06", "factual", "What is coordination game and Nash equilibrium", List.of("lec06-coord"), grades("lec06-coord", 3)));
        queries.add(new EvalQuery("sc6116-f-07", "factual", "What is selfish routing and the price of anarchy", List.of("lec07-selfish"), grades("lec07-selfish", 3)));
        queries.add(new EvalQuery("sc6116-f-08", "factual", "How does Bayesian probability apply to games", List.of("lec02-prob"), grades("lec02-prob", 3)));
        queries.add(new EvalQuery("sc6116-f-09", "factual", "What is Vickrey auction and truthful bidding", List.of("lec05-auction"), grades("lec05-auction", 3)));
        queries.add(new EvalQuery("sc6116-f-10", "factual", "What is the difference between cooperative and non-cooperative games", List.of("lec04-game"), grades("lec04-game", 3)));

        // ChatAgent factual (5)
        queries.add(new EvalQuery("ca-f-01", "factual", "How does LLM routing work in ChatAgent", List.of("ca-llm-routing"), grades("ca-llm-routing", 3)));
        queries.add(new EvalQuery("ca-f-02", "factual", "What is the agent runtime architecture", List.of("ca-agent-runtime"), grades("ca-agent-runtime", 3)));
        queries.add(new EvalQuery("ca-f-03", "factual", "How does the RAG pipeline process documents", List.of("ca-rag-pipeline"), grades("ca-rag-pipeline", 3)));
        queries.add(new EvalQuery("ca-f-04", "factual", "How does intent routing classify user queries", List.of("ca-intent-routing"), grades("ca-intent-routing", 3)));
        queries.add(new EvalQuery("ca-f-05", "factual", "What is MCP integration in ChatAgent", List.of("ca-mcp-integration"), grades("ca-mcp-integration", 3)));

        // Cross-topic (5)
        queries.add(new EvalQuery("ct-01", "cross-topic", "How do discrete logarithm and digital signatures relate", List.of("lec01-dl", "lec01-sig"), multiGrades("lec01-dl", 3, "lec01-sig", 3)));
        queries.add(new EvalQuery("ct-02", "cross-topic", "What cryptographic primitives provide privacy", List.of("lec01-privacy", "lec04-mpczk", "lec03-grs"), multiGrades("lec01-privacy", 3, "lec04-mpczk", 2, "lec03-grs", 2)));
        queries.add(new EvalQuery("ct-03", "cross-topic", "How does probability theory relate to game theory auctions", List.of("lec02-prob", "lec05-auction"), multiGrades("lec02-prob", 2, "lec05-auction", 3)));
        queries.add(new EvalQuery("ct-04", "cross-topic", "What is the relationship between decision theory and coordination", List.of("lec03-decision", "lec06-coord"), multiGrades("lec03-decision", 2, "lec06-coord", 3)));
        queries.add(new EvalQuery("ct-05", "cross-topic", "How does conversation orchestration work with agent runtime", List.of("ca-conv-orch", "ca-agent-runtime"), multiGrades("ca-conv-orch", 3, "ca-agent-runtime", 2)));

        // Cross-domain (10)
        queries.add(new EvalQuery("cd-01", "cross-domain", "How does game theory apply to blockchain consensus", List.of("lec01-intro", "lec07-selfish", "lec01-privacy"), multiGrades("lec01-intro", 2, "lec07-selfish", 2, "lec01-privacy", 1)));
        queries.add(new EvalQuery("cd-02", "cross-domain", "What is the role of probability in cryptographic protocols", List.of("lec02-prob", "lec01-dl"), multiGrades("lec02-prob", 2, "lec01-dl", 2)));
        queries.add(new EvalQuery("cd-03", "cross-domain", "How do auctions relate to mechanism design in blockchain", List.of("lec05-auction", "lec01-privacy"), multiGrades("lec05-auction", 3, "lec01-privacy", 1)));
        queries.add(new EvalQuery("cd-04", "cross-domain", "What is the relationship between zero knowledge and game theory", List.of("lec04-mpczk", "lec04-game"), multiGrades("lec04-mpczk", 2, "lec04-game", 2)));
        queries.add(new EvalQuery("cd-05", "cross-domain", "How does selfish mining relate to blockchain privacy", List.of("lec07-selfish", "lec01-privacy"), multiGrades("lec07-selfish", 3, "lec01-privacy", 2)));
        queries.add(new EvalQuery("cd-06", "cross-domain", "How does RAG retrieval compare to information retrieval in cryptography", List.of("ca-rag-pipeline", "lec01-privacy"), multiGrades("ca-rag-pipeline", 2, "lec01-privacy", 1)));
        queries.add(new EvalQuery("cd-07", "cross-domain", "What is the relationship between game theory and AI agent design", List.of("lec04-game", "ca-agent-runtime"), multiGrades("lec04-game", 2, "ca-agent-runtime", 2)));
        queries.add(new EvalQuery("cd-08", "cross-domain", "How do digital signatures relate to MCP tool authentication", List.of("lec01-sig", "ca-mcp-integration"), multiGrades("lec01-sig", 1, "ca-mcp-integration", 2)));
        queries.add(new EvalQuery("cd-09", "cross-domain", "How does intent classification compare to game theory decision making", List.of("ca-intent-routing", "lec03-decision"), multiGrades("ca-intent-routing", 2, "lec03-decision", 2)));
        queries.add(new EvalQuery("cd-10", "cross-domain", "How does RAG vector search relate to lattice-based cryptography", List.of("ca-rag-pipeline", "lec05-pqc"), multiGrades("ca-rag-pipeline", 2, "lec05-pqc", 1)));

        return queries;
    }

    private static Map<String, Integer> grades(String key, int value) {
        return Map.of(key, value);
    }

    private static Map<String, Integer> multiGrades(Object... keyValues) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], (Integer) keyValues[i + 1]);
        }
        return map;
    }
}
