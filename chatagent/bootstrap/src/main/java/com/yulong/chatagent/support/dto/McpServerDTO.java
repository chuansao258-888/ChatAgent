package com.yulong.chatagent.support.dto;

import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Server-side MCP endpoint configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerDTO {
    private String id;
    private String slug;
    private String name;
    private String description;
    private McpProtocol protocol;
    private McpAuthType authType;
    private String endpointUrl;
    private String encryptedCredentials;
    private String credentialKeyVersion;
    private McpServerStatus status;
    private Integer consecutiveFailures;
    private LocalDateTime lastTestedAt;
    private LocalDateTime lastInitializedAt;
    private LocalDateTime lastSyncAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
