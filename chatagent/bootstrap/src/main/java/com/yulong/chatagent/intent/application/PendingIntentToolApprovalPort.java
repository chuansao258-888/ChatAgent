package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.contract.ClarificationKind;
import com.yulong.chatagent.agent.tools.ToolApprovalChallenge;
import com.yulong.chatagent.agent.tools.ToolApprovalChallengeBuilder;
import com.yulong.chatagent.agent.tools.ToolApprovalPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Uses the existing session-scoped Redis pending-resolution store as approval authority. */
@Component
public class PendingIntentToolApprovalPort implements ToolApprovalPort {

    private final PendingIntentResolutionStore pendingStore;
    private final ToolApprovalChallengeBuilder challengeBuilder;

    public PendingIntentToolApprovalPort(PendingIntentResolutionStore pendingStore, ObjectMapper objectMapper) {
        this.pendingStore = pendingStore;
        this.challengeBuilder = new ToolApprovalChallengeBuilder(objectMapper);
    }

    @Override
    public ToolApprovalChallenge stageProposal(ToolApprovalRequest request) {
        String approvalId = "appr-" + UUID.randomUUID();
        ToolApprovalChallenge challenge = challengeBuilder.build(
                approvalId, request.toolName(), request.rawArguments());
        if (!challenge.isAcceptable()) {
            return challenge;
        }
        pendingStore.save(PendingIntentResolution.builder()
                .sessionId(request.sessionId())
                .originalQuery("")
                .clarificationKind(ClarificationKind.ACTION_CONFIRMATION)
                .missingDimensions(List.of(MissingDimension.CONFIRMATION))
                .attemptCount(0)
                .actionIdentity(challenge.argumentHash())
                .toolApprovalId(challenge.approvalId())
                .toolName(challenge.toolName())
                .canonicalToolArguments(challenge.canonicalArguments())
                .toolArgumentHash(challenge.argumentHash())
                .toolDescriptorHash(request.descriptorHash())
                .toolPolicyVersion(request.policyVersion())
                .toolContractVersion(request.contractVersion())
                .toolSafePreview(challenge.safePreview())
                .build());
        return challenge;
    }
}
