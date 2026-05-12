package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
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
 * 增量会话摘要器。
 * <p>
 * 它负责把“已经滑出 L1 原始记忆窗口”的那部分历史压缩成 L2 摘要，
 * 并且不是每次从零开始重算，而是：
 * <ol>
 *     <li>读取现有摘要；</li>
 *     <li>找到这次新稳定下来的 turn 片段；</li>
 *     <li>把旧摘要与新 turn 合并成下一版摘要。</li>
 * </ol>
 * 这样可以把摘要成本控制在“增量更新”而不是“全量重建”。
 */
@Component
@Slf4j
public class IncrementalSummarizer {

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern SLASH_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?<!\\w)(?:[\\u00A5\\uFFE5$])?\\d+(?:\\.\\d{1,2})?(?!\\w)");
    private static final Pattern ORDER_PATTERN = Pattern.compile("\\b[A-Z]{2,}-?\\d{4,}\\b");

    private final PromptLoader promptLoader;
    private final TurnBasedContextExtractor turnBasedContextExtractor;
    private final SummaryWatermarkService summaryWatermarkService;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;
    private final ChatModelRouter chatModelRouter;
    private final String summaryModel;
    private final int summaryMaxChars;

    public IncrementalSummarizer(PromptLoader promptLoader,
                                 TurnBasedContextExtractor turnBasedContextExtractor,
                                 SummaryWatermarkService summaryWatermarkService,
                                 ChatSessionSummaryRepository chatSessionSummaryRepository,
                                 ChatModelRouter chatModelRouter,
                                 @Value("${chatagent.memory.summary-model:deepseek-chat}") String summaryModel,
                                 @Value("${chatagent.memory.summary-max-chars:500}") int summaryMaxChars) {
        this.promptLoader = promptLoader;
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
            // 没有待推进的 seq_no 区间，说明当前 anchor 已经被摘要覆盖。
            return false;
        }

        ChatSessionSummaryDTO existing = chatSessionSummaryRepository.findBySessionId(sessionId);
        // 只提取增量区间内、按 turn 聚合后的可摘要内容。
        List<AtomicConversationTurn> turns = turnBasedContextExtractor.extractPendingTurns(sessionId, range.endInclusiveSeqNo());
        Map<String, List<String>> mergedAnchors = mergeAnchoredEntities(
                existing == null ? Map.of() : existing.getAnchoredEntities(),
                extractAnchoredEntities(turns)
        );

        // 新摘要不是覆盖式“重写全部历史”，而是在旧摘要之上增量刷新。
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
            // 模型空返回也视作不可用，继续走确定性回退。
            log.warn("Summary model returned blank content, fallback to deterministic summary");
        } catch (Exception e) {
            // 摘要是后台能力，不应该因为模型瞬时故障就整体失效。
            log.warn("Summary generation fallback triggered: error={}", e.getMessage());
        }
        return buildFallbackSummary(existingSummary, turns);
    }

    private String buildPrompt(String existingSummary, List<AtomicConversationTurn> turns) {
        return promptLoader.render(PromptConstants.SUMMARIZER_MEMORY, java.util.Map.of(
                "summaryMaxChars", String.valueOf(summaryMaxChars),
                "existingSummary", StringUtils.hasText(existingSummary) ? existingSummary : "(empty)",
                "formattedTurns", formatTurns(turns)
        ));
    }

    private String formatTurns(List<AtomicConversationTurn> turns) {
        // 这里把 turn 格式化成 prompt 文本输入给 summary model，
        // 尽量保留“谁问了什么、最终答了什么”的结构化顺序。
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
        // 确定性回退方案不依赖模型，只做朴素拼接。
        // 质量可能略差，但能保证后台摘要链不停摆。
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
        // 摘要是放进 system prompt / memory 的长文本，长度必须可控。
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
        // 锚定实体是对摘要正文的一个保护：
        // 即使摘要模型压缩得过度，也能额外保留日期、金额、订单号等稳定事实。
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
        // 新旧锚点是累积关系，不是覆盖关系。
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
        // 这里不强制失败，只做 warning。
        // 目的是提醒摘要刷新后可能丢失了关键事实，便于后续人工观察和调优 prompt。
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
