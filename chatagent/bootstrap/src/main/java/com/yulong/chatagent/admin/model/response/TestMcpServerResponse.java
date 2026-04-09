package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.McpDiscoveredToolVO;
import com.yulong.chatagent.admin.model.vo.McpServerVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result payload for admin-side MCP connectivity tests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestMcpServerResponse {
    private boolean success;
    private String errorCode;
    private String errorMessage;
    private String negotiatedProtocolVersion;
    private String remoteServerName;
    private String remoteServerVersion;
    private int discoveredToolCount;
    private List<McpDiscoveredToolVO> discoveredTools;
    private LocalDateTime testedAt;
    private McpServerVO server;
}
