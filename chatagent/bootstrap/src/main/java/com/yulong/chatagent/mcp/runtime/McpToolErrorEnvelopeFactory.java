package com.yulong.chatagent.mcp.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces stable error payloads for MCP tool-call failures.
 */
@Component
public class McpToolErrorEnvelopeFactory {

    private final ObjectMapper objectMapper;

    public McpToolErrorEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String error(String errorCode, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "error");
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        return writeJson(payload);
    }

    public String ok(String serverSlug, String toolName, String content, boolean truncated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("server", serverSlug);
        payload.put("tool", toolName);
        payload.put("truncated", truncated);
        payload.put("content", content);
        return writeJson(payload);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize MCP tool envelope", ex);
        }
    }
}
