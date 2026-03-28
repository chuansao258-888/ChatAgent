package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
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
 * Builds and persists a rolling summary for the portion of the chat history
 * that has moved outside the L1 runtime window.
 * Uses the previous L2 summary plus newly stable turns to refresh session memory incrementally.
 */
@Component
@Slf4j
public class IncrementalSummarizer {

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern SLASH_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?<!\\w)(?:[\\u00A5\\uFFE5$])?\\d+(?:\\.\\d{1,2})?(?!\\w)");
    private static final Pattern ORDER_PATTERN = Pattern.compile("\\b[A-Z]{2,}-?\\d{4,}\\b");

    private final TurnBasedContextExtractor turnBasedContextExtractor;
    private final SummaryWatermarkService summaryWatermarkService;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final ChatModelRouter chatModelRouter;
    private final String summaryModel;
    private final int summaryMaxChars;

    public IncrementalSummarizer(TurnBasedContextExtractor turnBasedContextExtractor,
                                 SummaryWatermarkService summaryWatermarkService,
                                 ChatSessionSummaryRepository chatSessionSummaryRepository,
                                 ChatModelRouter chatModelRouter,
                                 @Value("${chatagent.memory.summary-model:deepseek-chat}") String summaryModel,
                                 @Value("${chatagent.memory.summary-max-chars:500}") int summaryMaxChars) {
        this.turnBasedContextExtractor = turnBasedContextExtractor;
        this.summaryWatermarkService = summaryWatermarkService;
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
        this.chatModelRouter = chatModelRouter;
        this.summaryModel = summaryModel;
        this.summaryMaxChars = Math.max(summaryMaxChars, 120);
    }

    /**
     * Summarizes the newly stable portion of a session and advances the summary watermark.
     *
     * @param sessionId chat session identifier
     * @param anchorSeqNo latest persisted message sequence that is safe to summarize
     * @return true when the summary record was updated
     */
    public boolean summarize(String sessionId, long anchorSeqNo) {
        SummaryWatermarkRange range = summaryWatermarkService.resolvePendingRange(sessionId, anchorSeqNo);
        if (!range.hasPendingMessages()) {
            return false;
        }

        ChatSessionSummaryDTO existing = chatSessionSummaryRepository.findBySessionId(sessionId);
        List<AtomicConversationTurn> turns = turnBasedContextExtractor.extractPendingTurns(sessionId, range.endInclusiveSeqNo());
        Map<String, List<String>> mergedAnchors = mergeAnchoredEntities(
                existing == null ? Map.of() : existing.getAnchoredEntities(),
                extractAnchoredEntities(turns)
        );

        String existingSummary = existing == null || existing.getSummary() == null ? "" : existing.getSummary().trim();
        String nextSummary = turns.isEmpty()
                ? existingSummary
                : generateSummary(existingSummary, turns);
        nextSummary = enforceLengthCap(nextSummary);
        warnIfAnchorsMissing(sessionId, nextSummary, mergedAnchors);

        ChatSessionSummaryDTO updated = ChatSessionSummaryDTO.builder()
                .sessionId(sessionId)
                .lastSeqNo(range.endInclusiveSeqNo())
                .summary(nextSummary)
                .anchoredEntities(mergedAnchors)
                .build();
        return chatSessionSummaryRepository.saveOrUpdate(updated);
    }

    private String generateSummary(String existingSummary, List<AtomicConversationTurn> turns) {
        String prompt = buildPrompt(existingSummary, turns);
        try {
            ChatClient chatClient = chatModelRouter.route(summaryModel);
            String content = chatClient.prompt(prompt)
                    .call()
                    .content();
            if (StringUtils.hasText(content)) {
                return content.trim();
            }
            log.warn("Summary model returned blank content, fallback to deterministic summary");
        } catch (Exception e) {
            log.warn("Summary generation fallback triggered: error={}", e.getMessage());
        }
        return buildFallbackSummary(existingSummary, turns);
    }

    private String buildPrompt(String existingSummary, List<AtomicConversationTurn> turns) {
        return """
                You are updating a rolling memory summary for an enterprise assistant conversation.

                Rules:
                1. Preserve durable facts, user preferences, commitments, dates, amounts, and identifiers.
                2. Drop tool chatter, intermediate reasoning, and repeated phrasing.
                3. Keep the summary under %d characters. If space is tight, switch to key-value fact listing.
                4. Keep the summary concise and factual.
                5. Output only the updated summary text.

                Existing summary:
                %s

                New turns:
                %s
                """.formatted(
                summaryMaxChars,
                StringUtils.hasText(existingSummary) ? existingSummary : "(empty)",
                formatTurns(turns)
        );
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

    private String buildFallbackSummary(String existingSummary, List<AtomicConversationTurn> turns) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(existingSummary)) {
            builder.append(existingSummary.trim()).append('\n');
        }
        for (AtomicConversationTurn turn : turns) {
            if (turn.userMessages() != null) {
                for (String userMessage : turn.userMessages()) {
                    builder.append("User: ").append(userMessage).append('\n');
                }
            }
            if (StringUtils.hasText(turn.assistantConclusion())) {
                builder.append("Assistant: ").append(turn.assistantConclusion()).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String enforceLengthCap(String summary) {
        if (!StringUtils.hasText(summary) || summary.length() <= summaryMaxChars) {
            return summary == null ? "" : summary.trim();
        }
        int cutoff = Math.max(summary.lastIndexOf('\n', summaryMaxChars), summary.lastIndexOf(' ', summaryMaxChars));
        if (cutoff < summaryMaxChars / 2) {
            cutoff = summaryMaxChars;
        }
        return summary.substring(0, cutoff).trim();
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
                                                            Map<String, List<String>> extracted) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        appendAnchors(merged, existing);
        appendAnchors(merged, extracted);

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
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : anchors.entrySet()) {
            for (String value : entry.getValue()) {
                if (StringUtils.hasText(value) && !normalizedSummary.contains(value.toLowerCase())) {
                    missing.add(value);
                }
            }
        }
        if (!missing.isEmpty()) {
            log.warn("Anchored entities missing after summary refresh: sessionId={}, missing={}", sessionId, missing);
        }
    }
}
