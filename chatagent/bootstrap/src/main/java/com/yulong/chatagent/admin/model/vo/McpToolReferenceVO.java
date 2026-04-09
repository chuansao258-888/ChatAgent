package com.yulong.chatagent.admin.model.vo;

import com.yulong.chatagent.support.enums.McpReferenceType;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One unresolved MCP tool reference exposed to the admin UI.
 */
@Data
@AllArgsConstructor
public class McpToolReferenceVO {
    private McpReferenceType referenceType;
    private String referenceId;
    private String referenceName;
    private String referencePath;
}
