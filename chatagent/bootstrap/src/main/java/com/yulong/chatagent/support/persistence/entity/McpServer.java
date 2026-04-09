package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence entity for {@code t_mcp_server}.
 */
@Data
@Builder
public class McpServer {
    private String id;
    private String slug;
    private String name;
    private String description;
    private String protocol;
    private String authType;
    private String endpointUrl;
    private String encryptedCredentials;
    private String credentialKeyVersion;
    private String status;
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
