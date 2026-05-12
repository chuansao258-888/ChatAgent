package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.chat.ChatModelRouter;
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
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full RAGAS (Retrieval Augmented Generation Assessment) evaluation with real infrastructure.
 * End-to-end: clean DB → upload 20 real documents → ingest → retrieve → generate → LLM judge.
 *
 * RAGAS metrics:
 *   Context Precision: Hit@3, Hit@5, NDCG@5
 *   Context Recall:    MRR
 *   Faithfulness:      LLM-judged answer faithfulness to context
 *   Answer Relevancy:  LLM-judged answer relevance to question
 *
 * Requires: PostgreSQL, Redis, Milvus, Ollama (bge-m3), DeepSeek API.
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-rag-retrieval -Dtest=RetrievalQualityIntegrationEvalTest
 */
@Tag("eval-rag-retrieval")
@SpringBootTest
@ActiveProfiles("local-gpu")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalQualityIntegrationEvalTest {

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
    private ChatModelRouter chatModelRouter;

    @Autowired
    private KnowledgeBaseSimilaritySearcher kbSearcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sc6109KbId;
    private String sc6116KbId;
    private String chatagentKbId;
    private Map<String, String> shortNameToDocId;
    private ChatClient chatClient;
    private List<EvalQuery> goldenQueries;

    @BeforeAll
    void setUp() throws Exception {
        // Find or create a valid admin user ID (FK constraint requires existing t_user row)
        String adminUserId = findOrCreateAdminUser();
        UserContext.set(LoginUser.builder()
                .userId(adminUserId)
                .role("ADMIN")
                .build());

        // Chat client for generation + evaluation
        chatClient = chatModelRouter.route("deepseek-chat");

        // Step 1: Clean all existing KBs
        System.out.println("=== RAGAS Eval Setup: Cleaning existing KBs ===");
        KnowledgeBaseVO[] existingKbs = kbFacade.getKnowledgeBases();
        for (KnowledgeBaseVO kb : existingKbs) {
            System.out.println("  Deleting KB: " + kb.getName() + " (" + kb.getId() + ")");
            kbFacade.deleteKnowledgeBase(kb.getId());
        }

        // Step 2: Create 3 KBs
        System.out.println("=== RAGAS Eval Setup: Creating 3 KBs ===");
        sc6109KbId = createKb("SC6109 - Blockchain Privacy", "Blockchain privacy and scalability course materials");
        sc6116KbId = createKb("SC6116 - Game Theory", "Game theory and blockchain course materials");
        chatagentKbId = createKb("ChatAgent - Project Docs", "ChatAgent project technical documentation");
        System.out.println("  SC6109 KB: " + sc6109KbId);
        System.out.println("  SC6116 KB: " + sc6116KbId);
        System.out.println("  ChatAgent KB: " + chatagentKbId);

        // Step 3: Upload + ingest 20 documents
        System.out.println("=== RAGAS Eval Setup: Uploading & ingesting 20 documents ===");
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
                System.out.println("    OK: " + docId);
            } catch (Exception e) {
                System.out.println("    FAILED: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }

        System.out.println("=== RAGAS Eval Setup: " + shortNameToDocId.size() + " documents ingested ===");

        // Step 4: Build golden queries
        goldenQueries = buildGoldenQueries();
        if (smoke) {
            goldenQueries = goldenQueries.stream()
                    .collect(Collectors.groupingBy(EvalQuery::category))
                    .values().stream()
                    .flatMap(list -> list.stream().limit(3))
                    .toList();
        }
        System.out.println("=== RAGAS Eval: " + goldenQueries.size() + " golden queries prepared ===");
    }

    @Test
    void evaluateRagasMetrics() throws Exception {
        List<String> allKbIds = List.of(sc6109KbId, sc6116KbId, chatagentKbId);
        List<PerQueryResult> results = new ArrayList<>();
        List<Long> retrievalLatencies = new ArrayList<>();
        List<Long> generationLatencies = new ArrayList<>();

        for (int i = 0; i < goldenQueries.size(); i++) {
            EvalQuery q = goldenQueries.get(i);
            System.out.println("  [" + (i + 1) + "/" + goldenQueries.size() + "] " + q.id + ": " + q.query);

            // Step A: Retrieve
            long retrievalStart = System.currentTimeMillis();
            List<RetrievalHit> hits;
            try {
                hits = kbSearcher.searchByKnowledgeBaseIds(allKbIds, q.query);
            } catch (Exception e) {
                System.out.println("    Retrieval FAILED: " + e.getMessage());
                results.add(new PerQueryResult(q, List.of(), "", 0.0, 0.0, 0, 0, 0, 0));
                continue;
            }
            long retrievalMs = System.currentTimeMillis() - retrievalStart;
            retrievalLatencies.add(retrievalMs);

            List<String> rankedDocIds = hits.stream()
                    .map(RetrievalHit::documentId)
                    .toList();

            // Compute retrieval metrics
            Set<String> relevantDocIds = resolveDocIds(q.expectedLectureShortNames);
            Map<String, Integer> grades = resolveGrades(q.relevanceGrades);

            double hitAt3 = EvalMetrics.hitAtK(rankedDocIds, relevantDocIds, 3);
            double hitAt5 = EvalMetrics.hitAtK(rankedDocIds, relevantDocIds, 5);
            double mrr = EvalMetrics.mrr(rankedDocIds, relevantDocIds);
            double ndcgAt5 = EvalMetrics.ndcgAtK(rankedDocIds, grades, 5);

            // Step B: Generate answer from retrieved context
            String context = hits.stream()
                    .map(RetrievalHit::content)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n"));

            String answer = "";
            double faithfulness = 0.0;
            double answerRelevancy = 0.0;

            if (!context.isBlank()) {
                long genStart = System.currentTimeMillis();
                try {
                    answer = generateAnswer(context, q.query, q.category());
                } catch (Exception e) {
                    System.out.println("    Generation FAILED: " + e.getMessage());
                    answer = "";
                }
                long genMs = System.currentTimeMillis() - genStart;
                generationLatencies.add(genMs);

                // Step C+D: Combined LLM judge (Faithfulness + Answer Relevancy)
                if (!answer.isBlank()) {
                    try {
                        JudgeResult judge = judgeQuality(context, q.query, answer);
                        faithfulness = judge.faithfulness();
                        answerRelevancy = judge.answerRelevancy();
                    } catch (Exception e) {
                        System.out.println("    Quality judge FAILED: " + e.getMessage());
                    }
                }
            }

            results.add(new PerQueryResult(q, rankedDocIds, answer, faithfulness, answerRelevancy,
                    hitAt3, hitAt5, mrr, ndcgAt5));

            System.out.println("    hit@3=" + hitAt3 + " hit@5=" + hitAt5 + " mrr=" + round4(mrr)
                    + " ndcg5=" + round4(ndcgAt5) + " faith=" + round4(faithfulness) + " rel=" + round4(answerRelevancy)
                    + " (" + retrievalMs + "ms retrieval)");

            // Rate limiting for API calls
            Thread.sleep(500);
        }

        // Aggregate metrics
        long totalQueries = results.size();
        double avgHitAt3 = results.stream().mapToDouble(r -> r.hitAt3).average().orElse(0);
        double avgHitAt5 = results.stream().mapToDouble(r -> r.hitAt5).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        double avgNdcgAt5 = results.stream().mapToDouble(r -> r.ndcgAt5).average().orElse(0);
        double avgFaithfulness = results.stream().mapToDouble(r -> r.faithfulness).average().orElse(0);
        double avgAnswerRelevancy = results.stream().mapToDouble(r -> r.answerRelevancy).average().orElse(0);

        // Latency stats
        retrievalLatencies.sort(Long::compareTo);
        long retP50 = percentile(retrievalLatencies, 50);
        long retP95 = percentile(retrievalLatencies, 95);
        long retAvg = (long) retrievalLatencies.stream().mapToLong(l -> l).average().orElse(0);

        generationLatencies.sort(Long::compareTo);
        long genP50 = percentile(generationLatencies, 50);
        long genP95 = percentile(generationLatencies, 95);

        // Per-category breakdown
        Map<String, List<PerQueryResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(r -> r.query.category()));
        Map<String, Map<String, Object>> categoryMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, List<PerQueryResult>> cat : byCategory.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", cat.getValue().size());
            m.put("avgHitAt3", round4(cat.getValue().stream().mapToDouble(r -> r.hitAt3).average().orElse(0)));
            m.put("avgHitAt5", round4(cat.getValue().stream().mapToDouble(r -> r.hitAt5).average().orElse(0)));
            m.put("avgMrr", round4(cat.getValue().stream().mapToDouble(r -> r.mrr).average().orElse(0)));
            m.put("avgNdcgAt5", round4(cat.getValue().stream().mapToDouble(r -> r.ndcgAt5).average().orElse(0)));
            m.put("avgFaithfulness", round4(cat.getValue().stream().mapToDouble(r -> r.faithfulness).average().orElse(0)));
            m.put("avgAnswerRelevancy", round4(cat.getValue().stream().mapToDouble(r -> r.answerRelevancy).average().orElse(0)));
            categoryMetrics.put(cat.getKey(), m);
        }

        // Build report
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("avgHitAt3", round4(avgHitAt3));
        metrics.put("avgHitAt5", round4(avgHitAt5));
        metrics.put("avgMrr", round4(avgMrr));
        metrics.put("avgNdcgAt5", round4(avgNdcgAt5));
        metrics.put("avgFaithfulness", round4(avgFaithfulness));
        metrics.put("avgAnswerRelevancy", round4(avgAnswerRelevancy));
        metrics.put("totalQueries", totalQueries);
        metrics.put("retrievalLatencyAvgMs", retAvg);
        metrics.put("retrievalLatencyP50Ms", retP50);
        metrics.put("retrievalLatencyP95Ms", retP95);
        metrics.put("generationLatencyP50Ms", genP50);
        metrics.put("generationLatencyP95Ms", genP95);
        metrics.put("documentCount", shortNameToDocId.size());
        metrics.put("knowledgeBaseCount", 3);
        metrics.put("mode", "full-ragas");
        metrics.put("byCategory", categoryMetrics);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "rag-ragas");
        report.put("metrics", metrics);
        report.put("totalQueries", totalQueries);
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));

        Path reportPath = EvalReportWriter.writeReport("rag-ragas-eval", report);

        // Print summary
        System.out.println("\n=== RAGAS Evaluation Results ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Documents: " + shortNameToDocId.size() + " | Queries: " + totalQueries);
        System.out.println("Context Precision:");
        System.out.println("  Hit@3:   " + round4(avgHitAt3));
        System.out.println("  Hit@5:   " + round4(avgHitAt5));
        System.out.println("  NDCG@5:  " + round4(avgNdcgAt5));
        System.out.println("Context Recall:");
        System.out.println("  MRR:     " + round4(avgMrr));
        System.out.println("Generation:");
        System.out.println("  Faithfulness:      " + round4(avgFaithfulness));
        System.out.println("  Answer Relevancy:  " + round4(avgAnswerRelevancy));
        System.out.println("Latency:");
        System.out.println("  Retrieval P50=" + retP50 + "ms  P95=" + retP95 + "ms");
        System.out.println("  Generation P50=" + genP50 + "ms  P95=" + genP95 + "ms");
        System.out.println("\nBy Category:");
        categoryMetrics.forEach((cat, m) ->
                System.out.printf("  %-15s: hit@3=%.2f  faith=%.2f  rel=%.2f  (%d queries)%n",
                        cat, (double) m.get("avgHitAt3"), (double) m.get("avgFaithfulness"),
                        (double) m.get("avgAnswerRelevancy"), m.get("count")));

        System.out.println("\nMetrics JSON:\n" + new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(metrics));

        // Assertions
        assertThat(avgHitAt3)
                .as("Hit@3 should be reasonable with real documents")
                .isGreaterThan(0.3);
        assertThat(avgFaithfulness)
                .as("Faithfulness should be reasonable")
                .isGreaterThan(0.3);
        assertThat(avgAnswerRelevancy)
                .as("Answer Relevancy should be reasonable")
                .isGreaterThan(0.3);
    }

    @AfterAll
    void tearDown() {
        System.out.println("=== RAGAS Eval Teardown ===");
        try {
            if (sc6109KbId != null) {
                kbFacade.deleteKnowledgeBase(sc6109KbId);
                System.out.println("  Deleted SC6109 KB");
            }
            if (sc6116KbId != null) {
                kbFacade.deleteKnowledgeBase(sc6116KbId);
                System.out.println("  Deleted SC6116 KB");
            }
            if (chatagentKbId != null) {
                kbFacade.deleteKnowledgeBase(chatagentKbId);
                System.out.println("  Deleted ChatAgent KB");
            }
        } catch (Exception e) {
            System.out.println("  Teardown error: " + e.getMessage());
        } finally {
            UserContext.clear();
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private String createKb(String name, String description) {
        UpsertKnowledgeBaseRequest req = new UpsertKnowledgeBaseRequest();
        req.setName(name);
        req.setDescription(description);
        return kbFacade.createKnowledgeBase(req);
    }

    private String findOrCreateAdminUser() {
        // Try to find an existing admin user
        List<String> adminIds = jdbcTemplate.queryForList(
                "SELECT id FROM t_user WHERE role = 'ADMIN' LIMIT 1", String.class);
        if (!adminIds.isEmpty()) {
            return adminIds.get(0);
        }
        // Try any user
        List<String> anyIds = jdbcTemplate.queryForList(
                "SELECT id FROM t_user LIMIT 1", String.class);
        if (!anyIds.isEmpty()) {
            return anyIds.get(0);
        }
        // Create a test admin user
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

        // Synchronous ingestion
        KnowledgeDocumentDTO docDto = docFacade.getKnowledgeDocument(resp.getDocumentId());
        ingestionService.ingestSync(spec.kbId, docDto);

        return resp.getDocumentId();
    }

    private String generateAnswer(String context, String query, String category) {
        String instruction = switch (category) {
            case "cross-domain" -> """
                    The question spans MULTIPLE distinct knowledge domains.
                    Based on the context:
                    1. Identify which parts of the context come from which domain.
                    2. Explain how the concepts relate, compare, or contrast across domains.
                    3. If the context doesn't fully bridge both domains, explicitly state what information is missing.
                    4. Address BOTH sides of the question — do not focus on only one domain.""";
            case "cross-topic" -> """
                    The question requires connecting concepts from MULTIPLE documents in the same domain.
                    Based on the context:
                    1. Identify relevant information from each document.
                    2. Synthesize a coherent answer connecting the concepts.
                    3. If some aspects are missing, state what's not covered.""";
            default -> """
                    Based on the following context, answer the question accurately and thoroughly.
                    - Use ONLY information from the context.
                    - If the context doesn't contain the answer, say so explicitly.
                    - Be specific and include relevant details.""";
        };

        String prompt = """
                %s

                Context:
                %s

                Question: %s

                Answer:""".formatted(instruction, context, query);
        return chatClient.prompt(prompt).call().content();
    }

    private JudgeResult judgeQuality(String context, String query, String answer) {
        String prompt = """
                You are an expert evaluator. Rate the answer on TWO metrics:

                1. Faithfulness (0.0-1.0): Does the answer use ONLY information from the context?
                   - 1.0: All claims are supported by the context
                   - 0.5: Mix of supported and unsupported claims
                   - 0.0: Answer fabricates facts not in the context

                2. Answer Relevancy (0.0-1.0): Does the answer address the question?
                   - 1.0: Directly and comprehensively answers the question
                   - 0.5: Partially addresses the question
                   - 0.0: Does not address the question at all

                Context:
                %s

                Question:
                %s

                Answer:
                %s

                Output ONLY two numbers separated by a comma: faithfulness,relevancy
                Example: 0.8,0.7""".formatted(context, query, answer);
        String response = chatClient.prompt(prompt).call().content();
        return parseJudgeResult(response);
    }

    private JudgeResult parseJudgeResult(String response) {
        if (response == null || response.isBlank()) return new JudgeResult(0.0, 0.0);
        try {
            String cleaned = response.trim();
            // Try to extract "faithfulness,relevancy" pattern
            String[] parts = cleaned.split("[,;]");
            if (parts.length >= 2) {
                double faith = parseSingleScore(parts[0]);
                double rel = parseSingleScore(parts[1]);
                return new JudgeResult(faith, rel);
            }
            // Fallback: single number treated as both
            double score = parseSingleScore(cleaned);
            return new JudgeResult(score, score);
        } catch (Exception e) {
            return new JudgeResult(0.0, 0.0);
        }
    }

    private double parseSingleScore(String text) {
        if (text == null || text.isBlank()) return 0.0;
        String cleaned = text.trim().replaceAll("[^0-9.]", "").trim();
        if (cleaned.isEmpty()) return 0.0;
        double score = Double.parseDouble(cleaned);
        return Math.max(0.0, Math.min(1.0, score));
    }

    private Set<String> resolveDocIds(List<String> shortNames) {
        Set<String> ids = new LinkedHashSet<>();
        for (String sn : shortNames) {
            String docId = shortNameToDocId.get(sn);
            if (docId != null) {
                ids.add(docId);
            }
        }
        return ids;
    }

    private Map<String, Integer> resolveGrades(Map<String, Integer> shortNameGrades) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : shortNameGrades.entrySet()) {
            String docId = shortNameToDocId.get(entry.getKey());
            if (docId != null) {
                resolved.put(docId, entry.getValue());
            }
        }
        return resolved;
    }

    private long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // =========================================================================
    // Data structures
    // =========================================================================

    private record FileSpec(String baseDir, String fileName, String kbId, String shortName, String mimeType) {}

    private record EvalQuery(
            String id,
            String category,
            String query,
            List<String> expectedLectureShortNames,
            Map<String, Integer> relevanceGrades
    ) {}

    private record PerQueryResult(
            EvalQuery query,
            List<String> rankedDocIds,
            String answer,
            double faithfulness,
            double answerRelevancy,
            double hitAt3,
            double hitAt5,
            double mrr,
            double ndcgAt5
    ) {}

    private record JudgeResult(double faithfulness, double answerRelevancy) {}

    // =========================================================================
    // File specifications
    // =========================================================================

    private List<FileSpec> buildFileSpecs() {
        List<FileSpec> specs = new ArrayList<>();

        // SC6109 — 7 lecture PDFs
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 01 - Discrete_Logarithm.pdf", sc6109KbId, "lec01-dl", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 01 - Privacy Issues.pdf", sc6109KbId, "lec01-privacy", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 01 - Digitial Signature 2026.pdf", sc6109KbId, "lec01-sig", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 02 - Factoring_and_Continued_Fractions w Annotation.pdf", sc6109KbId, "lec02-factoring", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SC6109 - Lecture 03 - Group Ring Signature w Annotation.pdf", sc6109KbId, "lec03-grs", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SE6019 - Lecture 04 - MPC ZK.pdf", sc6109KbId, "lec04-mpczk", "application/pdf"));
        specs.add(new FileSpec(SC6109_DIR, "SE6109 - Lecture 05 - PQC.pdf", sc6109KbId, "lec05-pqc", "application/pdf"));

        // SC6116 — 7 lecture PDFs
        specs.add(new FileSpec(SC6116_DIR, "1.Introduction.pdf", sc6116KbId, "lec01-intro", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "2.Basics of Probability.pdf", sc6116KbId, "lec02-prob", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "3.Decision Theory.pdf", sc6116KbId, "lec03-decision", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "4.Basics of Game Theory.pdf", sc6116KbId, "lec04-game", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "5.Auctions.pdf", sc6116KbId, "lec05-auction", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "6.Coordination.pdf", sc6116KbId, "lec06-coord", "application/pdf"));
        specs.add(new FileSpec(SC6116_DIR, "7.Selfish.pdf", sc6116KbId, "lec07-selfish", "application/pdf"));

        // ChatAgent — 6 project MD docs
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/01-llm-routing.md", chatagentKbId, "ca-llm-routing", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/02-agent-runtime.md", chatagentKbId, "ca-agent-runtime", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/04-rag-pipeline.md", chatagentKbId, "ca-rag-pipeline", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/05-intent-routing.md", chatagentKbId, "ca-intent-routing", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/06-mcp-integration.md", chatagentKbId, "ca-mcp-integration", "text/markdown"));
        specs.add(new FileSpec(CHATAGENT_DIR, "docs/summary/03-conversation-orchestration.md", chatagentKbId, "ca-conv-orch", "text/markdown"));

        return specs;
    }

    // =========================================================================
    // Golden queries
    // =========================================================================

    private List<EvalQuery> buildGoldenQueries() {
        List<EvalQuery> queries = new ArrayList<>();

        // --- SC6109 factual (10) ---
        queries.add(new EvalQuery("sc6109-f-01", "factual", "What is the discrete logarithm problem",
                List.of("lec01-dl"), grades("lec01-dl", 3)));
        queries.add(new EvalQuery("sc6109-f-02", "factual", "How does the discrete logarithm relate to cryptography",
                List.of("lec01-dl"), grades("lec01-dl", 3)));
        queries.add(new EvalQuery("sc6109-f-03", "factual", "What are the privacy issues in blockchain",
                List.of("lec01-privacy"), grades("lec01-privacy", 3)));
        queries.add(new EvalQuery("sc6109-f-04", "factual", "How do digital signatures work in blockchain",
                List.of("lec01-sig"), grades("lec01-sig", 3)));
        queries.add(new EvalQuery("sc6109-f-05", "factual", "What is integer factorization and continued fractions",
                List.of("lec02-factoring"), grades("lec02-factoring", 3)));
        queries.add(new EvalQuery("sc6109-f-06", "factual", "Explain group signatures and ring signatures",
                List.of("lec03-grs"), grades("lec03-grs", 3)));
        queries.add(new EvalQuery("sc6109-f-07", "factual", "What is multi-party computation MPC",
                List.of("lec04-mpczk"), grades("lec04-mpczk", 3)));
        queries.add(new EvalQuery("sc6109-f-08", "factual", "How do zero-knowledge proofs work",
                List.of("lec04-mpczk"), grades("lec04-mpczk", 3)));
        queries.add(new EvalQuery("sc6109-f-09", "factual", "What is post-quantum cryptography",
                List.of("lec05-pqc"), grades("lec05-pqc", 3)));
        queries.add(new EvalQuery("sc6109-f-10", "factual", "How are lattice-based cryptographic schemes constructed",
                List.of("lec05-pqc"), grades("lec05-pqc", 3)));

        // --- SC6116 factual (10) ---
        queries.add(new EvalQuery("sc6116-f-01", "factual", "What is game theory introduction",
                List.of("lec01-intro"), grades("lec01-intro", 3)));
        queries.add(new EvalQuery("sc6116-f-02", "factual", "What are the basic concepts of probability in games",
                List.of("lec02-prob"), grades("lec02-prob", 3)));
        queries.add(new EvalQuery("sc6116-f-03", "factual", "What is decision theory and expected utility",
                List.of("lec03-decision"), grades("lec03-decision", 3)));
        queries.add(new EvalQuery("sc6116-f-04", "factual", "What are the types of games in game theory",
                List.of("lec04-game"), grades("lec04-game", 3)));
        queries.add(new EvalQuery("sc6116-f-05", "factual", "How do auctions work in mechanism design",
                List.of("lec05-auction"), grades("lec05-auction", 3)));
        queries.add(new EvalQuery("sc6116-f-06", "factual", "What is coordination game and Nash equilibrium",
                List.of("lec06-coord"), grades("lec06-coord", 3)));
        queries.add(new EvalQuery("sc6116-f-07", "factual", "What is selfish routing and the price of anarchy",
                List.of("lec07-selfish"), grades("lec07-selfish", 3)));
        queries.add(new EvalQuery("sc6116-f-08", "factual", "How does Bayesian probability apply to games",
                List.of("lec02-prob"), grades("lec02-prob", 3)));
        queries.add(new EvalQuery("sc6116-f-09", "factual", "What is Vickrey auction and truthful bidding",
                List.of("lec05-auction"), grades("lec05-auction", 3)));
        queries.add(new EvalQuery("sc6116-f-10", "factual", "What is the difference between cooperative and non-cooperative games",
                List.of("lec04-game"), grades("lec04-game", 3)));

        // --- ChatAgent factual (5) ---
        queries.add(new EvalQuery("ca-f-01", "factual", "How does LLM routing work in ChatAgent",
                List.of("ca-llm-routing"), grades("ca-llm-routing", 3)));
        queries.add(new EvalQuery("ca-f-02", "factual", "What is the agent runtime architecture",
                List.of("ca-agent-runtime"), grades("ca-agent-runtime", 3)));
        queries.add(new EvalQuery("ca-f-03", "factual", "How does the RAG pipeline process documents",
                List.of("ca-rag-pipeline"), grades("ca-rag-pipeline", 3)));
        queries.add(new EvalQuery("ca-f-04", "factual", "How does intent routing classify user queries",
                List.of("ca-intent-routing"), grades("ca-intent-routing", 3)));
        queries.add(new EvalQuery("ca-f-05", "factual", "What is MCP integration in ChatAgent",
                List.of("ca-mcp-integration"), grades("ca-mcp-integration", 3)));

        // --- Cross-topic (5) ---
        queries.add(new EvalQuery("ct-01", "cross-topic", "How do discrete logarithm and digital signatures relate",
                List.of("lec01-dl", "lec01-sig"),
                multiGrades("lec01-dl", 3, "lec01-sig", 3)));
        queries.add(new EvalQuery("ct-02", "cross-topic", "What cryptographic primitives provide privacy",
                List.of("lec01-privacy", "lec04-mpczk", "lec03-grs"),
                multiGrades("lec01-privacy", 3, "lec04-mpczk", 2, "lec03-grs", 2)));
        queries.add(new EvalQuery("ct-03", "cross-topic", "How does probability theory relate to game theory auctions",
                List.of("lec02-prob", "lec05-auction"),
                multiGrades("lec02-prob", 2, "lec05-auction", 3)));
        queries.add(new EvalQuery("ct-04", "cross-topic", "What is the relationship between decision theory and coordination",
                List.of("lec03-decision", "lec06-coord"),
                multiGrades("lec03-decision", 2, "lec06-coord", 3)));
        queries.add(new EvalQuery("ct-05", "cross-topic", "How does conversation orchestration work with agent runtime",
                List.of("ca-conv-orch", "ca-agent-runtime"),
                multiGrades("ca-conv-orch", 3, "ca-agent-runtime", 2)));

        // --- Cross-domain (10) ---
        queries.add(new EvalQuery("cd-01", "cross-domain", "How does game theory apply to blockchain consensus",
                List.of("lec01-intro", "lec07-selfish", "lec01-privacy"),
                multiGrades("lec01-intro", 2, "lec07-selfish", 2, "lec01-privacy", 1)));
        queries.add(new EvalQuery("cd-02", "cross-domain", "What is the role of probability in cryptographic protocols",
                List.of("lec02-prob", "lec01-dl"),
                multiGrades("lec02-prob", 2, "lec01-dl", 2)));
        queries.add(new EvalQuery("cd-03", "cross-domain", "How do auctions relate to mechanism design in blockchain",
                List.of("lec05-auction", "lec01-privacy"),
                multiGrades("lec05-auction", 3, "lec01-privacy", 1)));
        queries.add(new EvalQuery("cd-04", "cross-domain", "What is the relationship between zero knowledge and game theory",
                List.of("lec04-mpczk", "lec04-game"),
                multiGrades("lec04-mpczk", 2, "lec04-game", 2)));
        queries.add(new EvalQuery("cd-05", "cross-domain", "How does selfish mining relate to blockchain privacy",
                List.of("lec07-selfish", "lec01-privacy"),
                multiGrades("lec07-selfish", 3, "lec01-privacy", 2)));
        queries.add(new EvalQuery("cd-06", "cross-domain", "How does RAG retrieval compare to information retrieval in cryptography",
                List.of("ca-rag-pipeline", "lec01-privacy"),
                multiGrades("ca-rag-pipeline", 2, "lec01-privacy", 1)));
        queries.add(new EvalQuery("cd-07", "cross-domain", "What is the relationship between game theory and AI agent design",
                List.of("lec04-game", "ca-agent-runtime"),
                multiGrades("lec04-game", 2, "ca-agent-runtime", 2)));
        queries.add(new EvalQuery("cd-08", "cross-domain", "How do digital signatures relate to MCP tool authentication",
                List.of("lec01-sig", "ca-mcp-integration"),
                multiGrades("lec01-sig", 1, "ca-mcp-integration", 2)));
        queries.add(new EvalQuery("cd-09", "cross-domain", "How does intent classification compare to game theory decision making",
                List.of("ca-intent-routing", "lec03-decision"),
                multiGrades("ca-intent-routing", 2, "lec03-decision", 2)));
        queries.add(new EvalQuery("cd-10", "cross-domain", "How does RAG vector search relate to lattice-based cryptography",
                List.of("ca-rag-pipeline", "lec05-pqc"),
                multiGrades("ca-rag-pipeline", 2, "lec05-pqc", 1)));

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
