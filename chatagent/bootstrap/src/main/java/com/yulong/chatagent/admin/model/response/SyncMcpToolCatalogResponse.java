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
 * Result payload for one MCP catalog synchronization run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncMcpToolCatalogResponse {
    private boolean success;
    private String errorCode;
    private String errorMessage;
    private String negotiatedProtocolVersion;
    private String remoteServerName;
    private String remoteServerVersion;
    private int createdCount;
    private int updatedCount;
    private int staleCount;
    private int activeToolCount;
    private List<McpDiscoveredToolVO> activeTools;
    private LocalDateTime syncedAt;
    private McpServerVO server;
}
