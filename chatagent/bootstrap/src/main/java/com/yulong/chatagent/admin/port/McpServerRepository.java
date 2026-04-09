package com.yulong.chatagent.admin.port;

import com.yulong.chatagent.support.dto.McpServerDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence port for configured MCP servers.
 */
public interface McpServerRepository {

    List<McpServerDTO> findAll();

    McpServerDTO findById(String id);

    McpServerDTO findBySlug(String slug);

    boolean save(McpServerDTO server);

    boolean update(McpServerDTO server);

    boolean softDelete(String id, LocalDateTime deletedAt, LocalDateTime updatedAt);
}
