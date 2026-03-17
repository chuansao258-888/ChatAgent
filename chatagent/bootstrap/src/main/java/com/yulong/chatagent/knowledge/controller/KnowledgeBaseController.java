package com.yulong.chatagent.knowledge.controller;

import com.yulong.chatagent.knowledge.application.KnowledgeBaseFacadeService;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.knowledge.model.request.CreateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.response.CreateKnowledgeBaseResponse;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeBasesResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseFacadeService knowledgeBaseFacadeService;

    @GetMapping("/knowledge-bases")
    public ApiResponse<GetKnowledgeBasesResponse> getKnowledgeBases() {
        return ApiResponse.success(knowledgeBaseFacadeService.getKnowledgeBases());
    }

    @PostMapping("/knowledge-bases")
    public ApiResponse<CreateKnowledgeBaseResponse> createKnowledgeBase(
            @RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.success(knowledgeBaseFacadeService.createKnowledgeBase(request));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable String knowledgeBaseId) {
        knowledgeBaseFacadeService.deleteKnowledgeBase(knowledgeBaseId);
        return ApiResponse.success();
    }

    @PatchMapping("/knowledge-bases/{knowledgeBaseId}")
    public ApiResponse<Void> updateKnowledgeBase(@PathVariable String knowledgeBaseId,
                                                 @RequestBody UpdateKnowledgeBaseRequest request) {
        knowledgeBaseFacadeService.updateKnowledgeBase(knowledgeBaseId, request);
        return ApiResponse.success();
    }
}

