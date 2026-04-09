package com.yulong.chatagent.admin.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Produces model-safe MCP function names.
 */
@Component
public class McpToolNameNormalizer {

    private static final int MAX_LENGTH = 64;
    private static final int HASH_LENGTH = 4;

    public String normalizeToolName(String slug, String originalToolName) {
        String normalizedSlug = normalizeSegment(slug, "server");
        String normalizedTool = normalizeSegment(originalToolName, "tool");
        String prefix = "mcp_" + normalizedSlug + "_";
        String candidate = prefix + normalizedTool;
        if (candidate.length() <= MAX_LENGTH) {
            return candidate;
        }

        String hash = shortHash(originalToolName);
        int remaining = MAX_LENGTH - prefix.length() - HASH_LENGTH - 1;
        if (remaining < 1) {
            remaining = 1;
        }
        String truncated = normalizedTool.substring(0, Math.min(normalizedTool.length(), remaining));
        return prefix + truncated + "_" + hash;
    }

    private String normalizeSegment(String input, String fallback) {
        String raw = StringUtils.hasText(input) ? input.trim() : fallback;
        String normalized = raw.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+", "").replaceAll("_+$", "");
        if (!StringUtils.hasText(normalized)) {
            return fallback;
        }
        return normalized;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available", ex);
        }
    }
}
