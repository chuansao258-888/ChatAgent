package com.yulong.chatagent.knowledge.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.knowledge.application.KnowledgeDocumentStatusSseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Admin SSE endpoint for live knowledge-document status changes.
 */
@RestController
@RequestMapping("/api/sse/admin/knowledge-bases/{knowledgeBaseId}/documents")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class KnowledgeDocumentStatusSseController {

    private final KnowledgeDocumentStatusSseService knowledgeDocumentStatusSseService;
    private final ResourceAccessGuard resourceAccessGuard;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String knowledgeBaseId) {
        resourceAccessGuard.assertCanManageKnowledgeBase(UserContext.requireUser(), knowledgeBaseId);
        return knowledgeDocumentStatusSseService.connect(knowledgeBaseId);
    }
}
