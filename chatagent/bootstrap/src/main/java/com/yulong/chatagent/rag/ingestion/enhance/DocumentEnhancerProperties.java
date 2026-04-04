package com.yulong.chatagent.rag.ingestion.enhance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Properties for optional document-level enhancement on knowledge-base ingestion.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rag.ingestion.document-enhancer")
@Data
public class DocumentEnhancerProperties {

    private boolean enabled = false;
    private String modelId;
    private String promptVersion = "v1";
    private int shortDocCharLimit = 12_000;
    private int mapWindowMaxChars = 6_000;
    private int maxKeywords = 10;
    private int maxQuestions = 5;
    private int maxKeywordChars = 80;
    private int maxQuestionChars = 180;
    private double minEnhancedLengthRatio = 0.5d;
    private double maxEnhancedLengthRatio = 2.0d;
}
