package com.yulong.chatagent.knowledge.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.knowledge.application.KnowledgeDocumentFacadeService;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeDocumentsResponse;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Administrator endpoints for knowledge-base document management.
 */
@RestController
@RequestMapping("/api/admin/knowledge-bases/{knowledgeBaseId}/documents")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class KnowledgeDocumentController {

    private final KnowledgeDocumentFacadeService knowledgeDocumentFacadeService;

    @PostMapping("/upload")
    public ApiResponse<UploadKnowledgeDocumentResponse> uploadKnowledgeDocument(@PathVariable String knowledgeBaseId,
                                                                                @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(knowledgeDocumentFacadeService.uploadKnowledgeDocument(knowledgeBaseId, file));
    }

    @GetMapping
    public ApiResponse<GetKnowledgeDocumentsResponse> getKnowledgeDocuments(@PathVariable String knowledgeBaseId) {
        return ApiResponse.success(knowledgeDocumentFacadeService.getKnowledgeDocuments(knowledgeBaseId));
    }

    @PostMapping("/{documentId}/replace")
    public ApiResponse<UploadKnowledgeDocumentResponse> replaceKnowledgeDocument(@PathVariable String knowledgeBaseId,
                                                                                 @PathVariable String documentId,
                                                                                 @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(knowledgeDocumentFacadeService.replaceKnowledgeDocument(knowledgeBaseId, documentId, file));
    }

    @PostMapping("/{documentId}/archive")
    public ApiResponse<Void> archiveKnowledgeDocument(@PathVariable String knowledgeBaseId,
                                                      @PathVariable String documentId) {
        knowledgeDocumentFacadeService.archiveKnowledgeDocument(knowledgeBaseId, documentId);
        return ApiResponse.success();
    }
}
