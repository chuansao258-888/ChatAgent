package com.yulong.chatagent.intent.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.intent.application.IntentTreeFacadeService;
import com.yulong.chatagent.intent.model.request.SetIntentNodeKnowledgeBasesRequest;
import com.yulong.chatagent.intent.model.request.UpsertIntentNodeRequest;
import com.yulong.chatagent.intent.model.response.CreateIntentNodeResponse;
import com.yulong.chatagent.intent.model.response.GetIntentTreeResponse;
import com.yulong.chatagent.intent.model.vo.IntentVersionVO;
import com.yulong.chatagent.model.common.ApiResponse;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator endpoints for managing the internal assistant's intent tree.
 */
@RestController
@RequestMapping("/api/admin/assistant/intent-tree")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class IntentTreeController {

    private final IntentTreeFacadeService intentTreeFacadeService;

    @GetMapping
    public ApiResponse<GetIntentTreeResponse> getIntentTree() {
        return ApiResponse.success(intentTreeFacadeService.getIntentTree());
    }

    @PostMapping("/nodes")
    public ApiResponse<CreateIntentNodeResponse> createIntentNode(@RequestBody UpsertIntentNodeRequest request) {
        return ApiResponse.success(intentTreeFacadeService.createIntentNode(request));
    }

    @PatchMapping("/nodes/{nodeId}")
    public ApiResponse<Void> updateIntentNode(@PathVariable String nodeId,
                                              @RequestBody UpsertIntentNodeRequest request) {
        intentTreeFacadeService.updateIntentNode(nodeId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/nodes/{nodeId}")
    public ApiResponse<Void> deleteIntentNode(@PathVariable String nodeId) {
        intentTreeFacadeService.deleteIntentNode(nodeId);
        return ApiResponse.success();
    }

    @PutMapping("/nodes/{nodeId}/knowledge-bases")
    public ApiResponse<Void> setIntentNodeKnowledgeBases(@PathVariable String nodeId,
                                                         @RequestBody SetIntentNodeKnowledgeBasesRequest request) {
        intentTreeFacadeService.setIntentNodeKnowledgeBases(nodeId, request);
        return ApiResponse.success();
    }

    @PostMapping("/publish")
    public ApiResponse<Integer> publishIntentTreeSnapshot() {
        return ApiResponse.success(intentTreeFacadeService.publishIntentTreeSnapshot());
    }

    @GetMapping("/versions")
    public ApiResponse<List<IntentVersionVO>> getIntentVersions() {
        return ApiResponse.success(intentTreeFacadeService.getIntentVersions());
    }

    @PutMapping("/versions/{version}/activate")
    public ApiResponse<Void> switchActiveIntentVersion(@PathVariable int version) {
        intentTreeFacadeService.switchActiveIntentVersion(version);
        return ApiResponse.success();
    }
}
