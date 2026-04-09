package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.McpToolReferenceVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Result of a soft-delete attempt for one MCP server.
 */
@Data
@AllArgsConstructor
public class DeleteMcpServerResponse {
    private boolean deleted;
    private boolean softDeleted;
    private int activeReferenceCount;
    private int unresolvedReferenceCount;
    private List<McpToolReferenceVO> references;
}
