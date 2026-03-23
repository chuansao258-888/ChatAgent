package com.yulong.chatagent.rag.ingestion.enrich;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chatagent.rag.ingestion.contextual-enricher")
@Data
public class ContextualChunkEnricherProperties {

    private boolean enabled = false;
    private String modelId;
    private int maxDocumentChars = 12_000;
    private int maxChunksPerFile = 12;
    private int minChunkChars = 160;
    private int maxContextChars = 600;
}
