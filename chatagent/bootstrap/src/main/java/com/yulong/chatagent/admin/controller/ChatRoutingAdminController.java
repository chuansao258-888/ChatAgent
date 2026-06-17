package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.admin.application.ChatRoutingAdminFacadeService;
import com.yulong.chatagent.admin.model.request.UpdateChatRoutingCandidateOverrideRequest;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST endpoints for chat-routing visibility and runtime candidate overrides (requires ADMIN role).
 */
@RestController
@RequestMapping("/api/admin/chat-routing")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class ChatRoutingAdminController {

    private final ChatRoutingAdminFacadeService chatRoutingAdminFacadeService;

    @GetMapping("/state")
    public ApiResponse<GetChatRoutingStateResponse> getRoutingState() {
        return ApiResponse.success(chatRoutingAdminFacadeService.getRoutingState());
    }

    @PutMapping("/candidates/override")
    public ApiResponse<Void> updateCandidateOverride(
            @RequestBody UpdateChatRoutingCandidateOverrideRequest request) {
        chatRoutingAdminFacadeService.updateCandidateOverride(request);
        return ApiResponse.success();
    }

    @DeleteMapping("/candidates/{candidateId}/override")
    public ApiResponse<Void> clearCandidateOverride(@PathVariable String candidateId) {
        chatRoutingAdminFacadeService.clearCandidateOverride(candidateId);
        return ApiResponse.success();
    }
}
