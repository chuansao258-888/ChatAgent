package com.yulong.chatagent.rag.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Explicit engine-routing settings so parser dispatch does not depend on Spring bean order.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rag.vdp.routing")
@Data
public class VdpEngineRoutingProperties {

    /**
     * Explicitly disable engines by engineId.
     */
    private Set<String> disabledEngines = new HashSet<>();

    /**
     * Whether knowledge ingestion should prefer document-level batch engines.
     */
    private boolean knowledgeBatchPreferred = true;

    /**
     * Preferred engineId for single-page image parsing.
     */
    private String preferredPageImageEngine;

    /**
     * Preferred engineId for full-document batch PDF parsing.
     */
    private String preferredBatchEngine;
}
