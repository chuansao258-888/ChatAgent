package com.yulong.chatagent.eval.v2.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.IncrementalSummarizer;
import com.yulong.chatagent.conversation.summary.SummaryResult;
import com.yulong.chatagent.eval.v2.EvalArtifactWriter;
import com.yulong.chatagent.eval.v2.EvalConfigFingerprint;
import com.yulong.chatagent.eval.v2.EvalRunManifest;
import com.yulong.chatagent.memory.application.ExtractedMemory;
import com.yulong.chatagent.memory.application.ExtractionResult;
import com.yulong.chatagent.memory.application.LongTermMemoryExtractor;
import com.yulong.chatagent.memory.application.UserMemoryIndexService;
import com.yulong.chatagent.memory.application.UserMemorySearchHit;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real memory pipeline evaluation using real Ollama embeddings, Milvus L3 recall,
 * and LLM compaction/extraction.
 *
 * <p>Requires PostgreSQL, Ollama (bge-m3), Milvus, and a configured LLM provider.
 * Skips gracefully via {@link MemoryInfrastructureCondition} (JUnit ExecutionCondition)
 * before Spring context loads, so Surefire reports Tests run: N, Skipped: N.
 * Tagged {@code eval-real} to exclude from default CI.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "chatagent.mq.enabled=false",
                "chatagent.mq.dispatchers.agent-run-enabled=false"
        }
)
@ActiveProfiles("eval-real-memory")
@Tag("eval-v2")
@Tag("eval-real")
@ExtendWith(MemoryInfrastructureCondition.class)
class MemoryExportEvalTest {

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
    private static final String SUMMARY_MODEL = setting(
            "chatagent.eval.summaryModel",
            "CHATAGENT_MEMORY_SUMMARY_MODEL",
            "deepseek-chat"
    );
    private static final String EXTRACTOR_MODEL = setting(
            "chatagent.eval.extractorModel",
            "CHATAGENT_MEMORY_L3_EXTRACTOR_MODEL",
            "deepseek-chat"
    );

    private static final String DATASET_ID = "memory-v2-dialogues";
    private static final int SMOKE_DIALOGUE_COUNT = 10;
    private static final int L3_TOP_K = 3;
    /** Splits that must appear in the exported dataset root for tune-suite compatibility. */
    private static final Set<String> REQUIRED_SPLITS = Set.of("calibration", "development", "holdout");
    private static final int MIN_PER_SPLIT = 2;

    private static final String EVAL_PREFIX = "eval-memory-";
    private static final String EVAL_USER_A = "eval-memory-user-a";
    private static final String EVAL_USER_B = "eval-memory-user-b";
    /** Cached user_id from t_user, resolved lazily for FK constraints. */
    private String evalUserId;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Autowired
    private IncrementalSummarizer summarizer;

    @Autowired
    private LongTermMemoryExtractor extractor;

    @Autowired
    private UserMemoryIndexService userMemoryIndexService;

    @Autowired
    private OllamaEmbeddingClient embeddingClient;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatSessionSummaryRepository summaryRepository;

    @Autowired
    private MilvusClientV2 milvusClient;

