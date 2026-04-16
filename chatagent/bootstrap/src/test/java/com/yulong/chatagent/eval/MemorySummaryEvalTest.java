package com.yulong.chatagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.IncrementalSummarizer;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkService;
import com.yulong.chatagent.conversation.summary.TurnBasedContextExtractor;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2b: Memory / summary evaluation using the real rolling-memory prompt + model router.
 *
 * The test replays golden dialogues through {@link IncrementalSummarizer} one atomic turn at
 * a time, then measures:
 *   - checkpoint summary mention recall after assistant turns that declare expected mentions
 *   - final summary entity recall
 *   - final summary topic recall
 *
 * Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-memory \
 *      -Dtest=MemorySummaryEvalTest [-Deval.smoke=true]
 */
@Tag("eval-memory")
@SpringBootTest
@ActiveProfiles("local-gpu")
@TestPropertySource(properties = {
        "chat.routing.default-model=deepseek-chat",
        "chatagent.memory.summary-model=deepseek-chat"
})
class MemorySummaryEvalTest {

    private static final int SMOKE_DIALOGUES_PER_DOMAIN = 1;

    @Autowired
    private PromptLoader promptLoader;

    @Autowired
    private ChatModelRouter chatModelRouter;

    private List<MemoryGoldenDialogue> dialogues;

    @BeforeEach
    void setUp() {
        List<MemoryGoldenDialogue> all = GoldenDatasetLoader.loadMemoryGolden();
        if (Boolean.getBoolean("eval.smoke")) {
            dialogues = all.stream()
                    .collect(Collectors.groupingBy(MemoryGoldenDialogue::domain))
                    .values().stream()
                    .flatMap(list -> list.stream().limit(SMOKE_DIALOGUES_PER_DOMAIN))
                    .toList();
        } else {
            dialogues = all;
        }
    }

