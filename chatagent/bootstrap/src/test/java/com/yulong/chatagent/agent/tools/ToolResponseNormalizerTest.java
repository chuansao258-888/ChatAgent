package com.yulong.chatagent.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 {@link ToolResponseNormalizer} 的输出边界与 code-point-safe 截断。
 * 这是 ARRB-AC-005 "output over 12,000 UTF-16 code units are bounded with stable outcomes/markers" 的核心。
 */
class ToolResponseNormalizerTest {

    @Test
    void shortResponseIsUnchangedAndNotTruncated() {
        ToolResponseNormalizer.NormalizedResponse result =
                ToolResponseNormalizer.normalize("ok");

        assertThat(result.truncated()).isFalse();
        assertThat(result.text()).isEqualTo("ok");
        assertThat(result.fullHash()).hasSize(64);
    }

    @Test
    void nullResponseBecomesEmptyString() {
        ToolResponseNormalizer.NormalizedResponse result =
                ToolResponseNormalizer.normalize(null);

        assertThat(result.truncated()).isFalse();
        assertThat(result.text()).isEmpty();
    }

    @Test
    void responseAboveLimitIsTruncatedWithStableMarker() {
        // 构造一个超过 12,000 code units 的纯 ASCII 文本。
        String big = IntStream.range(0, 20_000).mapToObj(i -> "x").reduce("", String::concat);
        assertThat(big.length()).isGreaterThan(ToolResponseNormalizer.MAX_MODEL_VISIBLE_CODE_UNITS);

        ToolResponseNormalizer.NormalizedResponse result = ToolResponseNormalizer.normalize(big);

        assertThat(result.truncated()).isTrue();
        assertThat(result.text()).endsWith(ToolResponseNormalizer.TRUNCATION_MARKER);
        // 规范化后文本不超过上限（截断标记占的预算已被扣除）。
        assertThat(result.text().length()).isLessThanOrEqualTo(ToolResponseNormalizer.MAX_MODEL_VISIBLE_CODE_UNITS);
        // fullHash 仍然是原始正文的 SHA-256，长度固定。
        assertThat(result.fullHash()).hasSize(64);
    }

    @Test
    void truncationIsCodePointSafeForSurrogatePairs() {
        // 用 emoji（surrogate pair, 每个 2 个 UTF-16 code unit）填满到正好越界，
        // 确保截断不会在一个 surrogate pair 中间断开，避免产生 lone surrogate。
        String pair = "😀"; // U+1F600, 一个 surrogate pair
        StringBuilder sb = new StringBuilder();
        // 6001 个 pair = 12002 code units > 12000
        for (int i = 0; i < 6001; i++) {
            sb.append(pair);
        }
        String big = sb.toString();
        assertThat(big.length()).isGreaterThan(ToolResponseNormalizer.MAX_MODEL_VISIBLE_CODE_UNITS);

        ToolResponseNormalizer.NormalizedResponse result = ToolResponseNormalizer.normalize(big);

        assertThat(result.truncated()).isTrue();
        String text = result.text();
        // 关键：截断后的文本不包含 lone surrogate（每个 char 要么是完整 BMP，要么成对 surrogate）。
        assertThat(isValidUtf16(text))
                .as("truncated text must not contain lone surrogates").isTrue();
        assertThat(text.length()).isLessThanOrEqualTo(ToolResponseNormalizer.MAX_MODEL_VISIBLE_CODE_UNITS);
    }

    @Test
    void normalizeResponsePreservesIdAndNameAndBoundsResponseData() {
        String big = "y".repeat(20_000);
        ToolResponseMessage.ToolResponse original = new ToolResponseMessage.ToolResponse(
                "call-1", "webSearch", big);

        ToolResponseMessage.ToolResponse normalized = ToolResponseNormalizer.normalizeResponse(original);

        assertThat(normalized.id()).isEqualTo("call-1");
        assertThat(normalized.name()).isEqualTo("webSearch");
        assertThat(normalized.responseData()).endsWith(ToolResponseNormalizer.TRUNCATION_MARKER);
        assertThat(normalized.responseData().length())
                .isLessThanOrEqualTo(ToolResponseNormalizer.MAX_MODEL_VISIBLE_CODE_UNITS);
    }

    /** 检查字符串里没有 lone surrogate：每个 high surrogate 后面必须紧跟一个 low surrogate。 */
    private static boolean isValidUtf16(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                // lone low surrogate
                return false;
            }
        }
        return true;
    }
}
