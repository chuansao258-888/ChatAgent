package com.yulong.chatagent.mcp.transport;

import lombok.Data;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Transport-layer timeouts and protocol preferences for outbound MCP calls.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.mcp.transport")
@Data
public class McpTransportProperties {

    private String clientName = "ChatAgent";
    private String clientVersion = "phase-1b";
    private String preferredHttpProtocolVersion = "2025-06-18";
    private String preferredSseProtocolVersion = "2024-11-05";
    private List<String> supportedProtocolVersions = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private int maxToolsListPages = 20;
    private long connectTimeoutMs = 2000L;
    private long responseTimeoutMs = 10000L;
    private long readTimeoutMs = 10000L;
    private long writeTimeoutMs = 10000L;
    private long requestTimeoutMs = 15000L;
    private int maxInMemorySizeBytes = 131072;
    private long sseConnectTimeoutMs = 5000L;
    private int sseMaxReconnects = 5;
    private long sseInitialBackoffMs = 1000L;
    private long sseMaxBackoffMs = 30000L;
}
