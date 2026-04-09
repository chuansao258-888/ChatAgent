package com.yulong.chatagent.support.dto;

import com.yulong.chatagent.support.enums.McpReferenceType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One configuration reference that still points at an MCP tool name.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpToolReferenceDTO {
    private McpReferenceType referenceType;
    private String referenceId;
    private String referenceName;
    private String referencePath;
}
