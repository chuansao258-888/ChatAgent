package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Reverse lookup queries for MCP tool references.
 */
@Mapper
public interface McpServerReferenceQueryMapper {

    List<McpToolReferenceDTO> selectAgentReferences(@Param("toolNames") List<String> toolNames);

    List<McpToolReferenceDTO> selectIntentNodeReferences(@Param("toolNames") List<String> toolNames);
}
