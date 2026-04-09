package com.yulong.chatagent.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal remote tool descriptor returned from {@code tools/list}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpRemoteTool(
        String name,
        String title,
        String description,
        @JsonProperty("inputSchema") JsonNode inputSchema
) {
}
