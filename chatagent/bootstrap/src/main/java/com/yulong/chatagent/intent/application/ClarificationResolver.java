package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.ClarificationKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically interprets replies to a pending route clarification. */
@Component
public class ClarificationResolver {

    private static final Pattern DIGIT_PATTERN = Pattern.compile("(\\d+)");
    private static final Map<String, Integer> CHINESE_ORDINALS = buildChineseOrdinals();
    private static final Map<String, Integer> ENGLISH_ORDINALS = buildEnglishOrdinals();
    private static final Set<String> NONE_PHRASES = Set.of(
            "none", "none of these", "neither", "no option", "都不是", "都不对", "没有一个");
    private static final Set<String> CANCEL_PHRASES = Set.of(
            "cancel", "stop", "skip", "never mind", "取消", "算了", "不用了", "跳过");
    private static final Set<String> SELECT_ALL_PHRASES = Set.of(
            "both", "all", "both of them", "all of them", "两个都要", "都要", "全部", "都选");
    private static final Set<String> CONFIRM_PHRASES = Set.of(
            "yes", "confirm", "confirmed", "proceed", "continue", "do it",
            "是", "确认", "继续", "执行", "同意");
    private static final Set<String> UNCERTAIN_PHRASES = Set.of(
            "maybe", "not sure", "unsure", "不知道", "不确定", "再想想");

    private final IntentSignalAnalyzer signalAnalyzer;

    public ClarificationResolver(IntentSignalAnalyzer signalAnalyzer) {
        this.signalAnalyzer = signalAnalyzer;
    }

    /** Single-selection facade retained for callers that do not need typed reply outcomes. */
    public IntentNodeDTO resolve(String reply, List<IntentNodeDTO> candidates) {
        ClarificationReply typed = resolveTyped(reply, candidates);
        return typed.outcome() == ReplyOutcome.SELECT_ONE ? typed.selected().get(0) : null;
    }

    public ClarificationReply resolveTyped(String reply, List<IntentNodeDTO> candidates) {
        if (!StringUtils.hasText(reply) || candidates == null || candidates.isEmpty()) {
            return ClarificationReply.unresolved();
        }
        String normalized = normalize(reply);
        if (CANCEL_PHRASES.contains(normalized)) {
            return ClarificationReply.of(ReplyOutcome.CANCEL);
        }
        if (NONE_PHRASES.contains(normalized)) {
            return ClarificationReply.of(ReplyOutcome.NONE_OF_THESE);
        }
        if (signalAnalyzer.isExplicitTopicSwitch(reply)) {
            return ClarificationReply.newTopic(signalAnalyzer.stripTopicSwitchPrefix(reply));
        }
        if (SELECT_ALL_PHRASES.contains(normalized)) {
            return ClarificationReply.selected(ReplyOutcome.SELECT_MANY, candidates);
        }

        List<IntentNodeDTO> ordinalMatches = ordinalMatches(normalized, candidates);
        if (ordinalMatches.size() == 1) {
            return ClarificationReply.selected(ReplyOutcome.SELECT_ONE, ordinalMatches);
        }
        if (ordinalMatches.size() > 1) {
            return ClarificationReply.selected(ReplyOutcome.SELECT_MANY, ordinalMatches);
        }

        List<IntentNodeDTO> exactNames = candidates.stream()
                .filter(candidate -> StringUtils.hasText(candidate.getName()))
                .filter(candidate -> normalized.contains(normalize(candidate.getName())))
                .toList();
        if (exactNames.size() == 1) {
            return ClarificationReply.selected(ReplyOutcome.SELECT_ONE, exactNames);
        }
        if (exactNames.size() > 1) {
            return ClarificationReply.selected(ReplyOutcome.SELECT_MANY, exactNames);
        }

        List<IntentNodeDTO> partialNames = candidates.stream()
                .filter(candidate -> StringUtils.hasText(candidate.getName()))
                .filter(candidate -> normalized.length() >= 2
                        && normalize(candidate.getName()).contains(normalized))
                .toList();
        if (partialNames.size() == 1) {
            return ClarificationReply.selected(ReplyOutcome.SELECT_ONE, partialNames);
        }

        List<IntentNodeDTO> exampleMatches = candidates.stream()
                .filter(candidate -> matchesReviewedExample(normalized, candidate))
                .toList();
        if (exampleMatches.size() == 1) {
            return ClarificationReply.selected(ReplyOutcome.SELECT_ONE, exampleMatches);
        }
        return ClarificationReply.unresolved();
    }

    /** Reuses the typed clarification outcomes for an execution-readiness pending state. */
    public ClarificationReply resolveExecutionReply(String reply,
                                                    List<IntentNodeDTO> candidates,
                                                    ClarificationKind kind) {
        if (!StringUtils.hasText(reply) || candidates == null || candidates.isEmpty()) {
            return ClarificationReply.unresolved();
        }
        String normalized = normalize(reply);
        if (CANCEL_PHRASES.contains(normalized)) {
            return ClarificationReply.of(ReplyOutcome.CANCEL);
        }
        if (NONE_PHRASES.contains(normalized)) {
            return ClarificationReply.of(ReplyOutcome.NONE_OF_THESE);
        }
        if (signalAnalyzer.isExplicitTopicSwitch(reply)) {
            return ClarificationReply.newTopic(signalAnalyzer.stripTopicSwitchPrefix(reply));
        }
        if (UNCERTAIN_PHRASES.contains(normalized)) {
            return ClarificationReply.unresolved();
        }
        if (kind == ClarificationKind.ACTION_CONFIRMATION) {
            return CONFIRM_PHRASES.contains(normalized)
                    ? ClarificationReply.selected(ReplyOutcome.SELECT_ONE, candidates)
                    : ClarificationReply.unresolved();
        }
        return ClarificationReply.selected(ReplyOutcome.SELECT_ONE, candidates);
    }

