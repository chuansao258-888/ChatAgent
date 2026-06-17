package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Outcome of deleting an MCP server, including any tool references that blocked hard deletion. */
@Data
@AllArgsConstructor
public class DeleteMcpServerResponse {
    private boolean deleted;
    private boolean softDeleted;
    private int activeReferenceCount;
    private int unresolvedReferenceCount;
    private List<McpToolReferenceDTO> references;
}
