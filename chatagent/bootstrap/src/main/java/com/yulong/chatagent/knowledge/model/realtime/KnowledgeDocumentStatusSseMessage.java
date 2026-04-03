package com.yulong.chatagent.knowledge.model.realtime;

import com.yulong.chatagent.knowledge.model.vo.KnowledgeDocumentVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Realtime knowledge-document status update sent to the admin detail page.
 */
@Data
@AllArgsConstructor
@Builder
public class KnowledgeDocumentStatusSseMessage {

    private Type type;
    private Payload payload;

    @Data
    @AllArgsConstructor
    @Builder
    public static class Payload {
        private KnowledgeDocumentVO document;
    }

    public enum Type {
        DOCUMENT_STATUS_UPDATED
    }
}
