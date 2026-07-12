package com.yulong.chatagent.agent.tools;

/**
 * ARRB Phase 1：精确工具确认的行为端口（plan ARRB-DEC-008）。
 * <p>
 * 它在 coordinator 与现有 ACTION_CONFIRMATION pending-resolution flow 之间建立唯一边界。
 * coordinator 把一个非只读/未知提案 stage 成一条 {@link ToolApprovalChallenge}，并停止本轮；
 * 下一轮用户回复由 {@link com.yulong.chatagent.intent.application.ClarificationResolver} 解析，
 * 接受后的精确 name + canonical argument hash 通过本端口回放校验后才派发一次。
 * <p>
 * 实现隐藏 pending store、canonicalization 与安全预览的细节。pending canonical 参数有界
 * （8,192 UTF-8 字节）、TTL 由现有 pending-store 设置控制、永不进 INFO/WARN 日志，
 * 并在 accept/reject/expire 时删除。
 */
public interface ToolApprovalPort {

    /**
     * 为一个非只读/未知提案 stage 一条确认挑战。coordinator 据此停止本轮、不再做模型调用，
     * 由现有 pending flow 把 challenge 发给用户。
     *
     * @param sessionId     会话 id
     * @param toolName      模型面向 callback 名
     * @param rawArguments  原始参数 JSON 文本
     * @return 永远非空的 challenge；若 payload 过大则 {@link ToolApprovalChallenge#violationCode()} 非空
     */
    ToolApprovalChallenge stageProposal(ToolApprovalRequest request);

    record ToolApprovalRequest(
            String sessionId,
            String turnId,
            String toolName,
            String rawArguments,
            String descriptorHash,
            String policyVersion,
            String contractVersion) {
    }
}
