package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/**
 * Unified payload for creating or updating one MCP server configuration.
 */
@Data
public class UpsertMcpServerRequest {
    private String slug;
    private String name;
    private String description;
    private String protocol;
    private String authType;
    private String endpointUrl;
    private String credentials;
}
