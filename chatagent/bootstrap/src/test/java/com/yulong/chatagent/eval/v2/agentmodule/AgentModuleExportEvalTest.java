package com.yulong.chatagent.eval.v2.agentmodule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.agent.port.AgentRepository;
import com.yulong.chatagent.agent.runtime.AgentToolCallbackFactory;
import com.yulong.chatagent.eval.v2.EvalArtifactWriter;
import com.yulong.chatagent.eval.v2.EvalConfigFingerprint;
import com.yulong.chatagent.eval.v2.EvalRunManifest;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.IntentRoutingResult;
import com.yulong.chatagent.intent.application.IntentRouter;
import com.yulong.chatagent.intent.application.IntentTreeCacheManager;
import com.yulong.chatagent.intent.application.QueryRewriter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real agent module pipeline evaluation using real IntentRouter (LLM classifier),
 * QueryRewriter (LLM semantic rewrite), and AgentToolCallbackFactory.
 *
 * <p>Requires PostgreSQL, Redis, and a configured LLM provider.
 * Skips gracefully via {@link AgentModuleInfrastructureCondition} (JUnit ExecutionCondition)
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
@ActiveProfiles("eval-real-agent-modules")
@Tag("eval-v2")
@Tag("eval-real")
@ExtendWith(AgentModuleInfrastructureCondition.class)
class AgentModuleExportEvalTest {

    private static final String DATASET_ID = "memory-v2-dialogues";
    private static final String DATASET_SUBDIR = "agent-modules";
    private static final int SMOKE_DIALOGUE_COUNT = 10;
    /** Splits that must appear in the exported dataset root for tune-suite compatibility. */
    private static final Set<String> REQUIRED_SPLITS = Set.of("calibration", "development", "holdout");
    private static final int MIN_PER_SPLIT = 2;

    private static final String LLM_MODEL = setting(
            "chatagent.eval.summaryModel",
            "CHATAGENT_MEMORY_SUMMARY_MODEL",
            "deepseek-chat"
    );

    /** System assistant agent ID (seeded by V2 migration). */
    private static final String SYSTEM_AGENT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
    private static final int EVAL_INTENT_VERSION = 99999;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Autowired
    private IntentRouter intentRouter;

    @Autowired
    private QueryRewriter queryRewriter;

    @Autowired
    private AgentToolCallbackFactory toolCallbackFactory;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private IntentNodeRepository intentNodeRepository;

    @Autowired
    private IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;

    @Autowired
    private IntentTreeCacheManager intentTreeCacheManager;

    /** Tracks intent node IDs created by this test for cleanup. */
    private final List<String> createdNodeIds = new ArrayList<>();
    /** Original activeIntentVersion of the system agent, saved for restore. */
    private Integer originalActiveIntentVersion;

    @BeforeEach
    void setUpIntentTree() {
        AgentDTO agent = agentRepository.findById(SYSTEM_AGENT_ID);
        assertThat(agent)
                .as("System assistant agent must exist (seeded by V2 migration)")
                .isNotNull();

        // Save original version for restore
        originalActiveIntentVersion = agent.getActiveIntentVersion();

        // Create synthetic intent tree fixture
        createEvalIntentTree(agent.getId());

        // Update agent to point to eval version
        agent.setActiveIntentVersion(EVAL_INTENT_VERSION);
        agentRepository.update(agent);

        // Evict cache so cache manager loads our fresh nodes
        intentTreeCacheManager.evictActiveSnapshot(SYSTEM_AGENT_ID);
    }

    @AfterEach
    void tearDownIntentTree() {
        // Restore agent's original activeIntentVersion
        try {
            AgentDTO agent = agentRepository.findById(SYSTEM_AGENT_ID);
            if (agent != null) {
                agent.setActiveIntentVersion(originalActiveIntentVersion);
                agentRepository.update(agent);
            }
        } catch (Exception ignored) {
            // Best-effort restore
        }

        // Evict cache to clear eval snapshot
        try {
            intentTreeCacheManager.evictActiveSnapshot(SYSTEM_AGENT_ID);
        } catch (Exception ignored) {
            // Best-effort
        }

        // Delete eval intent nodes
        cleanupEvalNodes();
    }

