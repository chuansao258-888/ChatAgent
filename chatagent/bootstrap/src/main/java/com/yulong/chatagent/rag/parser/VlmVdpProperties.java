package com.yulong.chatagent.rag.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime settings for the lightweight VLM-backed visual parser used in Phase 5a.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.rag.vdp.vlm")
@Data
public class VlmVdpProperties {

    private boolean enabled = true;
    private String clientId = "glm-4.6";
    private String modelId = "glm-4v-flash";
    private String promptVersion = "v1";
    private int maxTokens = 1200;
    private double temperature = 0.1d;
    private long timeoutMs = 5000L;
    private int corePoolSize = 1;
    private int maxPoolSize = 2;
    private int queueCapacity = 0;
    private String failurePlaceholder = "[图像解析失败]";
}
