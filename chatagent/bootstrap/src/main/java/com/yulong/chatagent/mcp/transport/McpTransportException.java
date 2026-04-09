package com.yulong.chatagent.mcp.transport;

/**
 * Transport/protocol failure surfaced to admin MCP orchestration.
 */
public class McpTransportException extends RuntimeException {

    private final String errorCode;

    public McpTransportException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public McpTransportException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
