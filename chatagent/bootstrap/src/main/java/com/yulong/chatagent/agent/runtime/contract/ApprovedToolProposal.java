package com.yulong.chatagent.agent.runtime.contract;

/** Exact, bounded proposal released by the existing ACTION_CONFIRMATION flow. */
public record ApprovedToolProposal(
        String approvalId,
        String toolName,
        String canonicalArguments,
        String argumentHash,
        String descriptorHash,
        String policyVersion,
        String contractVersion) {
}
