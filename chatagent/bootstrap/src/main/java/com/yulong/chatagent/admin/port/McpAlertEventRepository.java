package com.yulong.chatagent.admin.port;

import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.enums.McpAlertType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence port for admin-visible MCP alert events.
 */
public interface McpAlertEventRepository {

    boolean save(McpAlertEventDTO alertEvent);

    boolean update(McpAlertEventDTO alertEvent);

    McpAlertEventDTO findOpenByServerAndType(String serverId, McpAlertType alertType);

    List<McpAlertEventDTO> findRecentOpen(int limit);

    long countOpen();

    int resolveOpenByServerAndType(String serverId, McpAlertType alertType, LocalDateTime resolvedAt, LocalDateTime updatedAt);
}
