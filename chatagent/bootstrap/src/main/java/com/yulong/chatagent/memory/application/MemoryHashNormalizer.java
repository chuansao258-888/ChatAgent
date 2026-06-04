package com.yulong.chatagent.memory.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Normalizes memory content and produces a deterministic content hash for exact deduplication.
 *
 * <p>Normalization rules:
 * <ol>
 *     <li>Trim leading/trailing whitespace.</li>
 *     <li>Collapse all internal whitespace sequences to a single space.</li>
 *     <li>Lowercase ASCII letters (A-Z → a-z).</li>
 * </ol>
 *
 * <p>Hash: SHA-256 of {@code userId + "\n" + type + "\n" + normalizedContent}.
 */
public final class MemoryHashNormalizer {

    private MemoryHashNormalizer() {
    }

    /**
     * Normalizes content for hash comparison: trim, collapse whitespace, lowercase English letters.
     */
    public static String normalize(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        // Collapse all whitespace (spaces, tabs, newlines) to a single space, then trim.
        String collapsed = content.replaceAll("\\s+", " ").trim();
        // Lowercase only ASCII letters.
        StringBuilder sb = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Produces a SHA-256 hash of {@code userId + "\n" + type + "\n" + normalizedContent}.
     *
     * @return hex-encoded SHA-256 hash (64 lowercase characters)
     */
    public static String hash(String userId, String type, String normalizedContent) {
        String input = userId + "\n" + type + "\n" + normalizedContent;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist on every JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
