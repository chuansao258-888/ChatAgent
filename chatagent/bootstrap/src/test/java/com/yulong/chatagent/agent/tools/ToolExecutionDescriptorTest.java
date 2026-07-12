package com.yulong.chatagent.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 {@link ToolExecutionDescriptor} 的副作用/确认/重试判定。这些判定是 coordinator
 * 决定是否先确认、能否自动重试、以及未知回调如何 fail-closed 的依据（ARRB-DEC-017/018）。
 */
class ToolExecutionDescriptorTest {

    @Test
    void unknownDefaultsRequireConfirmationAndAreNotRetryable() {
        ToolExecutionDescriptor unknown = ToolExecutionDescriptor.unknown("mcp_weather");

        assertThat(unknown.effectClass()).isEqualTo(ToolEffectClass.UNKNOWN);
        assertThat(unknown.deadlineMode()).isEqualTo(DeadlineMode.UNSUPPORTED);
        assertThat(unknown.returnDirect()).isFalse();
        assertThat(unknown.requiresConfirmation()).isTrue();
        assertThat(unknown.retryable()).isFalse();
    }

    @Test
    void readOnlyDoesNotRequireConfirmationAndIsRetryable() {
        ToolExecutionDescriptor readOnly = ToolExecutionDescriptor.readOnlyEnforced("webSearch", false);

        assertThat(readOnly.effectClass()).isEqualTo(ToolEffectClass.READ_ONLY);
        assertThat(readOnly.deadlineMode()).isEqualTo(DeadlineMode.ENFORCED);
        assertThat(readOnly.requiresConfirmation()).isFalse();
        assertThat(readOnly.retryable()).isTrue();
    }

    @Test
    void idempotentIsRetryableButStillRequiresConfirmation() {
        ToolExecutionDescriptor idempotent = new ToolExecutionDescriptor(
                "idempotentAction", ToolEffectClass.IDEMPOTENT, DeadlineMode.ENFORCED, false, 0);

        // 幂等副作用仍需精确确认（首次提案不派发），但确认后可一次有限重试。
        assertThat(idempotent.requiresConfirmation()).isTrue();
        assertThat(idempotent.retryable()).isTrue();
    }

    @Test
    void nonIdempotentRequiresConfirmationAndIsNotRetryable() {
        ToolExecutionDescriptor nonIdempotent = new ToolExecutionDescriptor(
                "sendEmail", ToolEffectClass.NON_IDEMPOTENT, DeadlineMode.UNSUPPORTED, false, 0);

        assertThat(nonIdempotent.requiresConfirmation()).isTrue();
        assertThat(nonIdempotent.retryable()).isFalse();
    }

    @Test
    void perRunDispatchCapIsOptional() {
        ToolExecutionDescriptor withCap = new ToolExecutionDescriptor(
                "rateLimited", ToolEffectClass.READ_ONLY, DeadlineMode.ENFORCED, false, 3);
        ToolExecutionDescriptor withoutCap = ToolExecutionDescriptor.readOnlyEnforced("noCap", false);

        assertThat(withCap.hasPerRunDispatchCap()).isTrue();
        assertThat(withCap.perRunDispatchCap()).isEqualTo(3);
        assertThat(withoutCap.hasPerRunDispatchCap()).isFalse();
    }

    @Test
    void builtInToolsDeclareReadOnlyEffectClass() {
        // 会话文件检索按 owned adapter contract 是只读（ARRB-DEC-009）。Web 搜索同理，
        // 这里通过 SessionFileTools 验证 built-in 工具显式覆盖了 effectClass 默认值。
        SessionFileTools sessionFileTools = new SessionFileTools(null, null, null);
        assertThat(sessionFileTools.effectClass()).isEqualTo(ToolEffectClass.READ_ONLY);
    }

    @Test
    void undeclaredToolDefaultsToUnknown() {
        // 未声明 effectClass 的工具（含 MCP）默认 UNKNOWN，按最保守路径处理。
        Tool undeclared = new Tool() {
            @Override public String getName() { return "undeclared"; }
            @Override public String getDescription() { return "no effect"; }
            @Override public ToolType getType() { return ToolType.OPTIONAL; }
        };
        assertThat(undeclared.effectClass()).isEqualTo(ToolEffectClass.UNKNOWN);
    }
}