    @Test
    void evaluateMemorySummaryQuality() throws Exception {
        List<DialogueResult> results = new ArrayList<>();
        int summaryRefreshes = 0;

        for (MemoryGoldenDialogue dialogue : dialogues) {
            TurnBasedContextExtractor contextExtractor = mock(TurnBasedContextExtractor.class);
            SummaryWatermarkService watermarkService = mock(SummaryWatermarkService.class);
            InMemorySummaryRepository summaryRepository = new InMemorySummaryRepository();
            IncrementalSummarizer summarizer = new IncrementalSummarizer(
                    promptLoader,
                    contextExtractor,
                    watermarkService,
                    summaryRepository,
                    chatModelRouter,
                    "",
                    500
            );

            String sessionId = "memory-eval-" + dialogue.id();
            List<EvalAtomicTurn> evalTurns = toAtomicTurns(dialogue);
            DialogueResult dialogueResult = new DialogueResult();
            dialogueResult.id = dialogue.id();
            dialogueResult.domain = dialogue.domain();

            long lastSeqNo = 0L;
            for (EvalAtomicTurn evalTurn : evalTurns) {
                long anchorSeqNo = evalTurn.turn().endSeqNo();
                when(watermarkService.resolvePendingRange(sessionId, anchorSeqNo))
                        .thenReturn(new SummaryWatermarkRange(sessionId, lastSeqNo, anchorSeqNo));
                when(contextExtractor.extractPendingTurns(sessionId, anchorSeqNo))
                        .thenReturn(List.of(evalTurn.turn()));

                boolean summarized = summarizer.summarize(sessionId, anchorSeqNo);
                summaryRefreshes++;
                lastSeqNo = anchorSeqNo;

                ChatSessionSummaryDTO current = summaryRepository.findBySessionId(sessionId);
                CheckpointResult checkpoint = new CheckpointResult();
                checkpoint.turnId = evalTurn.turn().turnId();
                checkpoint.summarized = summarized;
                checkpoint.summary = current == null ? "" : safeTrim(current.getSummary());
                checkpoint.expectedSummaryMentions = evalTurn.expectedSummaryMentions();
                checkpoint.expectedMentionCount = evalTurn.expectedSummaryMentions().size();
                checkpoint.matchedMentions = matchedItems(checkpoint.summary, evalTurn.expectedSummaryMentions());
                checkpoint.matchedMentionCount = checkpoint.matchedMentions.size();
                checkpoint.mentionRecall = ratio(checkpoint.matchedMentionCount, checkpoint.expectedMentionCount);
                checkpoint.allMentionsCovered = checkpoint.expectedMentionCount == 0
                        || checkpoint.matchedMentionCount == checkpoint.expectedMentionCount;
                dialogueResult.checkpoints.add(checkpoint);

                if (summaryRefreshes % 5 == 0) {
                    Thread.sleep(1000);
                }
            }

            ChatSessionSummaryDTO finalState = summaryRepository.findBySessionId(sessionId);
            dialogueResult.finalSummary = finalState == null ? "" : safeTrim(finalState.getSummary());
            dialogueResult.finalSummaryChars = dialogueResult.finalSummary.length();
            dialogueResult.anchoredEntities = finalState == null || finalState.getAnchoredEntities() == null
                    ? Map.of()
                    : finalState.getAnchoredEntities();

            dialogueResult.expectedEntities = defaultList(dialogue.expectedEntities());
            dialogueResult.expectedTopics = defaultList(dialogue.expectedTopics());
            dialogueResult.matchedEntities = matchedItems(dialogueResult.finalSummary, dialogueResult.expectedEntities);
            dialogueResult.matchedTopics = matchedItems(dialogueResult.finalSummary, dialogueResult.expectedTopics);
            dialogueResult.entityRecall = ratio(dialogueResult.matchedEntities.size(), dialogueResult.expectedEntities.size());
            dialogueResult.topicRecall = ratio(dialogueResult.matchedTopics.size(), dialogueResult.expectedTopics.size());
            dialogueResult.checkpointMentionRecall = dialogueResult.checkpoints.stream()
                    .mapToDouble(cp -> cp.mentionRecall)
                    .average()
                    .orElse(0.0);
            dialogueResult.checkpointAllCoveredRate = dialogueResult.checkpoints.isEmpty()
                    ? 0.0
                    : dialogueResult.checkpoints.stream().filter(CheckpointResult::allMentionsCovered).count()
                    / (double) dialogueResult.checkpoints.size();
            results.add(dialogueResult);
        }

        Map<String, Object> overall = aggregateOverall(results);
        Map<String, Map<String, Object>> byDomain = aggregateByDomain(results);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "memory-summary-eval");
        report.put("mode", "real-llm");
        report.put("smokeMode", Boolean.getBoolean("eval.smoke"));
        report.put("dialoguesEvaluated", results.size());
        report.put("summaryRefreshes", summaryRefreshes);
        report.put("overall", overall);
        report.put("byDomain", byDomain);
        report.put("perDialogue", results.stream().map(DialogueResult::toMap).toList());

        Path reportPath = EvalReportWriter.writeReport("memory-summary-eval", report);
        System.out.println("=== Memory / Summary Evaluation ===");
        System.out.println("Report: " + reportPath);
        System.out.println("Overall:\n" + new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(overall));

