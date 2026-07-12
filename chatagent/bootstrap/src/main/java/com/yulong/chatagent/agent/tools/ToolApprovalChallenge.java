package com.yulong.chatagent.agent.tools;

/**
 * ARRB Phase 1：一个非只读/未知工具提案的确定性确认挑战（plan ARRB-DEC-008）。
 * <p>
 * coordinator 不直接派发这类提案，而是生成一个 challenge，由现有 ACTION_CONFIRMATION 流程
 * 在下一轮把用户回复绑定到 approvalId + 精确 name/argument hash 的回放。canonicalArguments
 * 只在内部持久化（pending store），safePreview 是用户可见的唯一内容。
 *
 * @param approvalId        稳定 approval id（pending store 生成）
 * @param toolName          模型面向 callback 名
 * @param canonicalArguments canonical JSON（内部持久化用）；当 payload 过大时为 null
 * @param argumentHash      canonical 参数的 SHA-256，用于下一轮回放比对
 * @param safePreview       已脱敏/已裁剪的安全预览（用户可见）
 * @param violationCode     若提案不可接受（例如 payload 过大）的非空 typed code
 */
public record ToolApprovalChallenge(
        String approvalId,
        String toolName,
        String canonicalArguments,
        String argumentHash,
        String safePreview,
        String violationCode) {

    public boolean isAcceptable() {
        return violationCode == null;
    }

    /**
     * hash 前缀（前 12 个十六进制字符），用于在不暴露 canonical 载荷的前提下关联下一轮回放。
     */
    public String hashPrefix() {
        return argumentHash == null || argumentHash.length() < 12
                ? argumentHash : argumentHash.substring(0, 12);
    }
}
