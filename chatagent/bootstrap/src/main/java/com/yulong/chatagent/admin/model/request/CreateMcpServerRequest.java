package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/**
 * Request payload for creating one MCP server configuration.
 */
@Data
public class CreateMcpServerRequest {
    private String slug;
    private String name;
    private String description;
    private String protocol;
    private String authType;
    private String endpointUrl;
    private String credentials;
}