    private void cleanupEvalNodes() {
        // Step 1: Delete all KB bindings for eval nodes (FK: intent_knowledge_base → intent_node)
        try {
            intentKnowledgeBaseRepository.deleteByIntentNodeIds(ALL_EVAL_NODE_IDS);
        } catch (Exception ignored) {}
        // Step 2: Delete nodes in reverse order (children first) for self-referencing FK (parent_id)
        List<String> reversedIds = new ArrayList<>(ALL_EVAL_NODE_IDS);
        java.util.Collections.reverse(reversedIds);
        try {
            intentNodeRepository.deleteByIds(reversedIds);
        } catch (Exception ignored) {}
        createdNodeIds.clear();
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    void intentTreeSetupAndRouting() {
        IntentRoutingResult result = intentRouter.route(SYSTEM_AGENT_ID,
                "Where do the Arizona Cardinals play?");
        assertThat(result)
                .as("IntentRouter should return a result for a sample query with eval intent tree")
                .isNotNull();
    }

    @Test
    void queryRewriteEnrichesQuery() {
        IntentRoutingResult routing = intentRouter.route(SYSTEM_AGENT_ID,
                "What is the company travel reimbursement policy?");
        assertThat(routing).isNotNull();
        assertThat(routing.hasResolution())
                .as("Routing should resolve to an intent for a straightforward query")
                .isTrue();

        IntentResolution resolution = routing.resolution();
        String originalQuery = "What is the company travel reimbursement policy?";
        String rewritten = queryRewriter.rewrite(originalQuery, resolution);
        assertThat(rewritten)
                .as("QueryRewriter should return a non-empty string")
                .isNotBlank();
    }

    @Test
    void toolCallbackFactoryReturnsTools() {
        AgentDTO agentConfig = AgentDTO.builder()
                .id(SYSTEM_AGENT_ID)
                .name("eval-agent")
                .allowedTools(List.of("knowledge.search"))
                .build();

        // Build a synthetic resolution for KB kind
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(evalNode(NODE_TOPIC_KB)),
                List.of(),
                ScopePolicy.FALLBACK_ALLOWED,
                List.of("knowledge.search"),
                null
        );

        List<ToolCallback> tools = toolCallbackFactory.create(agentConfig, resolution);
        assertThat(tools)
                .as("AgentToolCallbackFactory should return non-empty tool list for KB intent")
                .isNotEmpty();
    }

    @Test
    void exportsRealAgentModulePipeline() throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        Path phase3Root = repositoryRoot.resolve("artifacts/eval/phase3");
        Path datasetPath = phase3Root.resolve("datasets/memory/memory-v2-dialogues.jsonl");
        Path manifestPath = phase3Root.resolve("manifests/datasets/memory-v2-dialogues.json");
        assertThat(Files.exists(datasetPath) && Files.exists(manifestPath))
                .as("Run Phase 3 MTRAG memory preparation before Phase 10c smoke")
                .isTrue();

        List<JsonNode> rows = loadDatasetRows(datasetPath);
        JsonNode manifest = OBJECT_MAPPER.readTree(manifestPath.toFile());
        List<String> sourceIdList = toStrings(manifest.path("sourceIds"));
        List<Map<String, Object>> samples = new ArrayList<>();
        String exportTimestamp = Instant.now().toString();
        String runId = "phase10c-agent-module-real-smoke";

        AgentDTO agentConfig = AgentDTO.builder()
                .id(SYSTEM_AGENT_ID)
                .name("eval-agent")
                .allowedTools(List.of("knowledge.search"))
                .build();

        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            String query = extractLastUserText(row);

            // Step 1: Intent routing
            IntentRoutingResult routing = intentRouter.route(SYSTEM_AGENT_ID, query);

            // Step 2: Query rewrite (only when resolved)
            String rewrittenQuery = null;
            if (routing.hasResolution()) {
                rewrittenQuery = queryRewriter.rewrite(query, routing.resolution());
            }

            // Step 3: Tool callbacks (only when resolved)
            List<String> toolNames = List.of();
            if (routing.hasResolution()) {
                List<ToolCallback> callbacks = toolCallbackFactory.create(agentConfig, routing.resolution());
                toolNames = callbacks.stream()
                        .map(cb -> cb.getToolDefinition().name())
                        .collect(Collectors.toList());
            }

