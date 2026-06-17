package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.admin.port.McpServerReferenceQueryRepository;
import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import com.yulong.chatagent.support.persistence.mapper.McpServerReferenceQueryMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MyBatis-backed delete-time reverse lookup for MCP tool references.
 */
@Repository
public class MyBatisMcpServerReferenceQueryRepository implements McpServerReferenceQueryRepository {

    private final McpServerReferenceQueryMapper mapper;

    public MyBatisMcpServerReferenceQueryRepository(McpServerReferenceQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<McpToolReferenceDTO> findActiveReferencesByToolNames(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        List<McpToolReferenceDTO> result = new ArrayList<>();
        result.addAll(mapper.selectAgentReferences(toolNames));
        result.addAll(mapper.selectIntentNodeReferences(toolNames));
        result.sort(Comparator.comparing(McpToolReferenceDTO::getReferenceType)
                .thenComparing(McpToolReferenceDTO::getReferenceName));
        return result;
    }
}
