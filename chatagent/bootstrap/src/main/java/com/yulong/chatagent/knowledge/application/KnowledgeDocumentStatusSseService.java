package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.converter.KnowledgeDocumentConverter;
import com.yulong.chatagent.knowledge.model.realtime.KnowledgeDocumentStatusSseMessage;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Owns the realtime stream for document status changes scoped to one knowledge base.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentStatusSseService {

    private static final String STREAM_PREFIX = "knowledge-base-documents:";

    private final SseService sseService;
    private final KnowledgeDocumentConverter knowledgeDocumentConverter;

    public SseEmitter connect(String knowledgeBaseId) {
        return sseService.connect(streamKey(knowledgeBaseId));
    }

    public void publishStatusUpdated(KnowledgeDocumentDTO knowledgeDocument) {
        if (knowledgeDocument == null
                || !StringUtils.hasText(knowledgeDocument.getKnowledgeBaseId())
                || !StringUtils.hasText(knowledgeDocument.getId())) {
            return;
        }
        sseService.publish(
                streamKey(knowledgeDocument.getKnowledgeBaseId()),
                KnowledgeDocumentStatusSseMessage.builder()
                        .type(KnowledgeDocumentStatusSseMessage.Type.DOCUMENT_STATUS_UPDATED)
                        .payload(KnowledgeDocumentStatusSseMessage.Payload.builder()
                                .document(knowledgeDocumentConverter.toVO(knowledgeDocument))
                                .build())
                        .build()
        );
    }

    private String streamKey(String knowledgeBaseId) {
        return STREAM_PREFIX + knowledgeBaseId;
    }
}