    /** Deterministic yes/cancel/topic-switch parser for an exact pending tool proposal. */
    public ClarificationReply resolveActionConfirmation(String reply) {
        if (!StringUtils.hasText(reply)) {
            return ClarificationReply.unresolved();
        }
        String normalized = normalize(reply);
        if (CANCEL_PHRASES.contains(normalized) || NONE_PHRASES.contains(normalized)) {
            return ClarificationReply.of(ReplyOutcome.CANCEL);
        }
        if (signalAnalyzer.isExplicitTopicSwitch(reply)) {
            return ClarificationReply.newTopic(signalAnalyzer.stripTopicSwitchPrefix(reply));
        }
        return CONFIRM_PHRASES.contains(normalized)
                ? ClarificationReply.of(ReplyOutcome.SELECT_ONE)
                : ClarificationReply.unresolved();
    }

    private List<IntentNodeDTO> ordinalMatches(String normalized, List<IntentNodeDTO> candidates) {
        LinkedHashSet<Integer> ordinals = new LinkedHashSet<>();
        Matcher digits = DIGIT_PATTERN.matcher(normalized);
        while (digits.find()) {
            ordinals.add(Integer.parseInt(digits.group(1)));
        }
        if (ordinals.isEmpty()) {
            Integer ordinal = parseNamedOrdinal(normalized);
            if (ordinal != null) {
                ordinals.add(ordinal);
            }
        }
        List<IntentNodeDTO> selected = new ArrayList<>();
        for (Integer ordinal : ordinals) {
            if (ordinal > 0 && ordinal <= candidates.size()) {
                selected.add(candidates.get(ordinal - 1));
            }
        }
        return List.copyOf(selected);
    }

    private boolean matchesReviewedExample(String normalized, IntentNodeDTO candidate) {
        if (candidate.getExamples() == null) {
            return false;
        }
        return candidate.getExamples().stream().filter(StringUtils::hasText)
                .map(this::normalize)
                .anyMatch(example -> normalized.equals(example)
                        || (normalized.length() >= 3 && example.contains(normalized)));
    }

    private String normalize(String reply) {
        return reply.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^(?:i choose|choose|select|option|我选择|选择|我选|选|我要)\\s*", "")
                .replaceAll("[。.!！?？,，;；]+$", "")
                .trim();
    }

    private Integer parseNamedOrdinal(String normalized) {
        for (Map.Entry<String, Integer> entry : CHINESE_ORDINALS.entrySet()) {
            String token = entry.getKey();
            String quoted = Pattern.quote(token);
            if (normalized.equals(token)
                    || normalized.equals("第" + token)
                    || normalized.matches(".*(?:第)?" + quoted + "(?:个|项|条|类|种|位|号)(?:比较像|更合适|吧|呢|呀|啊|嘛|哦|了)?$")) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, Integer> entry : ENGLISH_ORDINALS.entrySet()) {
            if (normalized.matches(".*\\b" + Pattern.quote(entry.getKey()) + "\\b.*")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Integer> buildChineseOrdinals() {
        Map<String, Integer> result = new LinkedHashMap<>();
        String[] values = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 0; i < values.length; i++) {
            result.put(values[i], i + 1);
        }
        result.put("两", 2);
        return result;
    }

    private static Map<String, Integer> buildEnglishOrdinals() {
        Map<String, Integer> result = new LinkedHashMap<>();
        String[] named = {"first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth"};
        String[] cardinal = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        for (int i = 0; i < named.length; i++) {
            result.put(named[i], i + 1);
            result.put(cardinal[i], i + 1);
        }
        return result;
    }

    public enum ReplyOutcome {
        SELECT_ONE,
        SELECT_MANY,
        NONE_OF_THESE,
        CANCEL,
        NEW_TOPIC,
        UNRESOLVED
    }

    public record ClarificationReply(
            ReplyOutcome outcome,
            List<IntentNodeDTO> selected,
            String newTopicText
    ) {
        public ClarificationReply {
            selected = selected == null ? List.of() : List.copyOf(selected);
        }

        static ClarificationReply selected(ReplyOutcome outcome, List<IntentNodeDTO> selected) {
            return new ClarificationReply(outcome, selected, null);
        }

        static ClarificationReply of(ReplyOutcome outcome) {
            return new ClarificationReply(outcome, List.of(), null);
        }

        static ClarificationReply newTopic(String text) {
            return new ClarificationReply(ReplyOutcome.NEW_TOPIC, List.of(), text);
        }

        static ClarificationReply unresolved() {
            return of(ReplyOutcome.UNRESOLVED);
        }
    }
}
