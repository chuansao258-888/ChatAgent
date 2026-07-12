package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 {@link ToolApprovalChallengeBuilder} 的脱敏、URL 剥离、码点上限与 payload 过大判定。
 * 这是 ARRB-DEC-008 精确确认安全预览的核心边界。
 */
class ToolApprovalChallengeBuilderTest {

    private final ToolApprovalChallengeBuilder builder =
            new ToolApprovalChallengeBuilder(new ObjectMapper());

    @Test
    void nullOrEmptyArgumentsCanonicalizeToEmptyObject() {
        ToolApprovalChallenge c1 = builder.build("appr-1", "sendEmail", null);
        ToolApprovalChallenge c2 = builder.build("appr-2", "sendEmail", "   ");

        assertThat(c1.canonicalArguments()).isEqualTo("{}");
        assertThat(c1.isAcceptable()).isTrue();
        assertThat(c2.canonicalArguments()).isEqualTo("{}");
        assertThat(c1.argumentHash()).hasSize(64);
    }

    @Test
    void canonicalArgumentsAreStableHashable() {
        // 相同语义、不同 key 顺序的参数应产生相同 canonical（ORDER_MAP_ENTRIES_BY_KEYS）。
        ToolApprovalChallenge c1 = builder.build("a", "t", "{\"b\":1,\"a\":2}");
        ToolApprovalChallenge c2 = builder.build("a", "t", "{\"a\":2,\"b\":1}");

        assertThat(c1.argumentHash()).isEqualTo(c2.argumentHash());
        assertThat(c1.canonicalArguments()).contains("\"a\"");
    }

    @Test
    void sensitiveKeysAreRedactedInSafePreview() {
        String args = "{\"user\":\"alice\",\"password\":\"hunter2\",\"apiKey\":\"sk-secret\",\"token\":\"t-123\"}";

        ToolApprovalChallenge challenge = builder.build("a", "sendEmail", args);

        assertThat(challenge.isAcceptable()).isTrue();
        assertThat(challenge.safePreview()).contains("alice");
        assertThat(challenge.safePreview()).doesNotContain("hunter2");
        assertThat(challenge.safePreview()).doesNotContain("sk-secret");
        assertThat(challenge.safePreview()).doesNotContain("t-123");
        assertThat(challenge.safePreview()).contains("[REDACTED]");
        // canonical 仍保留原始值（内部持久化），只有用户可见预览被脱敏。
        assertThat(challenge.canonicalArguments()).contains("hunter2");
    }

    @Test
    void urlCredentialsAreStrippedInSafePreview() {
        String args = "{\"endpoint\":\"https://user:pass@example.com/path?q=1#frag\"}";

        ToolApprovalChallenge challenge = builder.build("a", "httpPost", args);

        assertThat(challenge.safePreview()).contains("example.com");
        assertThat(challenge.safePreview()).doesNotContain("user:pass");
        assertThat(challenge.safePreview()).doesNotContain("q=1");
        assertThat(challenge.safePreview()).doesNotContain("#frag");
    }

    @Test
    void oversizePayloadIsRejectedAsTooLarge() {
        // 构造一个超过 8192 UTF-8 字节的合法 JSON。
        StringBuilder sb = new StringBuilder("{\"q\":\"");
        for (int i = 0; i < 9000; i++) {
            sb.append('x');
        }
        sb.append("\"}");

        ToolApprovalChallenge challenge = builder.build("a", "t", sb.toString());

        assertThat(challenge.isAcceptable()).isFalse();
        assertThat(challenge.violationCode()).isEqualTo("TOOL_APPROVAL_PAYLOAD_TOO_LARGE");
        assertThat(challenge.canonicalArguments()).isNull();
        // hash 仍可计算（用于关联），但预览是固定占位。
        assertThat(challenge.argumentHash()).hasSize(64);
        assertThat(challenge.safePreview()).isEqualTo("[payload too large]");
    }

    @Test
    void safePreviewIsCappedAt512CodePoints() {
        // 构造一个预览会超过 512 码点的合法 JSON（多个长 scalar）。
        StringBuilder val = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            val.append('y');
        }
        String args = "{\"field1\":\"" + val + "\"}";

        ToolApprovalChallenge challenge = builder.build("a", "t", args);

        assertThat(challenge.isAcceptable()).isTrue();
        // 单个 scalar 最多 80 码点，整体预览最多 512 码点。
        assertThat(challenge.safePreview().codePointCount(0, challenge.safePreview().length()))
                .isLessThanOrEqualTo(ToolApprovalChallengeBuilder.MAX_SAFE_PREVIEW_CODE_POINTS);
    }

    @Test
    void hashPrefixIsStableForCorrelation() {
        ToolApprovalChallenge challenge = builder.build("appr-7", "sendEmail", "{\"to\":\"a@b\"}");

        assertThat(challenge.hashPrefix()).hasSize(12);
        assertThat(challenge.argumentHash()).startsWith(challenge.hashPrefix());
    }

    @Test
    void controlCharsAreStrippedFromPreview() {
        // JSON-escaped control chars (\u0000, \u0007) decode to raw control chars; the preview must strip them.
        String args = "{\"note\":\"hello\\u0000world\\u0007\"}";

        ToolApprovalChallenge challenge = builder.build("a", "t", args);

        assertThat(challenge.safePreview()).doesNotContain("\u0000");
        assertThat(challenge.safePreview()).doesNotContain("\u0007");
        assertThat(challenge.safePreview()).contains("hello");
    }
}
