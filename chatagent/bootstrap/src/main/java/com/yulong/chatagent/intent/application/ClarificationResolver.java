package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a user's clarification reply back to one candidate intent node.
 */
@Component
public class ClarificationResolver {

    private static final Pattern DIGIT_PATTERN = Pattern.compile("(\\d+)");
    private static final Map<String, Integer> CHINESE_ORDINALS = buildChineseOrdinals();

    public IntentNodeDTO resolve(String reply, List<IntentNodeDTO> candidates) {
        if (!StringUtils.hasText(reply) || candidates == null || candidates.isEmpty()) {
            return null;
        }

        String normalized = normalize(reply);
        Integer ordinal = parseOrdinal(normalized);
        if (ordinal != null && ordinal > 0 && ordinal <= candidates.size()) {
            return candidates.get(ordinal - 1);
        }

        for (IntentNodeDTO candidate : candidates) {
            if (candidate != null
                    && StringUtils.hasText(candidate.getName())
                    && normalized.contains(candidate.getName().trim().toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }

    private String normalize(String reply) {
        return reply.trim()
                .toLowerCase(Locale.ROOT)
                .replace("选择", "")
                .replace("选", "")
                .replace("我要", "")
                .replace("我选", "")
                .trim();
    }

    private Integer parseOrdinal(String normalized) {
        Matcher digitMatcher = DIGIT_PATTERN.matcher(normalized);
        if (digitMatcher.find()) {
            return Integer.parseInt(digitMatcher.group(1));
        }

        for (Map.Entry<String, Integer> entry : CHINESE_ORDINALS.entrySet()) {
            String token = entry.getKey();
            if (normalized.equals(token)
                    || normalized.equals("第" + token)
                    || normalized.equals("第" + token + "个")
                    || normalized.equals(token + "个")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Integer> buildChineseOrdinals() {
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        ordinals.put("一", 1);
        ordinals.put("二", 2);
        ordinals.put("两", 2);
        ordinals.put("三", 3);
        ordinals.put("四", 4);
        ordinals.put("五", 5);
        ordinals.put("六", 6);
        ordinals.put("七", 7);
        ordinals.put("八", 8);
        ordinals.put("九", 9);
        ordinals.put("十", 10);
        return ordinals;
    }
}
