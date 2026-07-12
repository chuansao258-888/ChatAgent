package com.yulong.chatagent.conversation.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
 * 它负责把 L2 水位线之后选中的滚动批次压缩成 L2 摘要，
 * 并且不是每次从零开始重算，而是：
 * <ol>
 *     <li>读取现有摘要；</li>
 *     <li>找到这次新稳定下来的 turn 片段；</li>
 *     <li>调用 LLM 生成结构化 JSON 摘要；</li>
 *     <li>持久化一个 segment 行；</li>
 *     <li>将 segment 摘要合并进 session synopsis。</li>
 * </ol>
 * 如果 LLM 调用失败或返回非法 JSON，会回退到确定性拼接摘要。
 *
 * <p>Phase 5 增加失败保护和重试：
 * <ul>
 *     <li>模型返回空白/非法输出时重试（最多 maxRetries 次）</li>
 *     <li>Prompt 过长时自动拆分 turn 范围并分别摘要</li>
 *     <li>乐观锁冲突时重试 saveOrUpdate</li>
 *     <li>失败时记录 consecutiveFailures、failure range、nextRetryAt</li>
 *     <li>成功时清除失败状态</li>
 * </ul>
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
    private final MemoryCompactionCommitService commitService;
    private final ChatModelRouter chatModelRouter;
    private final String summaryModel;
    private final int segmentMaxChars;
    private final int synopsisMaxChars;
    private final int maxRetries;
    private final int maxConsecutiveFailures;
    private final int failureBackoffSeconds;

    public IncrementalSummarizer(PromptLoader promptLoader,
                                 TurnBasedContextExtractor turnBasedContextExtractor,
                                 SummaryWatermarkService summaryWatermarkService,
                                 ChatSessionSummaryRepository chatSessionSummaryRepository,
                                 MemoryCompactionCommitService commitService,
                                 ChatModelRouter chatModelRouter,
                                 @Value("${chatagent.memory.summary-model:deepseek-v4-flash}") String summaryModel,
                                 @Value("${chatagent.memory.compaction.v2.segment-max-chars:1200}") int segmentMaxChars,
                                 @Value("${chatagent.memory.compaction.v2.synopsis-max-chars:2000}") int synopsisMaxChars,
                                 @Value("${chatagent.memory.compaction.v2.max-retries:2}") int maxRetries,
                                 @Value("${chatagent.memory.compaction.v2.max-consecutive-failures:3}") int maxConsecutiveFailures,
                                 @Value("${chatagent.memory.compaction.v2.failure-backoff-seconds:300}") int failureBackoffSeconds) {
        this.promptLoader = promptLoader;
        this.turnBasedContextExtractor = turnBasedContextExtractor;
        this.summaryWatermarkService = summaryWatermarkService;
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
        this.commitService = commitService;
        this.chatModelRouter = chatModelRouter;
        this.summaryModel = summaryModel;
        this.segmentMaxChars = Math.max(segmentMaxChars, 120);
        this.synopsisMaxChars = Math.max(synopsisMaxChars, 200);
        this.maxRetries = Math.max(maxRetries, 0);
        this.maxConsecutiveFailures = Math.max(maxConsecutiveFailures, 1);
        this.failureBackoffSeconds = Math.max(failureBackoffSeconds, 60);
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
     * <p>V2 flow with Phase 5 failure protection:
     * <ol>
     *     <li>Resolve pending range from watermark</li>
     *     <li>Extract pending turns</li>
     *     <li>Call LLM with structured JSON prompt → parse into StructuredSummary (with retry)</li>
     *     <li>If prompt too long → split range and process each sub-range independently</li>
     *     <li>Create a segment row for each successful sub-range</li>
     *     <li>Merge segment summary into session synopsis deterministically</li>
     *     <li>Persist updated summary row (with optimistic lock retry)</li>
     *     <li>On failure, record failure state (consecutiveFailures, nextRetryAt)</li>
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

        return summarizeRange(sessionId, turns, range, existing);
    }

    /**
     * Summarizes a single turn range with split-and-retry on prompt-too-long.
     */
    private SummaryResult summarizeRange(String sessionId, List<AtomicConversationTurn> turns,
                                          SummaryWatermarkRange range, ChatSessionSummaryDTO existing) {
        try {
            return doSummarizeRange(sessionId, turns, range, existing);
        } catch (PromptTooLongException e) {
            if (turns.size() <= 1) {
                log.warn("Prompt too long for single turn, recording failure: sessionId={}, turnId={}",
                        sessionId, turns.get(0).turnId());
                return recordFailure(sessionId, range, existing, e);
            }
            return splitAndSummarize(sessionId, turns, range, existing);
        } catch (SummarizationFailedException e) {
            return recordFailure(sessionId, range, existing, e);
        }
    }

    /**
     * Core per-range summarization: generate structured summary, create segment, save summary.
     */
    private SummaryResult doSummarizeRange(String sessionId, List<AtomicConversationTurn> turns,
                                            SummaryWatermarkRange range, ChatSessionSummaryDTO existing) {
        // 1. Generate structured summary (with retry)
        StructuredSummary structured = generateStructuredSummaryWithRetry(turns);

        // 2. Merge anchored entities (regex-based + structured entities from LLM)
        Map<String, List<String>> mergedAnchors = mergeAnchoredEntities(
                existing == null ? Map.of() : existing.getAnchoredEntities(),
                extractAnchoredEntities(turns),
                structured.entities()
        );
        warnIfAnchorsMissing(sessionId, structured.summary(), mergedAnchors);

        // 3. Prepare the segment. Persistence happens later in one short transaction.
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
        // 4. Merge synopsis deterministically
        String existingSynopsis = existing == null || existing.getSynopsis() == null ? "" : existing.getSynopsis().trim();
        String nextSynopsis = mergeSynopsis(existingSynopsis, structured.summary());

        // 5. Persist updated summary (success path clears failure state)
        int existingSegmentCount = existing == null || existing.getSegmentCount() == null ? 0 : existing.getSegmentCount();
        ChatSessionSummaryDTO updated = ChatSessionSummaryDTO.builder()
                .sessionId(sessionId)
                .summarizedUntilSeqNo(range.endInclusiveSeqNo())
                .synopsis(nextSynopsis)
                .structuredSummaryJson(existing == null ? null : existing.getStructuredSummaryJson())
                .anchoredEntities(mergedAnchors)
                .segmentCount(existingSegmentCount + 1)
                .consecutiveFailures(0)
                .failedStartSeqNo(null)
                .failedEndSeqNo(null)
                .lastFailureClass(null)
                .nextRetryAt(null)
                .build();

        MemoryCompactionCommitService.CommitOutcome commitOutcome;
        try {
            commitOutcome = commitService.commit(range.startExclusiveSeqNo(), segment, updated);
        } catch (RuntimeException e) {
            throw new SummarizationFailedException(
                    "Failed to commit summary atomically: sessionId=" + sessionId, e);
        }
        if (commitOutcome == MemoryCompactionCommitService.CommitOutcome.STALE_WATERMARK) {
            log.info("Discarding stale compaction result: sessionId={}, expectedWatermark={}",
                    sessionId, range.startExclusiveSeqNo());
            return new SummaryResult(false, range, List.of());
        }

        return new SummaryResult(true, range, turns,
                List.of(segment),
                nextSynopsis);
    }

    /**
     * Splits the turn range in half and processes each sub-range independently.
     * Partial success advances the watermark only through successful segments.
     */
    private SummaryResult splitAndSummarize(String sessionId, List<AtomicConversationTurn> turns,
                                             SummaryWatermarkRange range, ChatSessionSummaryDTO existing) {
        int mid = turns.size() / 2;
        List<AtomicConversationTurn> firstHalf = new ArrayList<>(turns.subList(0, mid));
        List<AtomicConversationTurn> secondHalf = new ArrayList<>(turns.subList(mid, turns.size()));

        log.info("Splitting compaction range: sessionId={}, turns={}, firstHalf={}, secondHalf={}",
                sessionId, turns.size(), firstHalf.size(), secondHalf.size());

        // First half range
        SummaryWatermarkRange firstRange = new SummaryWatermarkRange(
                sessionId, range.lastSummarizedSeqNo(), firstHalf.get(firstHalf.size() - 1).endSeqNo());
        SummaryResult firstResult = summarizeRange(sessionId, firstHalf, firstRange, existing);

        if (!firstResult.updated()) {
            return firstResult;
        }

        // Second half — re-read existing to get updated state after first half's save
        ChatSessionSummaryDTO afterFirst = chatSessionSummaryRepository.findBySessionId(sessionId);
        SummaryWatermarkRange secondRange = new SummaryWatermarkRange(
                sessionId, firstHalf.get(firstHalf.size() - 1).endSeqNo(), range.endInclusiveSeqNo());
        SummaryResult secondResult = summarizeRange(sessionId, secondHalf, secondRange, afterFirst);

        // Merge results: turns and segments from both halves, synopsis from latest
        List<AtomicConversationTurn> allTurns = new ArrayList<>(firstResult.turns());
        if (secondResult.updated()) {
            allTurns.addAll(secondResult.turns());
        }

        List<ChatSessionSummarySegmentDTO> allSegments = new ArrayList<>(firstResult.segments());
        allSegments.addAll(secondResult.segments());

        // Effective range covers only successfully processed portion
        SummaryWatermarkRange effectiveRange = range;
        if (!secondResult.updated()) {
            effectiveRange = firstRange;
        }

        String synopsis = secondResult.synopsis() != null ? secondResult.synopsis() : firstResult.synopsis();

        return new SummaryResult(true, effectiveRange, allTurns, allSegments, synopsis);
    }

    /**
     * Records failure state in the summary row: increments consecutiveFailures,
     * sets failure range. Sets nextRetryAt only when consecutiveFailures reaches
     * maxConsecutiveFailures threshold, to avoid premature backoff.
     */
    private SummaryResult recordFailure(String sessionId, SummaryWatermarkRange range,
                                         ChatSessionSummaryDTO existing, Exception failure) {
        int currentFailures = existing == null || existing.getConsecutiveFailures() == null
                ? 0 : existing.getConsecutiveFailures();
        int newFailures = currentFailures + 1;

        // Only activate backoff when threshold is reached
        LocalDateTime nextRetryAt = null;
        if (newFailures >= maxConsecutiveFailures) {
            nextRetryAt = LocalDateTime.now().plusSeconds((long) newFailures * failureBackoffSeconds);
        }

        long existingWatermark = existing == null || existing.getSummarizedUntilSeqNo() == null
                ? 0L : existing.getSummarizedUntilSeqNo();

        ChatSessionSummaryDTO failureUpdate = ChatSessionSummaryDTO.builder()
                .sessionId(sessionId)
                .summarizedUntilSeqNo(existingWatermark)
                .synopsis(existing == null ? null : existing.getSynopsis())
                .structuredSummaryJson(existing == null ? null : existing.getStructuredSummaryJson())
                .anchoredEntities(existing == null ? null : existing.getAnchoredEntities())
                .segmentCount(existing == null ? 0 : existing.getSegmentCount())
                .consecutiveFailures(newFailures)
                .failedStartSeqNo(range.startExclusiveSeqNo() + 1)
                .failedEndSeqNo(range.endInclusiveSeqNo())
                .lastFailureClass(failure.getClass().getSimpleName())
                .nextRetryAt(nextRetryAt)
                .build();

        boolean saved = saveWithRetry(failureUpdate, sessionId);

        log.warn("Compaction failed: sessionId={}, consecutiveFailures={}, nextRetryAt={}, errorClass={}",
                sessionId, newFailures, nextRetryAt, failure.getClass().getSimpleName());

        return new SummaryResult(false, range, List.of());
    }

    /**
     * Retries saveOrUpdate up to maxRetries + 1 total attempts on optimistic lock conflict.
     * The repository's saveOrUpdate already re-reads the existing row internally,
     * so each retry picks up the current version.
     */
    private boolean saveWithRetry(ChatSessionSummaryDTO summary, String sessionId) {
        int totalAttempts = maxRetries + 1;
        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            if (chatSessionSummaryRepository.saveOrUpdate(summary)) {
                return true;
            }
            log.debug("Optimistic lock conflict on summary save (attempt {}/{}): sessionId={}",
                    attempt + 1, totalAttempts, sessionId);
        }
        return false;
    }

    /**
     * Generates a structured summary with retry on blank/invalid model output.
     * Throws {@link PromptTooLongException} when the prompt exceeds the model's context window.
     * After all retries, falls back to deterministic summary from raw turns.
     */
    private StructuredSummary generateStructuredSummaryWithRetry(List<AtomicConversationTurn> turns) {
        Exception lastException = null;
        int totalAttempts = maxRetries + 1;

        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            try {
                String prompt = buildStructuredPrompt(turns);
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
                log.warn("Summary model returned blank or unparseable content (attempt {}/{}), retrying",
                        attempt + 1, totalAttempts);
                lastException = new IllegalStateException("Blank or unparseable summary output");
            } catch (Exception e) {
                if (isPromptTooLong(e)) {
                    throw new PromptTooLongException(e);
                }
                log.warn("Summary generation failed (attempt {}/{}): errorClass={}",
                        attempt + 1, totalAttempts, e.getClass().getSimpleName());
                lastException = e;
            }
        }

        // All retries exhausted — use deterministic fallback
        log.warn("All {} attempts exhausted, using deterministic fallback", totalAttempts);
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

    /**
     * Detects whether an exception indicates the prompt exceeded the model's context window.
     */
    static boolean isPromptTooLong(Exception e) {
        if (e instanceof PromptTooLongException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("context_length")
                || lower.contains("context length")
                || lower.contains("token limit")
                || lower.contains("max_tokens")
                || lower.contains("too many tokens")
                || lower.contains("prompt is too long")
                || lower.contains("maximum context")
                || lower.contains("exceeds the maximum")
                || lower.contains("context window");
    }

    /**
     * Signals that the summarization prompt exceeded the model's context window.
     * The caller should split the range and retry.
     */
    static class PromptTooLongException extends RuntimeException {
        PromptTooLongException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Signals that the summarization failed after all retries and the failure should be recorded.
     */
    static class SummarizationFailedException extends RuntimeException {
        SummarizationFailedException(String message) {
            super(message);
        }

        SummarizationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
