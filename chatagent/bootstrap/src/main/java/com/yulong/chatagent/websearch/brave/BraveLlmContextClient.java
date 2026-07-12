package com.yulong.chatagent.websearch.brave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.CurrentToolDeadlineHolder;
import com.yulong.chatagent.websearch.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Sole production native-search adapter: Brave LLM Context. */
@Component
public class BraveLlmContextClient implements WebSearchClient {
    static final String PRODUCTION_ENDPOINT = "https://api.search.brave.com/res/v1/llm/context";

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public BraveLlmContextClient(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getResponseTimeoutMs());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        CurrentToolDeadlineHolder.remainingMillisOrDefault(properties.getResponseTimeoutMs());
        try {
            String body = client.post().uri(properties.getEffectiveBraveEndpoint())
                    .header("X-Subscription-Token", properties.getBraveApiKey())
                    .header("Accept", "application/json")
                    .body(buildRequest(request))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        int status = response.getStatusCode().value();
                        String code = status == 401 || status == 403 ? "AUTH_FAILURE"
                                : status == 429 ? "RATE_LIMITED"
                                : status >= 500 ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REJECTED";
                        throw new BraveSearchException(code);
                    })
                    .body(String.class);
            if (body == null || body.length() > properties.getMaxResponseBytes()) {
                return WebSearchResponse.failure("INVALID_RESPONSE");
            }
            return mapResponse(objectMapper.readTree(body), request);
        } catch (BraveSearchException e) {
            return WebSearchResponse.failure(e.code);
        } catch (RestClientException e) {
            return WebSearchResponse.failure("PROVIDER_UNAVAILABLE");
        } catch (Exception e) {
            return WebSearchResponse.failure("INVALID_RESPONSE");
        }
    }

    Map<String, Object> buildRequest(WebSearchRequest request) {
        String q = request.providerQuery();
        int contextTokens = Math.max(1024, properties.getMaxContextTokens());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", q);
        body.put("count", request.maxResults());
        body.put("maximum_number_of_urls", request.maxResults());
        body.put("maximum_number_of_tokens", contextTokens);
        body.put("maximum_number_of_snippets", Math.min(request.maxResults() * 3, 20));
        // Brave validates this field with a provider-side minimum of 512.
        body.put("maximum_number_of_tokens_per_url",
                Math.max(512, contextTokens / request.maxResults()));
        body.put("maximum_number_of_snippets_per_url", 3);
        body.put("context_threshold_mode", "balanced");
        body.put("enable_local", false);
        if (request.braveFreshness() != null) body.put("freshness", request.braveFreshness());
        if (hasText(properties.getBraveCountry())) body.put("country", properties.getBraveCountry());
        if (hasText(properties.getBraveSearchLang())) body.put("search_lang", properties.getBraveSearchLang());
        return body;
    }

    private WebSearchResponse mapResponse(JsonNode root, WebSearchRequest request) {
        List<WebSearchResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonNode grounding = root.path("grounding").path("generic");
        JsonNode sources = root.path("sources");
        if (grounding.isArray()) {
            for (JsonNode source : grounding) {
                String url = source.path("url").asText(null);
                if (!isAllowedPublicUrl(url, request.domains()) || !seen.add(normalizeUrl(url))) continue;
                JsonNode metadata = sources.path(url == null ? "" : url);
                String title = source.path("title").asText(metadata.path("title").asText("Source"));
                StringBuilder snippet = new StringBuilder();
                JsonNode snippets = source.path("snippets");
                if (snippets.isArray()) {
                    snippets.forEach(value -> {
                        if (!snippet.isEmpty()) snippet.append("\n");
                        snippet.append(value.isTextual() ? value.asText() : value.toString());
                    });
                }
                results.add(new WebSearchResult(title, url, snippet.toString(), null, "Brave", 0d));
                if (results.size() >= request.maxResults()) break;
            }
        }
        return WebSearchResponse.success(results);
    }

    static boolean isAllowedPublicUrl(String value, List<String> domains) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getHost() == null) return false;
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) return false;
            return domains.isEmpty() || domains.stream().anyMatch(d ->
                    uri.getHost().equalsIgnoreCase(d) || uri.getHost().toLowerCase().endsWith("." + d));
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeUrl(String url) { return URI.create(url).normalize().toString(); }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static final class BraveSearchException extends RuntimeException {
        private final String code;
        private BraveSearchException(String code) { this.code = code; }
    }
}
