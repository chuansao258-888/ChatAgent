package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.persistence.mapper.McpServerMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-backed MCP server repository.
 */
@Repository
public class MyBatisMcpServerRepository implements McpServerRepository {

    private final McpServerMapper mapper;

    public MyBatisMcpServerRepository(McpServerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<McpServerDTO> findAll() {
        return mapper.selectAll();
    }

    @Override
    public McpServerDTO findById(String id) {
        return mapper.selectById(id);
    }

    @Override
    public McpServerDTO findBySlug(String slug) {
        return mapper.selectBySlug(slug);
    }

    @Override
    public boolean save(McpServerDTO server) {
        return mapper.insert(server) > 0;
    }

    @Override
    public boolean update(McpServerDTO server) {
        return mapper.updateById(server) > 0;
    }

    @Override
    public boolean softDelete(String id, LocalDateTime deletedAt, LocalDateTime updatedAt) {
        return mapper.softDeleteById(id, deletedAt, updatedAt) > 0;
    }
}