    private final List<String> createdMessageIds = new ArrayList<>();
    private final List<String> createdSessionIds = new ArrayList<>();
    private final List<String> createdMilvusMemoryIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Delete eval-owned chat messages
        for (String messageId : List.copyOf(createdMessageIds)) {
            try {
                chatMessageRepository.deleteById(messageId);
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        createdMessageIds.clear();
        // Delete eval-owned sessions (chat_session rows)
        for (String sessionId : List.copyOf(createdSessionIds)) {
            try {
                summaryRepository.deleteBySessionId(sessionId);
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
            try {
                chatSessionRepository.deleteById(sessionId);
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        createdSessionIds.clear();
        // Delete eval-owned Milvus memories by ID
        for (String memoryId : List.copyOf(createdMilvusMemoryIds)) {
            try {
                milvusClient.delete(DeleteReq.builder()
                        .collectionName(evalMilvusCollection())
                        .filter("memory_id == \"" + escapeMilvusFilter(memoryId) + "\"")
                        .build());
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        createdMilvusMemoryIds.clear();
    }

    @BeforeEach
    void verifyCleanupState() {
        // Sanity: no stale eval-prefixed data before each test
        for (String sessionId : createdSessionIds) {
            assertThat(chatMessageRepository.findBySessionId(sessionId))
                    .as("Stale chat messages for session: " + sessionId)
                    .isEmpty();
            assertThat(summaryRepository.findBySessionId(sessionId))
                    .as("Stale summary for session: " + sessionId)
                    .isNull();
        }
    }

    @Test
    void dbSetupAndTeardown() {
        // Insert MTRAG dialogue turns and verify summarizer returns non-empty result
        JsonNode dialogue = findMultiTurnDialogue();
        String sessionId = EVAL_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        createdSessionIds.add(sessionId);
        ensureSession(sessionId);
        long maxSeqNo = insertDialogueMessages(sessionId, dialogue);

        SummaryResult result = summarizer.summarizeWithDetails(sessionId, maxSeqNo);

        assertThat(result.turns())
                .as("summarizeWithDetails should extract pending turns after fixture insertion")
                .isNotEmpty();
        assertThat(result.turns().get(0).userMessages())
                .as("Extracted turns should include user messages from the fixture")
                .isNotEmpty();
    }

    @Test
    void userIsolation() {
        // Insert L3 memories for two users, verify user-a search excludes user-b
        assumeOllamaEmbeddingAvailable();
        Map<String, String> memoryA = Map.of(
                "memoryId", EVAL_PREFIX + "mem-a-" + randomSuffix(),
                "userId", EVAL_USER_A,
                "type", "fact",
                "status", "active",
                "content", "The Arizona Cardinals NFL team moved from Chicago to St. Louis in 1960 and then to Arizona in 1988.",
                "tags", "[\"sports\",\"nfl\",\"cardinals\"]"
        );
        Map<String, String> memoryB = Map.of(
                "memoryId", EVAL_PREFIX + "mem-b-" + randomSuffix(),
                "userId", EVAL_USER_B,
                "type", "fact",
                "status", "active",
                "content", "FEMA recommends safe rooms designed to provide shelter from tornadoes.",
                "tags", "[\"disaster\",\"fema\",\"safety\"]"
        );

        float[] embeddingA = embeddingClient.embed(memoryA.get("content"));
        float[] embeddingB = embeddingClient.embed(memoryB.get("content"));

        boolean indexedA = userMemoryIndexService.upsertMemory(
                memoryA.get("memoryId"), memoryA.get("userId"), memoryA.get("type"),
                memoryA.get("status"), memoryA.get("content"), memoryA.get("tags"), embeddingA);
        boolean indexedB = userMemoryIndexService.upsertMemory(
                memoryB.get("memoryId"), memoryB.get("userId"), memoryB.get("type"),
                memoryB.get("status"), memoryB.get("content"), memoryB.get("tags"), embeddingB);
        assertThat(indexedA).isTrue();
        assertThat(indexedB).isTrue();
        trackMilvusMemory(memoryA.get("memoryId"));
        trackMilvusMemory(memoryB.get("memoryId"));

        // Search for user-a with a Cardinals-related query
        float[] queryEmbedding = embeddingClient.embed("Arizona Cardinals history");
        List<UserMemorySearchHit> hits = userMemoryIndexService.search(EVAL_USER_A, queryEmbedding, L3_TOP_K);

        assertThat(hits)
                .as("L3 recall should return results for user-a")
                .isNotEmpty();
        assertThat(hits.stream().map(UserMemorySearchHit::memoryId))
                .as("User-a recall should include only user-a's memories")
                .contains(memoryA.get("memoryId"))
                .doesNotContain(memoryB.get("memoryId"));
    }

    @Test
    void inactiveMemoryExclusion() {
        // Insert one active and one archived memory, verify search excludes archived
        assumeOllamaEmbeddingAvailable();
        String userId = EVAL_PREFIX + "inactive-test-" + randomSuffix();
        Map<String, String> active = Map.of(
                "memoryId", EVAL_PREFIX + "active-" + randomSuffix(),
                "userId", userId,
                "type", "preference",
                "status", "active",
                "content", "User prefers detailed explanations with historical context.",
                "tags", "[\"preference\",\"detail\"]"
        );
        Map<String, String> archived = Map.of(
                "memoryId", EVAL_PREFIX + "archived-" + randomSuffix(),
                "userId", userId,
                "type", "preference",
                "status", "archived",
                "content", "User used to prefer brief one-line answers but no longer does.",
                "tags", "[\"preference\",\"brief\"]"
        );

        float[] embeddingA = embeddingClient.embed(active.get("content"));
        float[] embeddingB = embeddingClient.embed(archived.get("content"));

        assertThat(userMemoryIndexService.upsertMemory(
                active.get("memoryId"), userId, active.get("type"),
                active.get("status"), active.get("content"), active.get("tags"), embeddingA))
                .isTrue();
        assertThat(userMemoryIndexService.upsertMemory(
                archived.get("memoryId"), userId, archived.get("type"),
                archived.get("status"), archived.get("content"), archived.get("tags"), embeddingB))
                .isTrue();
        trackMilvusMemory(active.get("memoryId"));
        trackMilvusMemory(archived.get("memoryId"));

        float[] queryEmbedding = embeddingClient.embed("user preference for answer style");
        List<UserMemorySearchHit> hits = userMemoryIndexService.search(userId, queryEmbedding, L3_TOP_K);

        assertThat(hits.stream().map(UserMemorySearchHit::memoryId))
                .as("L3 recall should exclude archived/inactive memories")
                .contains(active.get("memoryId"))
                .doesNotContain(archived.get("memoryId"));
    }

    @Test
    void exportsRealMemoryPipeline() throws Exception {
        assumeOllamaEmbeddingAvailable();
        Path repositoryRoot = findRepositoryRoot();
        Path phase3Root = repositoryRoot.resolve("artifacts/eval/phase3");
        Path datasetPath = phase3Root.resolve("datasets/memory/memory-v2-dialogues.jsonl");
        Path manifestPath = phase3Root.resolve("manifests/datasets/memory-v2-dialogues.json");
        assumeTrue(Files.exists(datasetPath) && Files.exists(manifestPath),
                "Run Phase 3 MTRAG memory preparation before the Phase 10b smoke");

        List<JsonNode> rows = loadDatasetRows(datasetPath);
        JsonNode manifest = OBJECT_MAPPER.readTree(manifestPath.toFile());
        List<String> sourceIdList = toStrings(manifest.path("sourceIds"));
        List<Map<String, Object>> samples = new ArrayList<>();
        String exportTimestamp = Instant.now().toString();
        String runId = "phase10b-memory-real-smoke";

        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            String sampleId = row.path("sampleId").asText();
            // Keep session ID within VARCHAR(64): eval-memory- (12) + 8-char-uuid = 20 chars
            String sessionId = EVAL_PREFIX + UUID.randomUUID().toString().substring(0, 8);
            createdSessionIds.add(sessionId);
            ensureSession(sessionId);

            long maxSeqNo = insertDialogueMessages(sessionId, row);
            SummaryResult l1Result = summarizer.summarizeWithDetails(sessionId, maxSeqNo);

            ExtractionResult l3Result = l1Result.turns().isEmpty()
                    ? ExtractionResult.failure()
                    : extractor.extract(l1Result.turns());

            if (l3Result.success() && !l3Result.memories().isEmpty()) {
                indexL3Memories(l3Result.memories(), sessionId, embeddingClient, userMemoryIndexService);
            }

            samples.add(toSample(row, l1Result, l3Result));
        }

        EvalArtifactWriter artifactWriter = new EvalArtifactWriter(
                repositoryRoot.resolve("artifacts/eval/phase10b"));
        Map<String, Object> config = runConfig(sourceIdList);
        String configFingerprint = EvalConfigFingerprint.sha256(config);
        Map<String, Object> metrics = Map.of("sampleCount", samples.size());
        EvalRunManifest runManifest = new EvalRunManifest(
                runId, "memory", "smoke", exportTimestamp,
                runtimeMetadata("chatagent.eval.gitBranch", "GIT_BRANCH"),
                runtimeMetadata("chatagent.eval.gitSha", "GIT_COMMIT"),
                manifest.path("datasetId").asText(),
                manifest.path("datasetHash").asText(),
                config, configFingerprint, Map.of(), Map.of(),
                List.of("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl"),
                null
        );
        Path runDirectory = artifactWriter.writeRun(runManifest, metrics, samples, List.of());
        writeDatasetRoot(runDirectory, manifest.path("datasetId").asText(), sourceIdList, rows, samples, exportTimestamp);

        assertThat(samples).hasSizeGreaterThanOrEqualTo(REQUIRED_SPLITS.size() * MIN_PER_SPLIT);
        assertThat(runDirectory).exists();
        assertThat(runDirectory.resolve("manifest.json")).exists();
        assertThat(runDirectory.resolve("samples.jsonl")).exists();
        assertThat(runDirectory.resolve("datasets/memory/" + DATASET_ID + ".jsonl")).exists();
        assertThat(runDirectory.resolve("manifests/datasets/" + DATASET_ID + ".json")).exists();
        // Verify exported split manifest has all required splits for tune-suite compatibility
        JsonNode exportedSplitManifest = OBJECT_MAPPER.readTree(
                runDirectory.resolve("manifests/splits/" + DATASET_ID + ".json").toFile());
        for (String required : REQUIRED_SPLITS) {
            assertThat(exportedSplitManifest.path("splits").has(required))
                    .as("Exported split manifest must include '%s' split for tune-suite", required)
                    .isTrue();
        }
    }

    @Test
    void nonEvalDataSurvivesCleanup() {
        assumeOllamaEmbeddingAvailable();

        // Seed a non-eval canary memory (no EVAL_PREFIX) — must NOT be tracked by cleanup
        String nonEvalMemoryId = "non-eval-canary-" + randomSuffix();
        String nonEvalUserId = "non-eval-canary-user-" + randomSuffix();
        String nonEvalContent = "Canary memory for eval preservation test " + UUID.randomUUID();
        float[] nonEvalEmbedding = embeddingClient.embed(nonEvalContent);
        assertThat(userMemoryIndexService.upsertMemory(
                nonEvalMemoryId, nonEvalUserId, "fact", "active",
                nonEvalContent, "[\"canary\"]", nonEvalEmbedding))
                .as("Non-eval canary memory should index successfully").isTrue();

        // Seed a tracked eval memory — will be cleaned up by @AfterEach
        String evalMemoryId = EVAL_PREFIX + "preservation-check-" + randomSuffix();
        String evalUserId = EVAL_PREFIX + "preservation-user-" + randomSuffix();
        String evalContent = "Eval memory that should be deleted by cleanup";
        float[] evalEmbedding = embeddingClient.embed(evalContent);
        assertThat(userMemoryIndexService.upsertMemory(
                evalMemoryId, evalUserId, "fact", "active",
                evalContent, "[\"eval\"]", evalEmbedding))
                .isTrue();
        trackMilvusMemory(evalMemoryId);

        // Run the same cleanup pattern as @AfterEach for the tracked eval memory
        for (String memoryId : List.copyOf(createdMilvusMemoryIds)) {
            try {
                milvusClient.delete(DeleteReq.builder()
                        .collectionName(evalMilvusCollection())
                        .filter("memory_id == \"" + escapeMilvusFilter(memoryId) + "\"")
                        .build());
            } catch (Exception ignored) {}
        }

        // Assert the non-eval canary memory is still searchable
        float[] queryEmbedding = embeddingClient.embed(nonEvalContent);
        List<UserMemorySearchHit> hits = userMemoryIndexService.search(nonEvalUserId, queryEmbedding, L3_TOP_K);
        assertThat(hits.stream().map(UserMemorySearchHit::memoryId))
                .as("Non-eval canary memory should survive eval cleanup")
                .contains(nonEvalMemoryId);

        // Clean up the non-eval canary manually (not tracked by @AfterEach)
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(evalMilvusCollection())
                    .filter("memory_id == \"" + escapeMilvusFilter(nonEvalMemoryId) + "\"")
                    .build());
        } catch (Exception ignored) {}
    }

    // ── DB fixture helpers ────────────────────────────────────────────

    /**
     * Creates a minimal chat_session row so that chat_message FK constraints are satisfied.
     * Reuses any existing t_user row for the user_id FK.
     */
    private void ensureSession(String sessionId) {
        if (chatSessionRepository.findById(sessionId) != null) {
            return;
        }
        // Resolve a valid user_id from t_user (user insert uses auto-generated UUID, so we must
        // find an existing one rather than trying to create with a specific ID).
        if (evalUserId == null) {
            List<UserDTO> users = userRepository.findPage(null, "ACTIVE", 1, 0);
            assertThat(users).as("Need at least one active t_user row for eval session FK").isNotEmpty();
            evalUserId = users.get(0).getId();
        }
        chatSessionRepository.save(ChatSessionDTO.builder()
                .id(sessionId)
                .userId(evalUserId)
                .title("eval-memory-test")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private long insertDialogueMessages(String sessionId, JsonNode row) {
        long seqNo = 0L;
        for (JsonNode turn : row.path("turns")) {
            String speaker = turn.path("speaker").asText();
            String text = turn.path("text").asText();
            String turnId = UUID.randomUUID().toString();
            ChatMessageDTO.RoleType role = "user".equals(speaker)
                    ? ChatMessageDTO.RoleType.USER
                    : ChatMessageDTO.RoleType.ASSISTANT;

            ChatMessageDTO message = ChatMessageDTO.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .role(role)
                    .content(text)
                    .turnCompleted(false)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            chatMessageRepository.save(message);
            createdMessageIds.add(message.getId());
            // seqNo is assigned by DB; use DB-returned value if available
            seqNo = message.getSeqNo() != null ? Math.max(seqNo, message.getSeqNo()) : seqNo + 1;
        }
        // Refresh seqNo from DB to get the actual max
        Long dbMax = chatMessageRepository.findMaxSeqNoBySessionId(sessionId);
        return dbMax != null ? dbMax : seqNo;
    }

    private void indexL3Memories(
            List<ExtractedMemory> memories,
            String userId,
            OllamaEmbeddingClient embedder,
            UserMemoryIndexService indexService
    ) {
        for (int i = 0; i < memories.size(); i++) {
            ExtractedMemory memory = memories.get(i);
            String memoryId = EVAL_PREFIX + "mem-" + userId.substring(EVAL_PREFIX.length()) + "-" + i;
            float[] embedding = embedder.embed(memory.content());
            indexService.upsertMemory(
                    memoryId, userId, memory.type(), "active",
                    memory.content(), serializeTags(memory.tags()), embedding);
            trackMilvusMemory(memoryId);
        }
    }

    // ── Sample serialization ──────────────────────────────────────────

    private Map<String, Object> toSample(JsonNode row, SummaryResult l1Result, ExtractionResult l3Result) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleId", row.path("sampleId").asText());
        sample.put("datasetId", row.path("datasetId").asText());
        sample.put("sourceGroupId", row.path("sourceGroupId").asText());
        sample.put("split", row.path("split").asText());
        sample.put("turns", toTurnsArray(row));
        sample.put("expectedResponse", row.path("expectedResponse").asText());
        sample.put("referenceContextIds", toStrings(row.path("referenceContextIds")));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("domain", row.path("metadata").path("domain").asText());
        metadata.put("questionType", toStrings(row.path("metadata").path("questionType")));
        metadata.put("multiTurn", toStrings(row.path("metadata").path("multiTurn")));
        metadata.put("answerability", toStrings(row.path("metadata").path("answerability")));
        metadata.put("sourceTaskId", row.path("metadata").path("sourceTaskId").asText());
        sample.put("metadata", metadata);

        Map<String, Object> moduleOutputs = new LinkedHashMap<>();
        moduleOutputs.put("l1Summary", serializeSummaryResult(l1Result));
        moduleOutputs.put("l3Extraction", serializeExtractionResult(l3Result));
        moduleOutputs.put("provider", Map.of(
                "summaryModel", SUMMARY_MODEL,
                "extractorModel", EXTRACTOR_MODEL,
                "embeddingModel", EMBEDDING_MODEL
        ));
        sample.put("moduleOutputs", moduleOutputs);

        return sample;
    }

    private List<Map<String, Object>> toTurnsArray(JsonNode row) {
        List<Map<String, Object>> turns = new ArrayList<>();
        for (JsonNode turn : row.path("turns")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("speaker", turn.path("speaker").asText());
            entry.put("text", turn.path("text").asText());
            turns.add(entry);
        }
        return turns;
    }

    private Map<String, Object> serializeSummaryResult(SummaryResult result) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("updated", result.updated());
        serialized.put("turnCount", result.turns().size());
        serialized.put("segmentCount", result.segments().size());
        serialized.put("synopsis", result.synopsis() != null ? result.synopsis() : "");
        serialized.put("range", Map.of(
                "sessionId", result.range().sessionId(),
                "lastSummarizedSeqNo", result.range().lastSummarizedSeqNo(),
                "anchorSeqNo", result.range().anchorSeqNo()
        ));
        return serialized;
    }

    private Map<String, Object> serializeExtractionResult(ExtractionResult result) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("success", result.success());
        List<Map<String, Object>> memories = new ArrayList<>();
        for (ExtractedMemory memory : result.memories()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", memory.type());
            entry.put("content", memory.content());
            entry.put("tags", memory.tags());
            memories.add(entry);
        }
        serialized.put("memories", memories);
        return serialized;
    }

    // ── Dataset root writer ───────────────────────────────────────────

    private void writeDatasetRoot(
            Path runDirectory,
            String datasetId,
            List<String> sourceIds,
            List<JsonNode> rows,
            List<Map<String, Object>> samples,
            String exportTimestamp
    ) throws Exception {
        List<Map<String, Object>> datasetRows = datasetRows(rows, samples);
        Path datasetPath = runDirectory.resolve(Path.of("datasets", "memory", datasetId + ".jsonl"));
        Files.createDirectories(datasetPath.getParent());
        writeJsonLines(datasetPath, datasetRows);

        Map<String, Object> splitManifest = splitManifest(datasetId, datasetRows);
        Path splitPath = runDirectory.resolve(Path.of("manifests", "splits", datasetId + ".json"));
        Files.createDirectories(splitPath.getParent());
        OBJECT_MAPPER.writeValue(splitPath.toFile(), splitManifest);

        Map<String, Object> datasetManifest = datasetManifest(
                datasetId, sourceIds, datasetRows, splitManifest,
                sha256(datasetPath), sha256(splitPath), exportTimestamp);
        Path manifestPath = runDirectory.resolve(Path.of("manifests", "datasets", datasetId + ".json"));
        Files.createDirectories(manifestPath.getParent());
        OBJECT_MAPPER.writeValue(manifestPath.toFile(), datasetManifest);
    }

    private List<Map<String, Object>> datasetRows(List<JsonNode> sourceRows, List<Map<String, Object>> samples) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            Map<String, Object> sample = samples.get(i);
            JsonNode source = sourceRows.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sampleId", sample.get("sampleId"));
            row.put("datasetId", sample.get("datasetId"));
            row.put("sourceGroupId", sample.get("sourceGroupId"));
            row.put("split", sample.get("split"));
            row.put("turns", sample.get("turns"));
            row.put("expectedResponse", sample.get("expectedResponse"));
            row.put("referenceContextIds", sample.get("referenceContextIds"));
            row.put("metadata", sample.get("metadata"));
            row.put("moduleOutputs", sample.get("moduleOutputs"));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> splitManifest(String datasetId, List<Map<String, Object>> rows) {
        Map<String, LinkedHashSet<String>> groupsBySplit = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            groupsBySplit.computeIfAbsent(row.get("split").toString(), ignored -> new LinkedHashSet<>())
                    .add(row.get("sourceGroupId").toString());
        }
        Map<String, Object> splits = new LinkedHashMap<>();
        groupsBySplit.forEach((split, groupIds) -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("groupIds", new ArrayList<>(groupIds));
            details.put("groupHash", sha256(groupIds));
            splits.put(split, details);
        });
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("datasetId", datasetId);
        manifest.put("splits", splits);
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> datasetManifest(
            String datasetId,
            List<String> sourceIds,
            List<Map<String, Object>> rows,
            Map<String, Object> splitManifest,
            String datasetHash,
            String splitManifestHash,
            String exportTimestamp
    ) {
        Map<String, Object> splits = new LinkedHashMap<>();
        Map<String, Object> splitDetails = (Map<String, Object>) splitManifest.get("splits");
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
        rows.forEach(row -> groupIds.add(row.get("sourceGroupId").toString()));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("datasetId", datasetId);
        manifest.put("version", 1);
        manifest.put("sourceIds", sourceIds);
        manifest.put("recordSchema", "eval-memory-dataset-record.schema.json");
        manifest.put("localPath", "datasets/memory/" + datasetId + ".jsonl");
        manifest.put("datasetHash", datasetHash);
        manifest.put("splitManifestPath", "manifests/splits/" + datasetId + ".json");
        manifest.put("splitManifestHash", splitManifestHash);
        manifest.put("recordCount", rows.size());
        manifest.put("groupCount", groupIds.size());
        manifest.put("splits", splits);
        manifest.put("provenance", provenance(exportTimestamp));
        return manifest;
    }

    private Map<String, Object> provenance(String exportTimestamp) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("provider", "deepseek");
        provenance.put("modelName", SUMMARY_MODEL);
        provenance.put("embeddingModel", EMBEDDING_MODEL);
        provenance.put("exportTimestamp", exportTimestamp);
        return provenance;
    }

