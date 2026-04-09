package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/**
 * Partial update payload for one MCP server configuration.
 */
@Data
public class UpdateMcpServerRequest {
    private String slug;
    private String name;
    private String description;
    private String protocol;
    private String authType;
    private String endpointUrl;
    private String credentials;
}
