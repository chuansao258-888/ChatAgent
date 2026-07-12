package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.agent.runtime.AgentRunBudget;
import com.yulong.chatagent.agent.runtime.contract.ApprovedToolProposal;

import java.util.Optional;

/**
 * ARRB Phase 1（cross-review F-1/F-2/F-4/F-5）：一次工具派发的上下文，把可选的
 * approval port、ledger port、budget 与 descriptor 解析器集中传给 coordinator。
 * <p>
 * 所有字段都是可选的：缺失时 coordinator 退回到"只做 preflight + per-call 隔离"的
 * 基线行为（向后兼容既有调用方）。运行时引擎在装配 coordinator 时传入完整上下文，
 * 从而启用 AC-006/007/008/009 的运行时级行为：
 * <ul>
 *   <li>{@link #approvalPort()}：非只读/未知提案首次不派发、stage 一条 challenge；</li>
 *   <li>{@link #ledgerPort()}：每个 dispatch 写 journal CAS 行 + 终态提交；</li>
 *   <li>{@link #budget()}：proposal/dispatch/LLM 预算消耗与耗尽判定；</li>
 *   <li>{@link #descriptorResolver()}：按名解析 effect class，决定确认/重试语义。</li>
 * </ul>
 * sessionId/turnId 用于 approval stage 与 journal execution key。
 */
public record ToolDispatchContext(
        String sessionId,
        String turnId,
        ToolApprovalPort approvalPort,
        ToolExecutionLedgerPort ledgerPort,
        AgentRunBudget budget,
        ToolExecutionDescriptorResolver descriptorResolver,
        ApprovedToolProposal approvedProposal,
        String policyVersion,
        String contractVersion) {

    public ToolDispatchContext(String sessionId,
                               String turnId,
                               ToolApprovalPort approvalPort,
                               ToolExecutionLedgerPort ledgerPort,
                               AgentRunBudget budget,
                               ToolExecutionDescriptorResolver descriptorResolver) {
        this(sessionId, turnId, approvalPort, ledgerPort, budget, descriptorResolver,
                null, "agent-runtime-v1", null);
    }

    public Optional<ToolApprovalPort> maybeApprovalPort() {
        return Optional.ofNullable(approvalPort);
    }

    public Optional<ToolExecutionLedgerPort> maybeLedgerPort() {
        return Optional.ofNullable(ledgerPort);
    }

    public Optional<AgentRunBudget> maybeBudget() {
        return Optional.ofNullable(budget);
    }

    public Optional<ToolExecutionDescriptorResolver> maybeDescriptorResolver() {
        return Optional.ofNullable(descriptorResolver);
    }

    /** 一个没有运行时 ports 的空上下文（向后兼容基线 coordinator 行为）。 */
    public static ToolDispatchContext empty() {
        return new ToolDispatchContext(null, null, null, null, null, null,
                null, "agent-runtime-v1", null);
    }
}
