package com.yulong.chatagent.rag.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime settings for the MinerU batch PDF engine.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rag.vdp.mineru")
@Data
public class MinerUProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8765";
    private String bearerToken;
    private String version = "v1";
    private String backend = "pipeline";
    private String parseMethod = "auto";
    private boolean tableEnable = true;
    private int maxPdfSizeMb = 50;
    private long pollIntervalMs = 2000L;
    private int maxPollAttempts = 150;
    private long submitTimeoutMs = 30000L;
    private long pollTimeoutMs = 5000L;
}