            samples.add(toSample(row, routing, rewrittenQuery, toolNames));
        }

        // Write run artifacts
        EvalArtifactWriter artifactWriter = new EvalArtifactWriter(
                repositoryRoot.resolve("artifacts/eval/phase10c"));
        Map<String, Object> config = runConfig(sourceIdList);
        String configFingerprint = EvalConfigFingerprint.sha256(config);
        Map<String, Object> metrics = Map.of("sampleCount", samples.size());
        EvalRunManifest runManifest = new EvalRunManifest(
                runId, "agent-modules", "smoke", exportTimestamp,
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
        assertThat(runDirectory.resolve("datasets/" + DATASET_SUBDIR + "/" + DATASET_ID + ".jsonl")).exists();
        assertThat(runDirectory.resolve("manifests/datasets/" + DATASET_ID + ".json")).exists();
        // Verify exported split manifest has all required splits
        JsonNode exportedSplitManifest = OBJECT_MAPPER.readTree(
                runDirectory.resolve("manifests/splits/" + DATASET_ID + ".json").toFile());
        for (String required : REQUIRED_SPLITS) {
            assertThat(exportedSplitManifest.path("splits").has(required))
                    .as("Exported split manifest must include '%s' split for tune-suite", required)
                    .isTrue();
        }
    }

    // ── Intent tree fixture ────────────────────────────────────────────

    // Fixed UUIDs for eval intent tree nodes (must be valid UUIDs for the intent_node.id column).
    // Using a fixed pattern so tests are reproducible.
    private static final String NODE_DOMAIN    = "a0000000-0000-0000-0000-000000000001";
    private static final String NODE_CAT_INFO  = "a0000000-0000-0000-0000-000000000002";
    private static final String NODE_TOPIC_KB  = "a0000000-0000-0000-0000-000000000003";
    private static final String NODE_CAT_ASSIST= "a0000000-0000-0000-0000-000000000004";
    private static final String NODE_TOPIC_TOOL= "a0000000-0000-0000-0000-000000000005";
    private static final String NODE_CAT_OTHER = "a0000000-0000-0000-0000-000000000006";
    private static final String NODE_TOPIC_DIR = "a0000000-0000-0000-0000-000000000007";

    private static final List<String> ALL_EVAL_NODE_IDS = List.of(
            NODE_DOMAIN, NODE_CAT_INFO, NODE_TOPIC_KB,
            NODE_CAT_ASSIST, NODE_TOPIC_TOOL,
            NODE_CAT_OTHER, NODE_TOPIC_DIR
    );

    private void createEvalIntentTree(String agentId) {
        // Idempotent: delete any stale eval nodes from previous crashed runs
        cleanupEvalNodes();

        LocalDateTime now = LocalDateTime.now();
        List<IntentNodeDTO> nodes = List.of(
                // Root domain
                evalNode(NODE_DOMAIN, agentId, null, EVAL_INTENT_VERSION,
                        IntentNodeLevel.DOMAIN, "General", IntentKind.KB, null, null,
                        List.of("information about", "help with", "tell me about", "what is"),
                        0, now),
                // Category: Information
                evalNode(NODE_CAT_INFO, agentId, NODE_DOMAIN, EVAL_INTENT_VERSION,
                        IntentNodeLevel.CATEGORY, "Information", IntentKind.KB, null, null,
                        List.of("knowledge", "search", "find", "look up"),
                        0, now),
                // Topic: KB knowledge search
                evalNode(NODE_TOPIC_KB, agentId, NODE_CAT_INFO, EVAL_INTENT_VERSION,
                        IntentNodeLevel.TOPIC, "Knowledge Search", IntentKind.KB,
                        ScopePolicy.FALLBACK_ALLOWED, List.of("knowledge.search"),
                        List.of("find information about", "search for", "where do", "what is the policy"),
                        0, now),
                // Category: Assistance
                evalNode(NODE_CAT_ASSIST, agentId, NODE_DOMAIN, EVAL_INTENT_VERSION,
                        IntentNodeLevel.CATEGORY, "Assistance", IntentKind.TOOL, null, null,
                        List.of("tool", "calculate", "perform", "execute"),
                        1, now),
                // Topic: Tool use
                evalNode(NODE_TOPIC_TOOL, agentId, NODE_CAT_ASSIST, EVAL_INTENT_VERSION,
                        IntentNodeLevel.TOPIC, "Tool Use", IntentKind.TOOL,
                        null, List.of("knowledge.search"),
                        List.of("use a tool", "call function", "perform action"),
                        0, now),
                // Category: Other
                evalNode(NODE_CAT_OTHER, agentId, NODE_DOMAIN, EVAL_INTENT_VERSION,
                        IntentNodeLevel.CATEGORY, "Other", IntentKind.SYSTEM, null, null,
                        List.of("greeting", "hello", "thanks", "goodbye"),
                        2, now),
                // Topic: Direct response
                evalNode(NODE_TOPIC_DIR, agentId, NODE_CAT_OTHER, EVAL_INTENT_VERSION,
                        IntentNodeLevel.TOPIC, "Direct Response", IntentKind.SYSTEM,
                        null, null,
                        List.of("hi", "hello", "thank you", "bye"),
                        0, now)
        );

        intentNodeRepository.saveAll(nodes);
        createdNodeIds.addAll(nodes.stream().map(IntentNodeDTO::getId).toList());
    }

    private IntentNodeDTO evalNode(String id, String agentId, String parentId, int version,
                                   IntentNodeLevel level, String name, IntentKind kind,
                                   ScopePolicy scopePolicy, List<String> allowedTools,
                                   List<String> examples, int sortOrder, LocalDateTime createdAt) {
        return IntentNodeDTO.builder()
                .id(id)
                .agentId(agentId)
                .parentId(parentId)
                .version(version)
                .status(IntentNodeStatus.PUBLISHED)
                .nodeLevel(level)
                .name(name)
                .description("Eval intent node: " + name)
                .examples(examples != null ? examples : List.of())
                .intentKind(level == IntentNodeLevel.TOPIC ? kind : null)
                .scopePolicy(level == IntentNodeLevel.TOPIC ? scopePolicy : null)
                .allowedTools(level == IntentNodeLevel.TOPIC && allowedTools != null ? allowedTools : List.of())
                .enabled(true)
                .sortOrder(sortOrder)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    /** Minimal node for building synthetic IntentResolution (no DB insert). */
    private IntentNodeDTO evalNode(String id) {
        return IntentNodeDTO.builder()
                .id(id)
                .name("Eval-" + id)
                .build();
    }

    // ── Sample serialization ──────────────────────────────────────────

    private Map<String, Object> toSample(JsonNode row, IntentRoutingResult routing,
                                         String rewrittenQuery, List<String> toolNames) {
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
        moduleOutputs.put("intent", serializeRoutingResult(routing));
        moduleOutputs.put("queryRewrite", rewrittenQuery);
        moduleOutputs.put("toolList", toolNames);
        moduleOutputs.put("provider", Map.of(
                "classifierModel", LLM_MODEL,
                "rewriteModel", LLM_MODEL
        ));
        sample.put("moduleOutputs", moduleOutputs);

        return sample;
    }

    private Map<String, Object> serializeRoutingResult(IntentRoutingResult result) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        if (result == null) {
            serialized.put("routed", false);
            return serialized;
        }
        serialized.put("routed", true);
        serialized.put("requiresClarification", result.requiresClarification());
        if (result.hasResolution()) {
            IntentResolution res = result.resolution();
            serialized.put("kind", res.kind().name());
            serialized.put("pathLabel", res.pathLabel());
            serialized.put("scopedKbIds", res.scopedKbIds());
            serialized.put("scopePolicy", res.scopePolicy() != null ? res.scopePolicy().name() : null);
            serialized.put("allowedTools", res.allowedTools());
        } else {
            serialized.put("kind", null);
            serialized.put("pathLabel", null);
        }
        return serialized;
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

    private String extractLastUserText(JsonNode row) {
        JsonNode turns = row.path("turns");
        for (int i = turns.size() - 1; i >= 0; i--) {
            if ("user".equals(turns.get(i).path("speaker").asText())) {
                return turns.get(i).path("text").asText();
            }
        }
        return "";
    }

    // ── Dataset root writer ───────────────────────────────────────────

    private void writeDatasetRoot(
            Path runDirectory, String datasetId, List<String> sourceIds,
            List<JsonNode> rows, List<Map<String, Object>> samples,
            String exportTimestamp
    ) throws Exception {
        List<Map<String, Object>> datasetRows = datasetRows(rows, samples);
        Path datasetPath = runDirectory.resolve(Path.of("datasets", DATASET_SUBDIR, datasetId + ".jsonl"));
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
            groupsBySplit.computeIfAbsent(row.get("split").toString(), k -> new LinkedHashSet<>())
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
            String datasetId, List<String> sourceIds, List<Map<String, Object>> rows,
            Map<String, Object> splitManifest, String datasetHash, String splitManifestHash,
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
        manifest.put("recordSchema", "eval-agent-module-dataset-record.schema.json");
        manifest.put("localPath", "datasets/" + DATASET_SUBDIR + "/" + datasetId + ".jsonl");
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
        provenance.put("classifierModel", LLM_MODEL);
        provenance.put("rewriteModel", LLM_MODEL);
        provenance.put("exportTimestamp", exportTimestamp);
        return provenance;
    }

    // ── Infrastructure probes ─────────────────────────────────────────

    /** Runs all infrastructure probes; called by {@link AgentModuleInfrastructureCondition}. */
    static boolean probeAll() {
        boolean pg = probePostgres();
        System.out.println("[Phase-10c probe] PostgreSQL: " + (pg ? "OK" : "UNAVAILABLE"));
        boolean redis = probeRedis();
        System.out.println("[Phase-10c probe] Redis:     " + (redis ? "OK" : "UNAVAILABLE"));
        boolean llm = probeLlmProvider();
        System.out.println("[Phase-10c probe] LLM:       " + (llm ? "OK" : "UNAVAILABLE"));
        boolean all = pg && redis && llm;
        System.out.println("[Phase-10c probe] Overall:   " + (all ? "ALL AVAILABLE" : "SKIPPING TESTS"));
        return all;
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
        } catch (Exception ignored) {
            // Use defaults
        }
        return tcpProbe(host, port);
    }

    private static boolean probeRedis() {
        String host = configValue("CHATAGENT_REDIS_HOST", "localhost");
        int port = Integer.parseInt(configValue("CHATAGENT_REDIS_PORT", "6379"));
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
        String baseUrl = configValue("CHATAGENT_DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        try {
            String probeUrl = baseUrl + "/chat/completions";
            String probeBody = "{\"model\":\"" + LLM_MODEL
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
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean tcpProbe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception ignored) {
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

    // ── Utility methods ───────────────────────────────────────────────

    private static String setting(String propertyName, String environmentName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String environmentValue = System.getenv(environmentName);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
    }

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

    private List<JsonNode> balanceSplits(List<JsonNode> allRows, int budget) {
        Map<String, List<Integer>> bySplit = new LinkedHashMap<>();
        for (int i = 0; i < allRows.size(); i++) {
            bySplit.computeIfAbsent(allRows.get(i).path("split").asText(), k -> new ArrayList<>()).add(i);
        }
        Set<Integer> selected = new LinkedHashSet<>();
        for (String split : REQUIRED_SPLITS) {
            List<Integer> indices = bySplit.getOrDefault(split, List.of());
            int count = Math.min(MIN_PER_SPLIT, indices.size());
            for (int i = 0; i < count && selected.size() < budget; i++) {
                selected.add(indices.get(i));
            }
        }
        for (int i = 0; i < allRows.size() && selected.size() < budget; i++) {
            selected.add(i);
        }
        return selected.stream().sorted().map(allRows::get).collect(Collectors.toList());
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("AGENTS.md"))) {
            current = current.getParent();
        }
        assertThat(current).as("Repository root with AGENTS.md was not found").isNotNull();
        return current;
    }

    private List<String> toStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private Map<String, Object> runConfig(List<String> sourceIds) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("classifierModel", LLM_MODEL);
        config.put("rewriteModel", LLM_MODEL);
        config.put("sampleCount", SMOKE_DIALOGUE_COUNT);
        config.put("sourceIds", sourceIds);
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
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
