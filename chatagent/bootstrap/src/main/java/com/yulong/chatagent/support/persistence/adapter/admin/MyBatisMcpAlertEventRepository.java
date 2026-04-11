package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.admin.port.McpAlertEventRepository;
import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.enums.McpAlertType;
import com.yulong.chatagent.support.persistence.mapper.McpAlertEventMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-backed MCP alert event repository.
 */
@Repository
public class MyBatisMcpAlertEventRepository implements McpAlertEventRepository {

    private final McpAlertEventMapper mapper;

    public MyBatisMcpAlertEventRepository(McpAlertEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean save(McpAlertEventDTO alertEvent) {
        return mapper.insert(alertEvent) > 0;
    }

    @Override
    public boolean update(McpAlertEventDTO alertEvent) {
        return mapper.updateById(alertEvent) > 0;
    }

    @Override
    public McpAlertEventDTO findOpenByServerAndType(String serverId, McpAlertType alertType) {
        return mapper.selectOpenByServerAndType(serverId, alertType == null ? null : alertType.name());
    }

    @Override
    public List<McpAlertEventDTO> findRecentOpen(int limit) {
        return mapper.selectRecentOpen(limit);
    }

    @Override
    public long countOpen() {
        Long count = mapper.countOpen();
        return count == null ? 0L : count;
    }

    @Override
    public int resolveOpenByServerAndType(String serverId, McpAlertType alertType, LocalDateTime resolvedAt, LocalDateTime updatedAt) {
        return mapper.resolveOpenByServerAndType(serverId, alertType == null ? null : alertType.name(), resolvedAt, updatedAt);
    }
}
