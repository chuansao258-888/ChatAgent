package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.knowledge.application.AssistantKnowledgeBaseFacadeService;
import com.yulong.chatagent.knowledge.model.request.SetAssistantKnowledgeBasesRequest;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator endpoints for binding the internal assistant to knowledge bases.
 */
@RestController
@RequestMapping("/api/admin/assistant")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class AssistantKnowledgeBaseController {

    private final AssistantKnowledgeBaseFacadeService assistantKnowledgeBaseFacadeService;

    @GetMapping("/knowledge-bases")
    public ApiResponse<KnowledgeBaseVO[]> getAssistantKnowledgeBases() {
        return ApiResponse.success(assistantKnowledgeBaseFacadeService.getAssistantKnowledgeBases());
    }

    @PutMapping("/knowledge-bases")
    public ApiResponse<Void> setAssistantKnowledgeBases(@RequestBody SetAssistantKnowledgeBasesRequest request) {
        assistantKnowledgeBaseFacadeService.setAssistantKnowledgeBases(request);
        return ApiResponse.success();
    }
}
