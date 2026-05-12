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
 * 澄清回答解析器。
 * <p>
 * 当系统上一轮已经把多个候选项列给用户时，用户下一轮的回复往往很短，
 * 例如“第一个”“选报销”“我要第二项”。
 * 这个类的职责就是把这种简短回答重新映射回一个具体的候选 {@link IntentNodeDTO}。
 * <p>
 * 当前解析策略比较轻量，优先支持两类输入：
 * <ul>
 *     <li>序号型：1 / 2 / 第一个 / 第二个 / 一 / 二；</li>
 *     <li>名称型：回答里直接包含候选名称。</li>
 * </ul>
 * 这里没有引入更重的语义模型，原因是澄清阶段的候选集合已经被 IntentRouter 缩得很小，
 * 用规则解析通常已经足够稳定，也能避免“为了回答一个第几个”再次调用模型。
 */
@Component
public class ClarificationResolver {

    private static final Pattern DIGIT_PATTERN = Pattern.compile("(\\d+)");
    private static final Map<String, Integer> CHINESE_ORDINALS = buildChineseOrdinals();

    public IntentNodeDTO resolve(String reply, List<IntentNodeDTO> candidates) {
        // 没有可解析的回答，或者当前根本没有候选项时，直接返回 null，交给上层继续澄清。
        if (!StringUtils.hasText(reply) || candidates == null || candidates.isEmpty()) {
            return null;
        }

        String normalized = normalize(reply);
        // 第一优先级：按序号解析。
        // 例如“2”“第二个”“我选一”都应该尽量稳定映射到候选列表里的顺序。
        Integer ordinal = parseOrdinal(normalized);
        if (ordinal != null && ordinal > 0 && ordinal <= candidates.size()) {
            return candidates.get(ordinal - 1);
        }

        // 第二优先级：按候选名称包含匹配。
        // 例如候选叫“差旅报销”，用户回复“报销”或完整名称时可以直接命中。
        for (IntentNodeDTO candidate : candidates) {
            if (candidate != null
                    && StringUtils.hasText(candidate.getName())
                    && normalized.contains(candidate.getName().trim().toLowerCase(Locale.ROOT))) {
                // 名称匹配不做复杂模糊检索，保持行为可预测：
                // 用户文本里出现哪个候选名，就认为他在选哪个候选。
                return candidate;
            }
        }
        return null;
    }

    private String normalize(String reply) {
        // 这里不是通用 NLP 规范化，只去掉澄清场景里常见的口语前缀。
        // 例如“我选第二个” -> “第二个”，让后续 ordinal / 名称匹配更稳定。
        return reply.trim()
                .toLowerCase(Locale.ROOT)
                .replace("选择", "")
                .replace("选", "")
                .replace("我要", "")
                .replace("我选", "")
                .trim();
    }

    private Integer parseOrdinal(String normalized) {
        // 先尝试解析数字，如“1”“2”“第3个”。
        Matcher digitMatcher = DIGIT_PATTERN.matcher(normalized);
        if (digitMatcher.find()) {
            return Integer.parseInt(digitMatcher.group(1));
        }

        // 再尝试解析中文序数表达，如“一”“第二个”“两个”。
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
        // 用 LinkedHashMap 保证匹配顺序稳定，虽然这里影响不大，但可读性更强。
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
