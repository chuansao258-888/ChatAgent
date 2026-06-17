package com.yulong.chatagent.rag.ingestion.enrich;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the contextual chunk enricher, bound to
 * {@code chatagent.rag.ingestion.contextual-enricher}.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rag.ingestion.contextual-enricher")
@Data
public class ContextualChunkEnricherProperties {

    /** Whether contextual enrichment is enabled; disabled by default. */
    private boolean enabled = false;
    /** Model id used to generate contextual prefixes; applies when enabled. */
    private String modelId;
    /** Maximum chunks enriched per file, to bound enrichment cost. */
    private int maxChunksPerFile = 12;
    /** Skip chunks shorter than this many characters, since context adds little value to tiny chunks. */
    private int minChunkChars = 160;
    /** Maximum characters of generated context to prepend to each chunk. */
    private int maxContextChars = 600;
}
