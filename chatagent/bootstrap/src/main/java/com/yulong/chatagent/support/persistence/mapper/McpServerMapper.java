package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.McpServer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mapper for {@code t_mcp_server}.
 */
@Mapper
public interface McpServerMapper {

    List<McpServer> selectAll();

    McpServer selectById(@Param("id") String id);

    McpServer selectBySlug(@Param("slug") String slug);

    int insert(McpServer server);

    int updateById(McpServer server);

    int softDeleteById(@Param("id") String id,
                       @Param("deletedAt") LocalDateTime deletedAt,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
