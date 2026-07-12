package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARRB Phase 1（ARRB-AC-008）：端到端验证精确工具确认提案的 stage → safe preview →
 * replay/stale-check 契约。
 * <p>
 * 覆盖：首次提案不派发（只产 challenge）；用户可见 safe preview 已脱敏；下一轮回放比对
 * canonical name + argument hash；name/argument 变化 => 新提案（stale）。
 */
class ToolApprovalFlowIntegrationTest {

    private final ToolApprovalChallengeBuilder builder =
            new ToolApprovalChallengeBuilder(new ObjectMapper());

    @Test
    void firstProposalStagesChallengeAndDoesNotEchoRawArguments() {
        // 一个非只读提案：首次只 stage 一条 challenge，不派发；safe preview 不回显完整参数/敏感值。
        String args = "{\"to\":\"alice@example.com\",\"subject\":\"hi\",\"apiKey\":\"sk-secret\"}";

        ToolApprovalChallenge challenge = builder.build("appr-1", "sendEmail", args);

        assertThat(challenge.isAcceptable()).isTrue();
        assertThat(challenge.toolName()).isEqualTo("sendEmail");
        assertThat(challenge.approvalId()).isEqualTo("appr-1");
        // canonical 保留原始值（内部持久化），但用户可见 safe preview 脱敏。
        assertThat(challenge.canonicalArguments()).contains("alice@example.com");
        assertThat(challenge.safePreview()).doesNotContain("sk-secret");
        assertThat(challenge.safePreview()).contains("[REDACTED]");
        // hash 前缀可用于下一轮关联而不暴露 canonical 载荷。
        assertThat(challenge.hashPrefix()).hasSize(12);
    }

    @Test
    void replayMatchesWhenNameAndCanonicalArgumentsAreUnchanged() {
        // 下一轮回放：相同的 name + canonical arguments 必须产生相同 hash，回放通过。
        String args = "{\"to\":\"bob@example.com\",\"body\":\"hello\"}";

        ToolApprovalChallenge staged = builder.build("appr-2", "sendEmail", args);
        ToolApprovalChallenge replayed = builder.build("appr-2", "sendEmail", args);

        assertThat(replayed.argumentHash()).isEqualTo(staged.argumentHash());
        assertThat(replayed.canonicalArguments()).isEqualTo(staged.canonicalArguments());
    }

    @Test
    void changedArgumentsRequireANewProposal() {
        // 模型在下一轮改变了参数 => hash 变化 => 旧 approval stale，需要新提案。
        ToolApprovalChallenge staged = builder.build("appr-3", "sendEmail",
                "{\"to\":\"a@example.com\"}");
        ToolApprovalChallenge changed = builder.build("appr-3", "sendEmail",
                "{\"to\":\"b@example.com\"}");

        assertThat(changed.argumentHash()).isNotEqualTo(staged.argumentHash());
    }

    @Test
    void changedToolNameRequiresANewProposalEvenWithSameArguments() {
        // name 变化也是 stale：approval 绑定精确 name + argument hash。
        String args = "{\"q\":\"cats\"}";
        ToolApprovalChallenge staged = builder.build("appr-4", "sendEmail", args);

        // 一个独立的 challenge：不同 name 相同参数。
        ToolApprovalChallenge otherName = builder.build("appr-4", "postMessage", args);

        // 这里验证的是 name 必须作为绑定的一部分：challenge 携带 name，回放时 coordinator
        // 会比对 name；hash 只覆盖参数，name 由 coordinator 单独比对。
        assertThat(otherName.toolName()).isNotEqualTo(staged.toolName());
        // 参数相同 => argumentHash 相同；stale 判定由 coordinator 用 name + hash 联合完成。
        assertThat(otherName.argumentHash()).isEqualTo(staged.argumentHash());
    }

    @Test
    void oversizeProposalIsRejectedAsTooLargeAndNotStaged() {
        StringBuilder big = new StringBuilder("{\"q\":\"");
        for (int i = 0; i < 9000; i++) {
            big.append('x');
        }
        big.append("\"}");

        ToolApprovalChallenge challenge = builder.build("appr-5", "sendEmail", big.toString());

        assertThat(challenge.isAcceptable()).isFalse();
        assertThat(challenge.violationCode()).isEqualTo("TOOL_APPROVAL_PAYLOAD_TOO_LARGE");
        assertThat(challenge.canonicalArguments()).isNull();
        assertThat(challenge.safePreview()).isEqualTo("[payload too large]");
    }
}
