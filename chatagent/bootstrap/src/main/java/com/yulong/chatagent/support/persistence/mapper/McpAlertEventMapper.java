package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis mapper for persisted MCP alert events.
 */
@Mapper
public interface McpAlertEventMapper {

    int insert(McpAlertEventDTO alertEvent);

    int updateById(McpAlertEventDTO alertEvent);

    McpAlertEventDTO selectOpenByServerAndType(@Param("serverId") String serverId,
                                               @Param("alertType") String alertType);

    List<McpAlertEventDTO> selectRecentOpen(@Param("limit") int limit);

    Long countOpen();

    int resolveOpenByServerAndType(@Param("serverId") String serverId,
                                   @Param("alertType") String alertType,
                                   @Param("resolvedAt") LocalDateTime resolvedAt,
                                   @Param("updatedAt") LocalDateTime updatedAt);
}
