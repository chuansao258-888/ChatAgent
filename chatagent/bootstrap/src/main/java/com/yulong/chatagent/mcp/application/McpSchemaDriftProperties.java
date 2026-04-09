package com.yulong.chatagent.mcp.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Background schema-drift detection settings.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.mcp.schema-drift")
@Data
public class McpSchemaDriftProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 600000L;
}
