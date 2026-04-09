package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.McpServerVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * MCP server catalog for the admin UI.
 */
@Data
@AllArgsConstructor
public class ListMcpServersResponse {
    private List<McpServerVO> servers;
}
