package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.UpdateChatRoutingCandidateOverrideRequest;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;

/** Admin operations for inspecting chat-routing state and managing runtime candidate overrides. */
public interface ChatRoutingAdminFacadeService {

    GetChatRoutingStateResponse getRoutingState();

    void updateCandidateOverride(UpdateChatRoutingCandidateOverrideRequest request);

    void clearCandidateOverride(String candidateId);
}