    // ── Infrastructure probes ─────────────────────────────────────────

    /** Runs all infrastructure probes; called by {@link MemoryInfrastructureCondition}. */
    static boolean probeAll() {
        return probeOllama() && probeMilvus() && probeLlmProvider() && probePostgres();
    }

    private static boolean probeOllama() {
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
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeMilvus() {
        String host = configValue("CHATAGENT_MILVUS_HOST", "localhost");
        int port = Integer.parseInt(configValue("CHATAGENT_MILVUS_PORT", "19530"));
        return tcpProbe(host, port);
    }

    private static boolean probeLlmProvider() {
        String apiKey = System.getenv("CHATAGENT_DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("CHATAGENT_DEEPSEEK_API_KEY", "");
        }
        if (apiKey.isBlank()) {
            return false;
        }
        // Validate key + model + endpoint with a minimal 1-token completion request
        String baseUrl = configValue("CHATAGENT_DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        try {
            String probeUrl = baseUrl + "/chat/completions";
            String probeBody = "{\"model\":\"" + SUMMARY_MODEL
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(probeUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(probeBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probePostgres() {
        String dbUrl = configValue("CHATAGENT_DB_URL", "jdbc:postgresql://localhost:5432/chatagent");
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
        } catch (Exception e) {
            // Use defaults
        }
        return tcpProbe(host, port);
    }

    /**
     * Pure TCP socket probe — does not assume HTTP or any application protocol.
     * Works for PostgreSQL, Milvus, and any TCP service.
     */
    private static boolean tcpProbe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String configValue(String envName, String defaultValue) {
        String value = System.getenv(envName);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return defaultValue;
    }

    private static String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "api.deepseek.com";
        }
    }

    private String evalMilvusCollection() {
        return "chat_user_memory_eval";
    }

    private static String escapeMilvusFilter(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void assumeOllamaEmbeddingAvailable() {
        try {
            embeddingClient.embed("probe");
        } catch (Exception e) {
            assumeTrue(false, "Ollama embedding probe failed: " + e.getMessage());
        }
    }

    private void trackMilvusMemory(String memoryId) {
        createdMilvusMemoryIds.add(memoryId);
    }

    // ── Utility methods ───────────────────────────────────────────────

    private static String setting(String propertyName, String environmentName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
    }

    private JsonNode findMultiTurnDialogue() {
        Path repositoryRoot = findRepositoryRoot();
        Path datasetPath = repositoryRoot.resolve("artifacts/eval/phase3/datasets/memory/memory-v2-dialogues.jsonl");
        assumeTrue(Files.exists(datasetPath),
                "Run Phase 3 MTRAG memory preparation before the Phase 10b DB tests");
        try {
            List<JsonNode> rows = loadDatasetRows(datasetPath);
            // Prefer a multi-turn dialogue with at least 3 turns and agent responses
            return rows.stream()
                    .filter(row -> row.path("turns").size() >= 3)
                    .filter(row -> {
                        for (JsonNode turn : row.path("turns")) {
                            if ("agent".equals(turn.path("speaker").asText())) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .findFirst()
                    .orElse(rows.get(0));
        } catch (Exception e) {
            assumeTrue(false, "Failed to load MTRAG dataset for fixture: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads rows from the dataset, selecting a balanced sample that covers all
     * {@link #REQUIRED_SPLITS} so the exported dataset root can be used with
     * {@code tune-suite --suite memory-v2}.
     *
     * <p>Takes up to {@link #MIN_PER_SPLIT} rows from each required split first,
     * then fills remaining slots up to {@link #SMOKE_DIALOGUE_COUNT} in original order.
     */
    private List<JsonNode> loadDatasetRows(Path path) throws Exception {
        List<JsonNode> allRows = new ArrayList<>();
        try (Stream<String> lines = Files.lines(path)) {
            lines.forEach(line -> {
                try { allRows.add(OBJECT_MAPPER.readTree(line)); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        return balanceSplits(allRows, SMOKE_DIALOGUE_COUNT);
    }

    /**
     * Selects rows covering all required splits within the given budget.
     * Guarantees at least {@link #MIN_PER_SPLIT} rows from each split in
     * {@link #REQUIRED_SPLITS}, then fills remaining slots in original order.
     */
    private List<JsonNode> balanceSplits(List<JsonNode> allRows, int budget) {
        Map<String, List<Integer>> bySplit = new LinkedHashMap<>();
        for (int i = 0; i < allRows.size(); i++) {
            bySplit.computeIfAbsent(allRows.get(i).path("split").asText(), k -> new ArrayList<>()).add(i);
        }
        Set<Integer> selected = new LinkedHashSet<>();
        // Reserve MIN_PER_SPLIT from each required split
        for (String split : REQUIRED_SPLITS) {
            List<Integer> indices = bySplit.getOrDefault(split, List.of());
            int count = Math.min(MIN_PER_SPLIT, indices.size());
            for (int i = 0; i < count && selected.size() < budget; i++) {
                selected.add(indices.get(i));
            }
        }
        // Fill remaining slots in original order
        for (int i = 0; i < allRows.size() && selected.size() < budget; i++) {
            selected.add(i);
        }
        return selected.stream().sorted().map(allRows::get).collect(Collectors.toList());
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String serializeTags(List<String> tags) {
        try {
            return OBJECT_MAPPER.writeValueAsString(tags != null ? tags : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    private String runtimeMetadata(String propertyName, String environmentName) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank() ? "unknown" : environmentValue;
    }

    private Map<String, Object> runConfig(List<String> sourceIds) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("embeddingModel", EMBEDDING_MODEL);
        config.put("summaryModel", SUMMARY_MODEL);
        config.put("extractorModel", EXTRACTOR_MODEL);
        config.put("sampleCount", SMOKE_DIALOGUE_COUNT);
        config.put("sourceIds", sourceIds);
        return config;
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("AGENTS.md"))) {
            current = current.getParent();
        }
        assumeTrue(current != null, "Repository root with AGENTS.md was not found");
        return current;
    }

    private List<String> toStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private void writeJsonLines(Path path, List<Map<String, Object>> rows) throws Exception {
        StringBuilder output = new StringBuilder();
        for (Map<String, Object> row : rows) {
            output.append(OBJECT_MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(row))
                    .append('\n');
        }
        Files.writeString(path, output);
    }

    private String sha256(Path path) throws Exception {
        return sha256(Files.readAllBytes(path));
    }

    private String sha256(Object value) {
        try {
            return sha256(OBJECT_MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash evaluation manifest value", e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
