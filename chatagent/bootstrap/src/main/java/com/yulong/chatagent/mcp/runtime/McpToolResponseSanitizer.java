package com.yulong.chatagent.mcp.runtime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * Normalizes and bounds remote MCP tool responses before they are returned to the LLM.
 */
@Component
public class McpToolResponseSanitizer {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    public SanitizedToolResponse sanitize(String rawPayload) {
        String normalized = StringUtils.hasText(rawPayload) ? rawPayload.trim() : "";
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_RESPONSE_BYTES) {
            return new SanitizedToolResponse(wrap(normalized), false);
        }

        String truncated = new String(bytes, 0, MAX_RESPONSE_BYTES, StandardCharsets.UTF_8);
        return new SanitizedToolResponse(wrap(truncated), true);
    }

    private String wrap(String payload) {
        return "[TOOL_RESPONSE_START]\n" + payload + "\n[TOOL_RESPONSE_END]";
    }

    public record SanitizedToolResponse(String content, boolean truncated) {
    }
}
