package com.yulong.chatagent.websearch.searxng;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.websearch.WebSearchProperties;
import com.yulong.chatagent.websearch.WebSearchRequest;
import com.yulong.chatagent.websearch.WebSearchResponse;
import com.yulong.chatagent.websearch.WebSearchResult;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * SearXNG search API client.
 * <p>
 * Calls {@code GET /search?q=...&format=json} on the configured SearXNG instance
 * and maps the JSON response to provider-neutral {@link WebSearchResponse}.
 * <p>
 * All provider-specific details are contained within this class; the tool layer
 * only sees {@link WebSearchResponse}.
 */
@Component
public class SearXNGWebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(SearXNGWebSearchClient.class);
    private static final String SEARCH_PATH = "/search";
    private static final int QUERY_PREVIEW_CHARS = 80;

    private final RestClient restClient;
    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;

    public SearXNGWebSearchClient(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = buildRestClient(properties);
    }

    /**
     * Execute a validated web search request against SearXNG.
     *
     * @param request validated and normalized request
     * @return provider-neutral response (success or safe error)
     */
    public WebSearchResponse search(WebSearchRequest request) {
        Instant started = Instant.now();
        log.info("SearXNG web search started: queryChars={}, maxResults={}, freshness={}, domainCount={}",
                request.query().length(),
                request.maxResults(),
                request.freshness(),
                request.domains().size());
        log.debug("SearXNG web search query preview: {}", queryPreview(request.query()));

        try {
            String queryWithDomains = decorateQueryWithDomains(request);
            URI uri = buildSearchUri(queryWithDomains, request);

            String json = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw new RestClientException(
                                "SearXNG returned HTTP " + resp.getStatusCode().value());
                    })
                    .body(String.class);

            if (json == null || json.isBlank()) {
                return WebSearchResponse.empty();
            }

            SearXNGSearchResponse searxng = objectMapper.readValue(json, SearXNGSearchResponse.class);
            WebSearchResponse response = mapResponse(searxng, request);
            log.info("SearXNG web search completed: queryChars={}, resultCount={}, durationMs={}",
                    request.query().length(),
                    response.results().size(),
                    elapsedMs(started));
            return response;
        } catch (RestClientException e) {
            ProviderError providerError = classifyProviderError(e);
            log.warn("SearXNG web search failed: queryChars={}, maxResults={}, freshness={}, domainCount={}, durationMs={}, errorCategory={}, exception={}",
                    request.query().length(),
                    request.maxResults(),
                    request.freshness(),
                    request.domains().size(),
                    elapsedMs(started),
                    providerError.category(),
                    e.getClass().getSimpleName());
            return WebSearchResponse.failure(providerError.safeMessage());
        } catch (JsonProcessingException e) {
            log.warn("SearXNG web search returned unreadable JSON: queryChars={}, durationMs={}, exception={}",
                    request.query().length(),
                    elapsedMs(started),
                    e.getClass().getSimpleName());
            return WebSearchResponse.failure("web search returned an unreadable response.");
        }
    }

    // ---- URI construction ----

    URI buildSearchUri(String query, WebSearchRequest request) {
        StringBuilder sb = new StringBuilder(properties.getSearxngBaseUrl());
        sb.append(SEARCH_PATH);
        sb.append("?q=").append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
        sb.append("&format=json");
        sb.append("&language=auto");
        sb.append("&categories=general");
        sb.append("&safesearch=").append(properties.getEffectiveSafeSearch());

        String timeRange = request.searxngTimeRange();
        if (timeRange != null) {
            sb.append("&time_range=").append(timeRange);
        }

        return URI.create(sb.toString());
    }

    // ---- Domain decoration ----

    /**
     * Prepend {@code site:domain1 OR site:domain2} to the query when domain
     * filters are present. This is a best-effort hint for upstream engines.
     */
    String decorateQueryWithDomains(WebSearchRequest request) {
        if (request.domains().isEmpty()) {
            return request.query();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < request.domains().size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append("site:").append(request.domains().get(i));
        }
        sb.append(" ").append(request.query());
        return sb.toString();
    }

    // ---- Response mapping ----

    private WebSearchResponse mapResponse(SearXNGSearchResponse searxng, WebSearchRequest request) {
        if (searxng.results() == null || searxng.results().isEmpty()) {
            return WebSearchResponse.empty();
        }

        // Map all provider results first
        List<WebSearchResult> all = searxng.results().stream()
                .map(this::mapResult)
                .toList();

        // Apply domain post-filter BEFORE limit, so results beyond the initial
        // maxResults window that match the domain filter are not lost.
        if (!request.domains().isEmpty()) {
            return applyDomainPostFilter(all, request);
        }

        // No domain filter — just limit to maxResults
        List<WebSearchResult> limited = all.stream()
                .limit(request.maxResults())
                .toList();
        return WebSearchResponse.success(limited);
    }

    private WebSearchResult mapResult(SearXNGSearchResult r) {
        Instant published = parsePublishedDate(r.publishedDate());
        return new WebSearchResult(
                r.title(),
                r.url(),
                r.content(),
                published,
                r.engine(),
                r.score() != null ? r.score() : 0.0
        );
    }

    private WebSearchResponse applyDomainPostFilter(List<WebSearchResult> results, WebSearchRequest request) {
        List<WebSearchResult> filtered = new ArrayList<>();
        for (WebSearchResult r : results) {
            Optional<String> host = r.host();
            if (host.isPresent() && matchesDomain(host.get(), request.domains())) {
                filtered.add(r);
            }
        }

        int beforeFilter = results.size();
        int afterFilter = filtered.size();

        if (filtered.isEmpty()) {
            // All results excluded — return a note instead of empty
            WebSearchResult note = new WebSearchResult(
                    "Note", null,
                    String.format("Domain filter excluded all %d results. No results matched the requested domains: %s.",
                            beforeFilter, String.join(", ", request.domains())),
                    null, null, 0.0
            );
            return WebSearchResponse.success(List.of(note));
        }

        // Limit after filtering
        List<WebSearchResult> limited = filtered.stream()
                .limit(request.maxResults())
                .toList();
        int afterLimit = limited.size();

        if (afterFilter < beforeFilter) {
            // Results were reduced by domain filter — include a note
            List<WebSearchResult> withNote = new ArrayList<>(limited);
            WebSearchResult note = new WebSearchResult(
                    "Note", null,
                    String.format("Domain filter reduced results from %d to %d. Some results were excluded because their domains did not match the requested filter.",
                            beforeFilter, afterFilter),
                    null, null, 0.0
            );
            withNote.add(note);
            return WebSearchResponse.success(withNote);
        }

        return WebSearchResponse.success(limited);
    }

    static boolean matchesDomain(String host, List<String> domains) {
        String lower = host.toLowerCase();
        for (String domain : domains) {
            if (lower.equals(domain) || lower.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    // ---- Error classification ----

    private ProviderError classifyProviderError(RestClientException e) {
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("connect timed out")) {
                return new ProviderError("timeout", "web search request timed out.");
            }
            if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many")) {
                return new ProviderError("rate_limit", "web search provider rate limit exceeded.");
            }
            if (lower.contains("connection refused") || lower.contains("connect error")) {
                return new ProviderError("not_reachable", "web search provider is not reachable.");
            }
        }
        return new ProviderError("unavailable", "web search is temporarily unavailable.");
    }

    // ---- Helpers ----

    private static long elapsedMs(Instant started) {
        return ChronoUnit.MILLIS.between(started, Instant.now());
    }

    private static String queryPreview(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String normalized = query.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= QUERY_PREVIEW_CHARS) {
            return normalized;
        }
        return normalized.substring(0, QUERY_PREVIEW_CHARS) + "...";
    }

    private static Instant parsePublishedDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(date);
        } catch (Exception e) {
            return null;
        }
    }

    private static RestClient buildRestClient(WebSearchProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getResponseTimeoutMs()));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    private record ProviderError(String category, String safeMessage) {
    }
}
