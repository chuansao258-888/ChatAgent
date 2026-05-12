package com.yulong.chatagent.eval;

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
import com.yulong.chatagent.rag.retrieve.NoopRetrievalReranker;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Latency baseline evaluation for the RAG retrieval pipeline.
 * Measures per-stage timing for each golden query:
 *   - Embedding + Milvus fusion (retrieval-only)
 *   - BGE rerank overhead (full pipeline minus retrieval-only)
 *   - Full end-to-end
 *
 * <p>Requires: PostgreSQL, Redis, Milvus, Ollama (bge-m3), BGE reranker service.
 * <br>Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-rag-retrieval -Dtest=LatencyBaselineEvalTest
 */
@Tag("eval-rag-retrieval")
@SpringBootTest
@ActiveProfiles("local-gpu")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LatencyBaselineEvalTest {

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
    private List<String> goldenQueries;

    @BeforeAll
    void setUp() throws Exception {
        String adminUserId = findOrCreateAdminUser();
        UserContext.set(LoginUser.builder()
                .userId(adminUserId)
                .role("ADMIN")
                .build());

        System.out.println("=== Latency Baseline Setup: Cleaning existing KBs ===");
        for (KnowledgeBaseVO kb : kbFacade.getKnowledgeBases()) {
            System.out.println("  Deleting KB: " + kb.getName() + " (" + kb.getId() + ")");
            kbFacade.deleteKnowledgeBase(kb.getId());
        }

        System.out.println("=== Latency Baseline Setup: Creating 3 KBs ===");
        sc6109KbId = createKb("SC6109 - Blockchain Privacy", "Blockchain privacy and scalability course materials");
        sc6116KbId = createKb("SC6116 - Game Theory", "Game theory and blockchain course materials");
        chatagentKbId = createKb("ChatAgent - Project Docs", "ChatAgent project technical documentation");

        System.out.println("=== Latency Baseline Setup: Uploading & ingesting documents ===");
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

        System.out.println("=== Latency Baseline: " + shortNameToDocId.size() + " documents ingested ===");

        goldenQueries = buildGoldenQueries();
        if (smoke) {
            goldenQueries = goldenQueries.stream().limit(5).toList();
        }
        System.out.println("=== Latency Baseline: " + goldenQueries.size() + " queries prepared ===");
    }

    @Test
    void evaluateLatencyBaseline() throws Exception {
        List<String> allKbIds = List.of(sc6109KbId, sc6116KbId, chatagentKbId);
        List<PerQueryLatency> results = new ArrayList<>();

        // Warm up: run 3 queries to prime caches
        System.out.println("=== Warm-up (3 queries) ===");
        for (int i = 0; i < Math.min(3, goldenQueries.size()); i++) {
            String q = goldenQueries.get(i);
            try {
                kbSearcher.searchByKnowledgeBaseIds(allKbIds, q);
                kbSearcher.searchCandidateHitsByKnowledgeBaseIds(allKbIds, q);
            } catch (Exception ignored) {}
        }

        System.out.println("=== Latency Baseline: Measuring " + goldenQueries.size() + " queries ===");
        for (int i = 0; i < goldenQueries.size(); i++) {
            String query = goldenQueries.get(i);
            System.out.print("  [" + (i + 1) + "/" + goldenQueries.size() + "] " + query.substring(0, Math.min(50, query.length())) + " ... ");

            // Stage 1: Full pipeline (embedding + fusion + rerank)
            long fullStart = System.nanoTime();
            List<RetrievalHit> fullHits;
            try {
                fullHits = kbSearcher.searchByKnowledgeBaseIds(allKbIds, query);
            } catch (Exception e) {
                System.out.println("FULL FAILED: " + e.getMessage());
                continue;
            }
            long fullEnd = System.nanoTime();
            long fullMs = (fullEnd - fullStart) / 1_000_000;

            // Stage 2: Retrieval-only (embedding + fusion, no rerank)
            long retrievalStart = System.nanoTime();
            List<MilvusSearchHit> rawCandidates;
            try {
                rawCandidates = kbSearcher.searchCandidateHitsByKnowledgeBaseIds(allKbIds, query);
            } catch (Exception e) {
                System.out.println("RETRIEVAL FAILED: " + e.getMessage());
                continue;
            }
            long retrievalEnd = System.nanoTime();
            long retrievalMs = (retrievalEnd - retrievalStart) / 1_000_000;

            // Reranker overhead = full pipeline - retrieval-only
            long rerankerOverheadMs = Math.max(0, fullMs - retrievalMs);
            int candidateCount = rawCandidates.size();

            results.add(new PerQueryLatency(query, fullMs, retrievalMs, rerankerOverheadMs, candidateCount, fullHits.size()));

            System.out.printf("full=%dms  retrieval=%dms  reranker=%dms  (candidates=%d, returned=%d)%n",
                    fullMs, retrievalMs, rerankerOverheadMs, candidateCount, fullHits.size());
        }

        // Aggregate
        List<Long> fullLatencies = results.stream().map(r -> (long) r.fullMs).sorted().toList();
        List<Long> retrievalLatencies = results.stream().map(r -> (long) r.retrievalMs).sorted().toList();
        List<Long> rerankerOverheads = results.stream().map(r -> (long) r.rerankerOverheadMs).sorted().toList();

        long fullP50 = p(fullLatencies, 50);
        long fullP95 = p(fullLatencies, 95);
        long fullP99 = p(fullLatencies, 99);
        long fullAvg = (long) fullLatencies.stream().mapToLong(l -> l).average().orElse(0);

        long retP50 = p(retrievalLatencies, 50);
        long retP95 = p(retrievalLatencies, 95);
        long retP99 = p(retrievalLatencies, 99);
        long retAvg = (long) retrievalLatencies.stream().mapToLong(l -> l).average().orElse(0);

        long rerankP50 = p(rerankerOverheads, 50);
        long rerankP95 = p(rerankerOverheads, 95);
        long rerankP99 = p(rerankerOverheads, 99);
        long rerankAvg = (long) rerankerOverheads.stream().mapToLong(l -> l).average().orElse(0);

        double rerankerOverheadPercent = retAvg == 0 ? 0 : (rerankAvg * 100.0 / retAvg);

        // Build report
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalQueries", results.size());
        metrics.put("documentCount", shortNameToDocId.size());
        metrics.put("knowledgeBaseCount", 3);

        Map<String, Object> fullPipeline = new LinkedHashMap<>();
        fullPipeline.put("avgMs", fullAvg);
        fullPipeline.put("p50Ms", fullP50);
        fullPipeline.put("p95Ms", fullP95);
        fullPipeline.put("p99Ms", fullP99);
        metrics.put("fullPipeline", fullPipeline);

        Map<String, Object> retrievalOnly = new LinkedHashMap<>();
        retrievalOnly.put("avgMs", retAvg);
        retrievalOnly.put("p50Ms", retP50);
        retrievalOnly.put("p95Ms", retP95);
        retrievalOnly.put("p99Ms", retP99);
        metrics.put("retrievalOnly", retrievalOnly);

        Map<String, Object> rerankerOverhead = new LinkedHashMap<>();
        rerankerOverhead.put("avgMs", rerankAvg);
        rerankerOverhead.put("p50Ms", rerankP50);
        rerankerOverhead.put("p95Ms", rerankP95);
        rerankerOverhead.put("p99Ms", rerankP99);
        rerankerOverhead.put("overheadPercent", round2(rerankerOverheadPercent));
        metrics.put("rerankerOverhead", rerankerOverhead);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "latency-baseline");
        report.put("metrics", metrics);
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));

        Path reportPath = EvalReportWriter.writeReport("latency-baseline-eval", report);

        // Print summary
        System.out.println("\n=== Latency Baseline Results ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Queries: " + results.size() + " | Documents: " + shortNameToDocId.size());
        System.out.println();
        System.out.println("Full Pipeline (embedding + fusion + rerank):");
        System.out.printf("  Avg=%dms  P50=%dms  P95=%dms  P99=%dms%n", fullAvg, fullP50, fullP95, fullP99);
        System.out.println();
        System.out.println("Retrieval Only (embedding + fusion):");
        System.out.printf("  Avg=%dms  P50=%dms  P95=%dms  P99=%dms%n", retAvg, retP50, retP95, retP99);
        System.out.println();
        System.out.println("Reranker Overhead:");
        System.out.printf("  Avg=%dms  P50=%dms  P95=%dms  P99=%dms  (%.1f%% of retrieval)%n",
                rerankAvg, rerankP50, rerankP95, rerankP99, rerankerOverheadPercent);
        System.out.println();

        // Assertions
        assertThat(results.size()).as("Should have latency results").isGreaterThan(0);
        assertThat(retP50).as("Retrieval P50 should be under 2s").isLessThan(2000);
        assertThat(fullP50).as("Full pipeline P50 should be under 3s").isLessThan(3000);
    }

    @AfterAll
    void tearDown() {
        System.out.println("=== Latency Baseline Teardown ===");
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

    private static long p(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // =========================================================================
    // Data structures
    // =========================================================================

    private record FileSpec(String baseDir, String fileName, String kbId, String shortName, String mimeType) {}

    private record PerQueryLatency(
            String query,
            long fullMs, long retrievalMs, long rerankerOverheadMs,
            int candidateCount, int returnedCount
    ) {}

    // =========================================================================
    // File specs
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
    // Golden queries (simplified — just query text, no relevance labels needed)
    // =========================================================================

    private List<String> buildGoldenQueries() {
        return List.of(
                // Factual (15)
                "What is the discrete logarithm problem",
                "How does the discrete logarithm relate to cryptography",
                "What are the privacy issues in blockchain",
                "How do digital signatures work in blockchain",
                "What is integer factorization and continued fractions",
                "Explain group signatures and ring signatures",
                "What is multi-party computation MPC",
                "How do zero-knowledge proofs work",
                "What is post-quantum cryptography",
                "What is game theory introduction",
                "What are the basic concepts of probability in games",
                "What is decision theory and expected utility",
                "How do auctions work in mechanism design",
                "What is coordination game and Nash equilibrium",
                "How does LLM routing work in ChatAgent",
                // Cross-topic (5)
                "How do discrete logarithm and digital signatures relate",
                "What cryptographic primitives provide privacy",
                "How does probability theory relate to game theory auctions",
                "What is the relationship between decision theory and coordination",
                "How does conversation orchestration work with agent runtime",
                // Cross-domain (10)
                "How does game theory apply to blockchain consensus",
                "What is the role of probability in cryptographic protocols",
                "How do auctions relate to mechanism design in blockchain",
                "What is the relationship between zero knowledge and game theory",
                "How does selfish mining relate to blockchain privacy",
                "How does RAG retrieval compare to information retrieval in cryptography",
                "What is the relationship between game theory and AI agent design",
                "How do digital signatures relate to MCP tool authentication",
                "How does intent classification compare to game theory decision making",
                "How does RAG vector search relate to lattice-based cryptography"
        );
    }
}
