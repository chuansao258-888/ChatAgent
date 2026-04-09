package com.yulong.chatagent.admin.model.vo;

import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Safe MCP server view for admin APIs.
 */
@Data
@Builder
public class McpServerVO {
    private String id;
    private String slug;
    private String name;
    private String description;
    private McpProtocol protocol;
    private McpAuthType authType;
    private String endpointUrl;
    private McpServerStatus status;
    private Integer consecutiveFailures;
    private LocalDateTime lastTestedAt;
    private LocalDateTime lastInitializedAt;
    private LocalDateTime lastSyncAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Integer unresolvedReferenceCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
