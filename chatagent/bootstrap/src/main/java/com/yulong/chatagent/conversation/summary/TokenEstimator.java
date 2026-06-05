package com.yulong.chatagent.conversation.summary;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Shared token estimator for compaction policy decisions.
 * <p>
 * Uses a simple char-based heuristic: CJK characters count as 2 tokens,
 * all other characters count as 1 token. This is intentionally imprecise
 * and only used for policy thresholds, not for exact budget enforcement.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            count += isChinese(c) ? 2 : 1;
        }
        return count;
    }

    public static int estimateTurns(List<AtomicConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AtomicConversationTurn turn : turns) {
            if (turn.userMessages() != null) {
                for (String msg : turn.userMessages()) {
                    total += estimateTokens(msg);
                }
            }
            total += estimateTokens(turn.assistantConclusion());
        }
        return total;
    }

    private static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
