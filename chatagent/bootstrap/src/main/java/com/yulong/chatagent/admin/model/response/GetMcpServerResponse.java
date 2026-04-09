package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.McpServerVO;
import com.yulong.chatagent.admin.model.vo.McpToolCatalogVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Detailed MCP server response for the admin UI.
 */
@Data
@AllArgsConstructor
public class GetMcpServerResponse {
    private McpServerVO server;
    private List<McpToolCatalogVO> catalogTools;
}
