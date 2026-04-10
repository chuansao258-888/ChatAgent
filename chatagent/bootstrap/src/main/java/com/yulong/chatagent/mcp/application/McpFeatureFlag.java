package com.yulong.chatagent.mcp.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Global kill switch for MCP discovery and outbound execution.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.mcp")
@Data
public class McpFeatureFlag {

    private boolean enabled = true;
}
