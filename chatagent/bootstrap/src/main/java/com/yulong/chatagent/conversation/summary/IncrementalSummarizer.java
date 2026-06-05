package com.yulong.chatagent.conversation.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增量会话摘要器 (V2 structured flow)。
 * <p>
 * 它负责把"已经滑出 L1 原始记忆窗口"的那部分历史压缩成 L2 摘要，
 * 并且不是每次从零开始重算，而是：
 * <ol>
 *     <li>读取现有摘要；</li>
 *     <li>找到这次新稳定下来的 turn 片段；</li>
 *     <li>调用 LLM 生成结构化 JSON 摘要；</li>
 *     <li>持久化一个 segment 行；</li>
 *     <li>将 segment 摘要合并进 session synopsis。</li>
 * </ol>
 * 如果 LLM 调用失败或返回非法 JSON，会回退到确定性拼接摘要。
 */
@Component
@Slf4j
public class IncrementalSummarizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern SLASH_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?<!\\w)(?:[\\u00A5\\uFFE5$])?\\d+(?:\\.\\d{1,2})?(?!\\w)");
    private static final Pattern ORDER_PATTERN = Pattern.compile("\\b[A-Z]{2,}-?\\d{4,}\\b");

    private final PromptLoader promptLoader;
    private final TurnBasedContextExtractor turnBasedContextExtractor;
    private final SummaryWatermarkService summaryWatermarkService;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final ChatSessionSummarySegmentRepository segmentRepository;
    private final ChatModelRouter chatModelRouter;
    private final String summaryModel;
    private final int segmentMaxChars;
    private final int synopsisMaxChars;

    public IncrementalSummarizer(PromptLoader promptLoader,
                                 TurnBasedContextExtractor turnBasedContextExtractor,
                                 SummaryWatermarkService summaryWatermarkService,
                                 ChatSessionSummaryRepository chatSessionSummaryRepository,
                                 ChatSessionSummarySegmentRepository segmentRepository,
                                 ChatModelRouter chatModelRouter,
                                 @Value("${chatagent.memory.summary-model:deepseek-chat}") String summaryModel,
                                 @Value("${chatagent.memory.compaction.v2.segment-max-chars:1200}") int segmentMaxChars,
                                 @Value("${chatagent.memory.compaction.v2.synopsis-max-chars:2000}") int synopsisMaxChars) {
        this.promptLoader = promptLoader;
        this.turnBasedContextExtractor = turnBasedContextExtractor;
        this.summaryWatermarkService = summaryWatermarkService;
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
        this.segmentRepository = segmentRepository;
        this.chatModelRouter = chatModelRouter;
        this.summaryModel = summaryModel;
        this.segmentMaxChars = Math.max(segmentMaxChars, 120);
        this.synopsisMaxChars = Math.max(synopsisMaxChars, 200);
    }

    /**
     * Summarizes the newly stable portion of a session and advances the summary watermark.
     *
     * @param sessionId chat session identifier
     * @param anchorSeqNo latest persisted message sequence that is safe to summarize
     * @return true when the summary record was updated
     */
    public boolean summarize(String sessionId, long anchorSeqNo) {
        return summarizeWithDetails(sessionId, anchorSeqNo).updated();
    }

    /**
     * Summarizes the newly stable portion of a session, returning both the update
     * status and the raw turns that were processed so L3 promotion can reuse them.
     *
     * <p>V2 flow:
     * <ol>
     *     <li>Resolve pending range from watermark</li>
     *     <li>Extract pending turns</li>
     *     <li>Call LLM with structured JSON prompt → parse into StructuredSummary</li>
     *     <li>Create a segment row for this range</li>
     *     <li>Merge segment summary into session synopsis deterministically</li>
     *     <li>Persist updated summary row</li>
     * </ol>
     *
     * @param sessionId chat session identifier
     * @param anchorSeqNo latest persisted message sequence that is safe to summarize
     * @return summary result including segments and updated synopsis
     */
    public SummaryResult summarizeWithDetails(String sessionId, long anchorSeqNo) {
        SummaryWatermarkRange range = summaryWatermarkService.resolvePendingRange(sessionId, anchorSeqNo);
        if (!range.hasPendingMessages()) {
            return new SummaryResult(false, range, List.of());
        }

        ChatSessionSummaryDTO existing = chatSessionSummaryRepository.findBySessionId(sessionId);
        List<AtomicConversationTurn> turns = turnBasedContextExtractor.extractPendingTurns(sessionId, range.endInclusiveSeqNo());

        if (turns.isEmpty()) {
            return new SummaryResult(false, range, List.of());
        }

        // 1. Generate structured summary
        StructuredSummary structured = generateStructuredSummary(turns);

        // 2. Merge anchored entities (regex-based + structured entities from LLM)
        Map<String, List<String>> mergedAnchors = mergeAnchoredEntities(
                existing == null ? Map.of() : existing.getAnchoredEntities(),
                extractAnchoredEntities(turns),
                structured.entities()
        );
        warnIfAnchorsMissing(sessionId, structured.summary(), mergedAnchors);

        // 3. Create segment row
        int tokenEstimate = TokenEstimator.estimateTurns(turns);
        ChatSessionSummarySegmentDTO segment = ChatSessionSummarySegmentDTO.builder()
                .sessionId(sessionId)
                .seqStartNo(turns.get(0).startSeqNo())
                .seqEndNo(range.endInclusiveSeqNo())
                .turnCount(turns.size())
                .sourceTokenEstimate(tokenEstimate)
                .segmentSummary(enforceLengthCap(structured.summary(), segmentMaxChars))
                .structuredSummaryJson(toStructuredJson(structured))
                .anchoredEntities(mergedAnchors)
                .build();
        boolean segmentInserted = segmentRepository.insert(segment);

        // 4. Merge synopsis deterministically
        String existingSynopsis = existing == null || existing.getSynopsis() == null ? "" : existing.getSynopsis().trim();
        String nextSynopsis = mergeSynopsis(existingSynopsis, structured.summary());

        // 5. Persist updated summary
        ChatSessionSummaryDTO updated = ChatSessionSummaryDTO.builder()
                .sessionId(sessionId)
                .summarizedUntilSeqNo(range.endInclusiveSeqNo())
                .synopsis(nextSynopsis)
                .anchoredEntities(mergedAnchors)
                .segmentCount(existing == null || existing.getSegmentCount() == null ? (segmentInserted ? 1 : 0) : existing.getSegmentCount() + (segmentInserted ? 1 : 0))
                .consecutiveFailures(0)
                .build();
        boolean saved = chatSessionSummaryRepository.saveOrUpdate(updated);

        return new SummaryResult(saved, range, turns,
                segmentInserted ? List.of(segment) : List.of(),
                nextSynopsis);
    }

    private StructuredSummary generateStructuredSummary(List<AtomicConversationTurn> turns) {
        String prompt = buildStructuredPrompt(turns);
        try {
            ChatClient chatClient = chatModelRouter.route(summaryModel);
            String content = chatClient.prompt(prompt)
                    .call()
                    .content();
            if (StringUtils.hasText(content)) {
                StructuredSummary parsed = StructuredSummaryParser.parse(content);
                if (StringUtils.hasText(parsed.summary())) {
                    return parsed;
                }
            }
            log.warn("Summary model returned blank or unparseable content, using fallback");
        } catch (Exception e) {
            log.warn("Summary generation fallback triggered: error={}", e.getMessage());
        }
        return StructuredSummaryParser.fallback(turns);
    }

    private String buildStructuredPrompt(List<AtomicConversationTurn> turns) {
        return promptLoader.render(PromptConstants.SUMMARIZER_SEGMENT_MEMORY, Map.of(
                "segmentMaxChars", String.valueOf(segmentMaxChars),
                "formattedTurns", formatTurns(turns)
        ));
    }

    private String formatTurns(List<AtomicConversationTurn> turns) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            AtomicConversationTurn turn = turns.get(i);
            builder.append("Turn ").append(i + 1).append(":\n");
            if (turn.userMessages() != null) {
                for (String userMessage : turn.userMessages()) {
                    builder.append("- User: ").append(userMessage).append('\n');
                }
            }
            if (StringUtils.hasText(turn.assistantConclusion())) {
                builder.append("- Assistant: ").append(turn.assistantConclusion()).append('\n');
            }
        }
        return builder.toString().trim();
    }

    /**
     * Deterministic synopsis merge: append new segment summary to existing synopsis.
     * If the result exceeds the cap, truncate from the beginning to keep the newest content.
     */
    private String mergeSynopsis(String existingSynopsis, String newSegmentSummary) {
        if (!StringUtils.hasText(newSegmentSummary)) {
            return existingSynopsis == null ? "" : existingSynopsis;
        }
        if (!StringUtils.hasText(existingSynopsis)) {
            return enforceLengthCap(newSegmentSummary.trim(), synopsisMaxChars);
        }

        String merged = existingSynopsis.trim() + "\n" + newSegmentSummary.trim();
        return enforceLengthCap(merged, synopsisMaxChars);
    }

    private String enforceLengthCap(String text, int maxChars) {
        if (!StringUtils.hasText(text) || text.length() <= maxChars) {
            return text == null ? "" : text.trim();
        }
        // Truncate from the beginning to keep the most recent content.
        int start = text.length() - maxChars;
        // Try to start at a line boundary for cleaner output.
        int newlinePos = text.indexOf('\n', start);
        if (newlinePos >= 0 && newlinePos < start + maxChars / 4) {
            start = newlinePos + 1;
        }
        return text.substring(start).trim();
    }

    /**
     * Serializes a StructuredSummary into a JSON string for the structured_summary_json column.
     */
    private String toStructuredJson(StructuredSummary summary) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("summary", summary.summary());
            map.put("facts", summary.facts());
            map.put("decisions", summary.decisions());
            map.put("open_tasks", summary.openTasks());
            map.put("entities", summary.entities());
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize structured summary to JSON: error={}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, List<String>> extractAnchoredEntities(List<AtomicConversationTurn> turns) {
        Map<String, Set<String>> collected = new LinkedHashMap<>();
        for (AtomicConversationTurn turn : turns) {
            if (turn.userMessages() == null) {
                continue;
            }
            for (String userMessage : turn.userMessages()) {
                collectMatches(collected, "dates", ISO_DATE_PATTERN, userMessage);
                collectMatches(collected, "dates", SLASH_DATE_PATTERN, userMessage);
                collectMatches(collected, "amounts", AMOUNT_PATTERN, userMessage);
                collectMatches(collected, "orderIds", ORDER_PATTERN, userMessage == null ? null : userMessage.toUpperCase());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : collected.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    private Map<String, List<String>> mergeAnchoredEntities(Map<String, List<String>> existing,
                                                            Map<String, List<String>> regexExtracted,
                                                            Map<String, List<String>> structuredEntities) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        appendAnchors(merged, existing);
        appendAnchors(merged, regexExtracted);
        appendAnchors(merged, structuredEntities);

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : merged.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    private void appendAnchors(Map<String, Set<String>> target, Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            target.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
    }

    private void collectMatches(Map<String, Set<String>> target, String bucket, Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group();
            if (!StringUtils.hasText(match)) {
                continue;
            }
            target.computeIfAbsent(bucket, ignored -> new LinkedHashSet<>()).add(match.trim());
        }
    }

    private void warnIfAnchorsMissing(String sessionId, String summary, Map<String, List<String>> anchors) {
        if (!StringUtils.hasText(summary) || anchors == null || anchors.isEmpty()) {
            return;
        }
        String normalizedSummary = summary.toLowerCase();
        int missingCount = 0;
        List<String> missingBuckets = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : anchors.entrySet()) {
            int bucketMissing = 0;
            for (String value : entry.getValue()) {
                if (StringUtils.hasText(value) && !normalizedSummary.contains(value.toLowerCase())) {
                    bucketMissing++;
                }
            }
            if (bucketMissing > 0) {
                missingCount += bucketMissing;
                missingBuckets.add(entry.getKey() + ":" + bucketMissing);
            }
        }
        if (missingCount > 0) {
            log.warn("Anchored entities missing after summary refresh: sessionId={}, missingCount={}, buckets={}",
                    sessionId, missingCount, missingBuckets);
        }
    }
}
