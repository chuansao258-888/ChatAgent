package com.yulong.chatagent.mcp.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime gray-release controls for MCP tool exposure.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.mcp.rollout")
@Data
public class McpRolloutProperties {

    private String mode = "ALL";

    private List<String> allowedAgentIds = new ArrayList<>();
}
