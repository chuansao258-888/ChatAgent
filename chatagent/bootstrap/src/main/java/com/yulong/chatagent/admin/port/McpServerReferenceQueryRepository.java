package com.yulong.chatagent.admin.port;

import com.yulong.chatagent.support.dto.McpToolReferenceDTO;

import java.util.List;

/**
 * Reverse lookups used before deleting MCP tool definitions.
 */
public interface McpServerReferenceQueryRepository {

    List<McpToolReferenceDTO> findActiveReferencesByToolNames(List<String> toolNames);
}
