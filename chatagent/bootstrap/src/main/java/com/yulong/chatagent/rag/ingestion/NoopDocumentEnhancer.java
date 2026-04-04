package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.BaseIngestionContext;
import org.springframework.stereotype.Component;

@Component
public class NoopDocumentEnhancer implements DocumentEnhancer {

    @Override
    public DocumentEnhancementResult enhance(BaseIngestionContext context) {
        return DocumentEnhancementResult.empty();
    }
}
