package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.admin.application.AssistantTemplateFacadeService;
import com.yulong.chatagent.admin.model.request.InitializeAssistantFromTemplateRequest;
import com.yulong.chatagent.admin.model.request.UpsertAssistantTemplateRequest;
import com.yulong.chatagent.admin.model.response.InitializeAssistantFromTemplateResponse;
import com.yulong.chatagent.admin.model.vo.AssistantTemplateVO;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrator endpoints for assistant template management.
 */
@RestController
@RequestMapping("/api/admin/assistant/templates")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class AssistantTemplateController {

    private final AssistantTemplateFacadeService assistantTemplateFacadeService;

    @GetMapping
    public ApiResponse<List<AssistantTemplateVO>> getTemplates() {
        return ApiResponse.success(assistantTemplateFacadeService.getTemplates());
    }

    @GetMapping("/{templateId}")
    public ApiResponse<AssistantTemplateVO> getTemplate(@PathVariable String templateId) {
        return ApiResponse.success(assistantTemplateFacadeService.getTemplate(templateId));
    }

    @PostMapping
    public ApiResponse<String> createTemplate(@RequestBody UpsertAssistantTemplateRequest request) {
        return ApiResponse.success(assistantTemplateFacadeService.createTemplate(request));
    }

    @PatchMapping("/{templateId}")
    public ApiResponse<Void> updateTemplate(@PathVariable String templateId,
                                            @RequestBody UpsertAssistantTemplateRequest request) {
        assistantTemplateFacadeService.updateTemplate(templateId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String templateId) {
        assistantTemplateFacadeService.deleteTemplate(templateId);
        return ApiResponse.success();
    }

    @PostMapping("/{templateId}/initialize")
    public ApiResponse<InitializeAssistantFromTemplateResponse> initializeAssistantFromTemplate(
            @PathVariable String templateId,
            @RequestBody(required = false) InitializeAssistantFromTemplateRequest request) {
        return ApiResponse.success(
                assistantTemplateFacadeService.initializeAssistantFromTemplate(templateId, request)
        );
    }
}
