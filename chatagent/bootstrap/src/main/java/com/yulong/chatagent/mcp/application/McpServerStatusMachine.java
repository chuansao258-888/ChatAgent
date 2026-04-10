package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.support.enums.McpServerStatus;
import org.springframework.stereotype.Component;

/**
 * Centralizes MCP server status transitions used by the admin facade.
 */
@Component
public class McpServerStatusMachine {

    public McpServerStatus initialStatus() {
        return McpServerStatus.DISABLED;
    }

    public McpServerStatus markSensitiveConfigChanged(McpServerStatus ignoredCurrentStatus) {
        return McpServerStatus.STALE;
    }

    public McpServerStatus activate() {
        return McpServerStatus.ACTIVE;
    }

    public McpServerStatus markConnectivityFailure(int consecutiveFailures) {
        return consecutiveFailures >= 3 ? McpServerStatus.FAILED : McpServerStatus.STALE;
    }

    public McpServerStatus disable() {
        return McpServerStatus.DISABLED;
    }
}