        assertThat(results).isNotEmpty();
        assertThat(overall.get("checkpointMentionRecall")).isNotNull();
        assertThat(overall.get("entityRecall")).isNotNull();
        assertThat(overall.get("topicRecall")).isNotNull();
    }

    private static Map<String, Object> aggregateOverall(List<DialogueResult> results) {
        long totalCheckpoints = results.stream().mapToLong(r -> r.checkpoints.size()).sum();
        long totalExpectedMentions = results.stream()
                .flatMap(r -> r.checkpoints.stream())
                .mapToLong(cp -> cp.expectedMentionCount)
                .sum();
        long totalMatchedMentions = results.stream()
                .flatMap(r -> r.checkpoints.stream())
                .mapToLong(cp -> cp.matchedMentionCount)
                .sum();
        long totalEntities = results.stream().mapToLong(r -> r.expectedEntities.size()).sum();
        long matchedEntities = results.stream().mapToLong(r -> r.matchedEntities.size()).sum();
        long totalTopics = results.stream().mapToLong(r -> r.expectedTopics.size()).sum();
        long matchedTopics = results.stream().mapToLong(r -> r.matchedTopics.size()).sum();
        double avgSummaryChars = results.stream().mapToInt(r -> r.finalSummaryChars).average().orElse(0.0);
        long allCoveredCheckpoints = results.stream()
                .flatMap(r -> r.checkpoints.stream())
                .filter(CheckpointResult::allMentionsCovered)
                .count();

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("checkpointCount", totalCheckpoints);
        overall.put("checkpointMentionRecall", round4(ratio(totalMatchedMentions, totalExpectedMentions)));
        overall.put("checkpointAllCoveredRate", round4(ratio(allCoveredCheckpoints, totalCheckpoints)));
        overall.put("entityRecall", round4(ratio(matchedEntities, totalEntities)));
        overall.put("topicRecall", round4(ratio(matchedTopics, totalTopics)));
        overall.put("avgSummaryChars", round4(avgSummaryChars));
        return overall;
    }

    private static Map<String, Map<String, Object>> aggregateByDomain(List<DialogueResult> results) {
        Map<String, Map<String, Object>> byDomain = new LinkedHashMap<>();
        results.stream()
                .collect(Collectors.groupingBy(r -> r.domain, LinkedHashMap::new, Collectors.toList()))
                .forEach((domain, domainResults) -> byDomain.put(domain, aggregateOverall(domainResults)));
        return byDomain;
    }

    private static List<EvalAtomicTurn> toAtomicTurns(MemoryGoldenDialogue dialogue) {
        List<EvalAtomicTurn> result = new ArrayList<>();
        List<String> pendingUsers = new ArrayList<>();
        long seqNo = 0L;
        int turnIndex = 0;

        for (MemoryGoldenDialogue.Turn turn : dialogue.turns()) {
            seqNo++;
            if ("user".equalsIgnoreCase(turn.role())) {
                pendingUsers.add(safeTrim(turn.content()));
                continue;
            }
            if (!"assistant".equalsIgnoreCase(turn.role())) {
                continue;
            }

            turnIndex++;
            AtomicConversationTurn atomicTurn = new AtomicConversationTurn(
                    "turn-" + turnIndex,
                    Math.max(1L, seqNo - pendingUsers.size()),
                    seqNo,
                    List.copyOf(pendingUsers),
                    safeTrim(turn.content())
            );
            result.add(new EvalAtomicTurn(atomicTurn, defaultList(turn.expectedSummaryMentions())));
            pendingUsers = new ArrayList<>();
        }

        return result;
    }

    private static List<String> matchedItems(String summary, List<String> expectedItems) {
        if (expectedItems == null || expectedItems.isEmpty()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (String item : expectedItems) {
            if (containsLoose(summary, item)) {
                matched.add(item);
            }
        }
        return matched;
    }

    private static boolean containsLoose(String text, String expected) {
        if (text == null || expected == null) {
            return false;
        }
        String normalizedText = normalizeLoose(text);
        String normalizedExpected = normalizeLoose(expected);
        if (normalizedExpected.isBlank()) {
            return false;
        }
        if (normalizedText.contains(normalizedExpected)) {
            return true;
        }
        String strippedExpected = stripTrailingZeroDecimals(normalizedExpected);
        String strippedText = stripTrailingZeroDecimals(normalizedText);
        return strippedText.contains(strippedExpected);
    }

    private static String normalizeLoose(String value) {
        return safeTrim(value)
                .toLowerCase(Locale.ROOT)
                .replace("，", "")
                .replace("。", "")
                .replace("：", "")
                .replace("；", "")
                .replace("、", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .replace("[", "")
                .replace("]", "")
                .replace("{", "")
                .replace("}", "")
                .replace(" ", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace(",", "");
    }

    private static String stripTrailingZeroDecimals(String value) {
        return value.replaceAll("(\\d+)\\.00\\b", "$1");
    }

    private static double ratio(long matched, long total) {
        return total <= 0 ? 0.0 : (double) matched / total;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(MemorySummaryEvalTest::safeTrim)
                .toList();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private record EvalAtomicTurn(
            AtomicConversationTurn turn,
            List<String> expectedSummaryMentions
    ) {}

    private static final class CheckpointResult {
        String turnId;
        boolean summarized;
        String summary;
        List<String> expectedSummaryMentions = List.of();
        List<String> matchedMentions = List.of();
        int expectedMentionCount;
        int matchedMentionCount;
        double mentionRecall;
        boolean allMentionsCovered;

        boolean allMentionsCovered() {
            return allMentionsCovered;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("turnId", turnId);
            map.put("summarized", summarized);
            map.put("expectedSummaryMentions", expectedSummaryMentions);
            map.put("matchedMentions", matchedMentions);
            map.put("mentionRecall", round4(mentionRecall));
            map.put("allMentionsCovered", allMentionsCovered);
            map.put("summary", summary);
            return map;
        }
    }

    private static final class DialogueResult {
        String id;
        String domain;
        List<CheckpointResult> checkpoints = new ArrayList<>();
        String finalSummary;
        int finalSummaryChars;
        List<String> expectedEntities = List.of();
        List<String> expectedTopics = List.of();
        List<String> matchedEntities = List.of();
        List<String> matchedTopics = List.of();
        Map<String, List<String>> anchoredEntities = Map.of();
        double checkpointMentionRecall;
        double checkpointAllCoveredRate;
        double entityRecall;
        double topicRecall;

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("domain", domain);
            map.put("checkpointMentionRecall", round4(checkpointMentionRecall));
            map.put("checkpointAllCoveredRate", round4(checkpointAllCoveredRate));
            map.put("entityRecall", round4(entityRecall));
            map.put("topicRecall", round4(topicRecall));
            map.put("finalSummaryChars", finalSummaryChars);
            map.put("expectedEntities", expectedEntities);
            map.put("matchedEntities", matchedEntities);
            map.put("expectedTopics", expectedTopics);
            map.put("matchedTopics", matchedTopics);
            map.put("anchoredEntities", anchoredEntities);
            map.put("finalSummary", finalSummary);
            map.put("checkpoints", checkpoints.stream().map(CheckpointResult::toMap).toList());
            return map;
        }
    }

    private static final class InMemorySummaryRepository implements ChatSessionSummaryRepository {

        private final Map<String, ChatSessionSummaryDTO> store = new LinkedHashMap<>();

        @Override
        public ChatSessionSummaryDTO findBySessionId(String sessionId) {
            ChatSessionSummaryDTO value = store.get(sessionId);
            return value == null ? null : copy(value);
        }

        @Override
        public boolean saveOrUpdate(ChatSessionSummaryDTO summary) {
            ChatSessionSummaryDTO existing = store.get(summary.getSessionId());
            int nextVersion = existing == null || existing.getVersion() == null ? 0 : existing.getVersion() + 1;
            LocalDateTime createdAt = existing == null ? LocalDateTime.now() : existing.getCreatedAt();
            ChatSessionSummaryDTO copy = copy(summary);
            copy.setVersion(nextVersion);
            copy.setCreatedAt(createdAt);
            copy.setUpdatedAt(LocalDateTime.now());
            if (copy.getAnchoredEntities() == null) {
                copy.setAnchoredEntities(Map.of());
            }
            store.put(copy.getSessionId(), copy);
            return true;
        }

        @Override
        public boolean deleteBySessionId(String sessionId) {
            return store.remove(sessionId) != null;
        }

        private ChatSessionSummaryDTO copy(ChatSessionSummaryDTO source) {
            if (source == null) {
                return null;
            }
            Map<String, List<String>> anchorsCopy = new LinkedHashMap<>();
            if (source.getAnchoredEntities() != null) {
                source.getAnchoredEntities().forEach((key, value) ->
                        anchorsCopy.put(key, value == null ? List.of() : List.copyOf(new LinkedHashSet<>(value))));
            }
            return ChatSessionSummaryDTO.builder()
                    .sessionId(source.getSessionId())
                    .lastSeqNo(source.getLastSeqNo())
                    .summary(source.getSummary())
                    .anchoredEntities(anchorsCopy)
                    .anchoredEntitiesJson(source.getAnchoredEntitiesJson())
                    .version(source.getVersion())
                    .createdAt(source.getCreatedAt())
                    .updatedAt(source.getUpdatedAt())
                    .build();
        }
    }
}
