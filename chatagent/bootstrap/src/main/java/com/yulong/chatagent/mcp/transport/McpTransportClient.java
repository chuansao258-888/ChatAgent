package com.yulong.chatagent.mcp.transport;

import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpToolCallResult;
import com.yulong.chatagent.support.dto.McpServerDTO;

/**
 * Admin-side transport abstraction for initialize + tools/list discovery.
 */
public interface McpTransportClient {

    McpDiscoveryResult discover(McpServerDTO server);

    McpToolCallResult callTool(McpServerDTO server, String remoteToolName, String jsonArguments);
}
