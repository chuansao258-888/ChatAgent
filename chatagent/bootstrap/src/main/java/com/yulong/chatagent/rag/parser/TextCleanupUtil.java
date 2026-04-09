package com.yulong.chatagent.rag.parser;

/**
 * Shared cleanup helpers for parser output normalization.
 */
public final class TextCleanupUtil {

    private TextCleanupUtil() {
    }

    /**
     * Applies the default cleanup policy used by document parsers.
     */
    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return stripNullCharacters(text)
                // Remove BOM markers emitted by some office/PDF parsers.
                .replace("\uFEFF", "")
                // Remove trailing spaces before newline boundaries.
                .replaceAll("[ \\t]+\\n", "\n")
                // Keep at most one blank paragraph separator.
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * Applies a configurable cleanup policy for callers that need finer control.
     */
    public static String cleanup(String text,
                                 boolean removeBOM,
                                 boolean trimTrailingSpaces,
                                 boolean compressEmptyLines,
                                 int maxConsecutiveLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = stripNullCharacters(text);

        if (removeBOM) {
            result = result.replace("\uFEFF", "");
        }

        if (trimTrailingSpaces) {
            result = result.replaceAll("[ \\t]+\\n", "\n");
        }

        if (compressEmptyLines && maxConsecutiveLines > 0) {
            String pattern = "\\n{" + (maxConsecutiveLines + 1) + ",}";
            String replacement = "\n".repeat(maxConsecutiveLines);
            result = result.replaceAll(pattern, replacement);
        }

        return result.trim();
    }

    /**
     * PostgreSQL text/jsonb cannot store NUL characters; strip them defensively once parser
     * output crosses the JVM/String boundary.
     */
    public static String stripNullCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.indexOf('\u0000') < 0 ? text : text.replace("\u0000", "");
    }
}
