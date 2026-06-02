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
     * Time-range filter supported by SearXNG.
     * <p>
     * WEEK is intentionally excluded: SearXNG {@code time_range} supports only
     * {@code day}, {@code month}, {@code year}. A future provider may extend this enum.
     */
    public enum Freshness {
        ANY,
        DAY,
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

        int effectiveDefault = Math.min(Math.max(defaultMaxResults, 1), hardMaxResults);
        int maxResults = rawMaxResults != null
                ? Math.min(Math.max(rawMaxResults, 1), hardMaxResults)
                : effectiveDefault;

        Freshness freshness = parseFreshness(rawFreshness);

        List<String> domains = parseDomains(rawDomains);

        return new WebSearchRequest(query, maxResults, freshness, domains, Instant.now());
    }

    /**
     * Build the SearXNG {@code time_range} query parameter value.
     * Returns {@code null} for ANY (no time restriction).
     */
    public String searxngTimeRange() {
        return freshness == Freshness.ANY ? null : freshness.name().toLowerCase();
    }

    private static Freshness parseFreshness(String raw) {
        if (raw == null || raw.isBlank()) {
            return Freshness.ANY;
        }
        return switch (raw.trim().toUpperCase()) {
            case "DAY" -> Freshness.DAY;
            case "MONTH" -> Freshness.MONTH;
            case "YEAR" -> Freshness.YEAR;
            default -> Freshness.ANY;
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
        return Arrays.stream(rawDomains.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .filter(WebSearchRequest::isValidHostname)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    static boolean isValidHostname(String host) {
        if (host.isEmpty() || host.length() > 253) {
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
