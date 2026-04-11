package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.McpServerDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mapper for {@code t_mcp_server}.
 */
@Mapper
public interface McpServerMapper {

    List<McpServerDTO> selectAll();

    McpServerDTO selectById(@Param("id") String id);

    McpServerDTO selectBySlug(@Param("slug") String slug);

    int insert(McpServerDTO server);

    int updateById(McpServerDTO server);

    int softDeleteById(@Param("id") String id,
                       @Param("deletedAt") LocalDateTime deletedAt,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
