package com.yulong.chatagent.mcp.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of one initialize + tools/list discovery run.
 */
public record McpDiscoveryResult(
        String negotiatedProtocolVersion,
        String remoteServerName,
        String remoteServerVersion,
        LocalDateTime initializedAt,
        List<McpRemoteToolDescriptor> tools
) {
}
