package com.yulong.chatagent.knowledge.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.knowledge.application.KnowledgeBaseFacadeService;
import com.yulong.chatagent.knowledge.model.request.CreateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.response.CreateKnowledgeBaseResponse;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeBaseResponse;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeBasesResponse;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator endpoints for knowledge-base CRUD flows.
 */
@RestController
@RequestMapping("/api/admin/knowledge-bases")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class KnowledgeBaseController {

    private final KnowledgeBaseFacadeService knowledgeBaseFacadeService;

    @PostMapping
    public ApiResponse<CreateKnowledgeBaseResponse> createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.success(knowledgeBaseFacadeService.createKnowledgeBase(request));
    }

    @GetMapping
    public ApiResponse<GetKnowledgeBasesResponse> getKnowledgeBases() {
        return ApiResponse.success(knowledgeBaseFacadeService.getKnowledgeBases());
    }

    @GetMapping("/{knowledgeBaseId}")
    public ApiResponse<GetKnowledgeBaseResponse> getKnowledgeBase(@PathVariable String knowledgeBaseId) {
        return ApiResponse.success(knowledgeBaseFacadeService.getKnowledgeBase(knowledgeBaseId));
    }

    @PatchMapping("/{knowledgeBaseId}")
    public ApiResponse<Void> updateKnowledgeBase(@PathVariable String knowledgeBaseId,
                                                 @RequestBody UpdateKnowledgeBaseRequest request) {
        knowledgeBaseFacadeService.updateKnowledgeBase(knowledgeBaseId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{knowledgeBaseId}/archive")
    public ApiResponse<Void> archiveKnowledgeBase(@PathVariable String knowledgeBaseId) {
        knowledgeBaseFacadeService.archiveKnowledgeBase(knowledgeBaseId);
        return ApiResponse.success();
    }

    @PostMapping("/{knowledgeBaseId}/restore")
    public ApiResponse<Void> restoreKnowledgeBase(@PathVariable String knowledgeBaseId) {
        knowledgeBaseFacadeService.restoreKnowledgeBase(knowledgeBaseId);
        return ApiResponse.success();
    }
}
