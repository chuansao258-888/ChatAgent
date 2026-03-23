package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.FileIngestionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NoopDocumentEnhancer implements DocumentEnhancer {

    @Override
    public String enhance(FileIngestionContext context, String rawText) {
        if (StringUtils.hasText(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return rawText;
    }
}
