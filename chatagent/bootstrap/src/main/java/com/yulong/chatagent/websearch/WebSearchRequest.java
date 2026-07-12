package com.yulong.chatagent.websearch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provider-neutral web search request.
 * <p>
 * Validation and normalization are performed by {@link #validate(String, Integer, String, String, int, int, int)}.
 * Construct via the static factory method to ensure safe defaults and input sanitization.
 */
public record WebSearchRequest(
        String query,
        int maxResults,
        Freshness freshness,
        List<String> domains,
        Instant requestedAt
) {

    /**
     * Provider-neutral freshness values mapped to Brave freshness codes.
     */
    public enum Freshness {
        ANY,
        DAY,
        WEEK,
        MONTH,
        YEAR
    }

    /**
     * Validate and normalize raw tool-call parameters into a safe {@link WebSearchRequest}.
     *
     * @param rawQuery             user/model-provided query string (required)
     * @param rawMaxResults        optional max results hint; clamped to [1..{@code hardMaxResults}]
     * @param rawFreshness         optional freshness string; defaults to ANY
     * @param rawDomains           optional comma-separated include-domain filter
     * @param hardMaxResults       backend-configured upper bound for max results
     * @param maxQueryChars        backend-configured query length limit
     * @param defaultMaxResults    backend-configured default when caller does not specify
     * @return validated request
     * @throws IllegalArgumentException if query is empty or exceeds length limit
     */
    public static WebSearchRequest validate(
            String rawQuery,
            Integer rawMaxResults,
            String rawFreshness,
            String rawDomains,
            int hardMaxResults,
            int maxQueryChars,
            int defaultMaxResults
    ) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        String query = rawQuery.trim();
        if (query.length() > maxQueryChars) {
            throw new IllegalArgumentException(
                    "query exceeds maximum length of " + maxQueryChars + " characters");
        }

        int effectiveHardMax = Math.max(hardMaxResults, 1);
        int effectiveDefault = Math.min(Math.max(defaultMaxResults, 1), effectiveHardMax);
        if (rawMaxResults != null && (rawMaxResults < 1 || rawMaxResults > effectiveHardMax)) {
            throw new IllegalArgumentException("maxResults must be between 1 and " + effectiveHardMax);
        }
        int maxResults = rawMaxResults != null ? rawMaxResults : effectiveDefault;

        Freshness freshness = parseFreshness(rawFreshness);

        List<String> domains = parseDomains(rawDomains);

        return new WebSearchRequest(query, maxResults, freshness, domains, Instant.now());
    }

    /**
     * Build the Brave freshness code. Returns {@code null} for ANY.
     */
    public String braveFreshness() {
        return switch (freshness) {
            case ANY -> null;
            case DAY -> "pd";
            case WEEK -> "pw";
            case MONTH -> "pm";
            case YEAR -> "py";
        };
    }

    public String providerQuery() {
        String suffix = domains.stream().map(domain -> "site:" + domain)
                .collect(Collectors.joining(" OR "));
        String value = suffix.isEmpty() ? query : query + " (" + suffix + ")";
        if (value.length() > 400 || value.trim().split("\\s+").length > 50) {
            throw new IllegalArgumentException("final provider query exceeds Brave limits");
        }
        return value;
    }

    private static Freshness parseFreshness(String raw) {
        if (raw == null || raw.isBlank()) {
            return Freshness.ANY;
        }
        return switch (raw.trim().toUpperCase()) {
            case "DAY" -> Freshness.DAY;
            case "WEEK" -> Freshness.WEEK;
            case "MONTH" -> Freshness.MONTH;
            case "YEAR" -> Freshness.YEAR;
            case "ANY" -> Freshness.ANY;
            default -> throw new IllegalArgumentException("unknown freshness value");
        };
    }

    /**
     * Parse and normalize comma-separated domain hostnames.
     * <p>
     * Each entry is stripped of whitespace, lowercased, and validated as a hostname
     * (letters, digits, hyphens, dots). Invalid entries are silently dropped.
     */
    static List<String> parseDomains(String rawDomains) {
        if (rawDomains == null || rawDomains.isBlank()) {
            return Collections.emptyList();
        }
        List<String> values = Arrays.stream(rawDomains.split(",", -1))
                .map(String::trim)
                .map(String::toLowerCase)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        if (values.size() > 5 || values.stream().anyMatch(v -> !isValidHostname(v))) {
            throw new IllegalArgumentException("domains must contain at most five valid public hostnames");
        }
        return values;
    }

    static boolean isValidHostname(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        if (host.matches("[0-9.]+") || host.contains(":") || !host.contains(".")) {
            return false;
        }
        // Reject trailing dot ("example.com.") — split would produce empty trailing label
        if (host.endsWith(".")) {
            return false;
        }
        // Reject host-level leading/trailing hyphens
        if (host.startsWith("-")) {
            return false;
        }
        for (String label : host.split("\\.")) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            // Each label must not start or end with a hyphen
            if (label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (char c : label.toCharArray()) {
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                    return false;
                }
            }
        }
        return true;
    }
}
