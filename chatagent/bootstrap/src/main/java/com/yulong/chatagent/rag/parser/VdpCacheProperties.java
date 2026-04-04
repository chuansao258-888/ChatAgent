package com.yulong.chatagent.rag.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cache settings for visual parsing deduplication.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rag.vdp.cache")
@Data
public class VdpCacheProperties {

    private boolean enabled = true;
    private long sessionTtlMinutes = 30L;
    private long sessionMaxSize = 512L;
    private long knowledgeTtlMinutes = 1440L;
}
