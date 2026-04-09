package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.port.McpServerReferenceQueryRepository;
import com.yulong.chatagent.support.dto.McpToolReferenceDTO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Aggregates delete-time MCP reference checks.
 */
@Component
public class McpServerReferenceInspector {

    private final McpServerReferenceQueryRepository referenceQueryRepository;

    public McpServerReferenceInspector(McpServerReferenceQueryRepository referenceQueryRepository) {
        this.referenceQueryRepository = referenceQueryRepository;
    }

    public List<McpToolReferenceDTO> inspect(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        return referenceQueryRepository.findActiveReferencesByToolNames(toolNames);
    }
}
