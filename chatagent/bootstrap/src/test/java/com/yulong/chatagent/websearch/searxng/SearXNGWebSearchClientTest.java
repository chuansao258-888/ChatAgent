package com.yulong.chatagent.websearch.searxng;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.websearch.WebSearchProperties;
import com.yulong.chatagent.websearch.WebSearchRequest;
import com.yulong.chatagent.websearch.WebSearchResponse;
import com.yulong.chatagent.websearch.WebSearchResult;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class SearXNGWebSearchClientTest {

    private static final int HARD_MAX = 8;
    private static final int MAX_QUERY_CHARS = 300;
    private static final int DEFAULT_MAX = 5;

    private WebSearchProperties properties;
    private ObjectMapper objectMapper;
    private MockRestServiceServer mockServer;
    private SearXNGWebSearchClient client;

    @BeforeEach
    void setUp() {
        properties = new WebSearchProperties();
        properties.setEnabled(true);
        properties.setSearxngBaseUrl("http://localhost:8888");

        objectMapper = new ObjectMapper();

        // Build RestClient through a shared builder so MockRestServiceServer can intercept
        RestClient.Builder restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        client = new SearXNGWebSearchClient(properties, objectMapper);
        injectRestClient(client, restClientBuilder.build());
    }

    private void injectRestClient(SearXNGWebSearchClient target, RestClient rc) {
        try {
            var field = SearXNGWebSearchClient.class.getDeclaredField("restClient");
            field.setAccessible(true);
            field.set(target, rc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WebSearchRequest request(String query) {
        return WebSearchRequest.validate(query, null, null, null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
    }

    private WebSearchRequest request(String query, String domains) {
        return WebSearchRequest.validate(query, null, null, domains, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
    }

    private String searxngJson(List<SearXNGSearchResult> results) {
        try {
            return objectMapper.writeValueAsString(new SearXNGSearchResponse(results, null, results.size()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SearXNGSearchResult searxngResult(String title, String url, String snippet, String engine, double score) {
        return new SearXNGSearchResult(title, url, snippet, engine, score, null);
    }

    @Test
    void providerFailureLogsShouldNotExposeFullQuery(CapturedOutput output) {
        String sensitiveQuery = "password reset token abcdef1234567890 internal private user data";
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                .andRespond(withException(new java.net.ConnectException("Connection refused")));

        WebSearchResponse response = client.search(request(sensitiveQuery));

        assertThat(response.success()).isFalse();
        assertThat(output.getAll())
                .contains("SearXNG web search failed")
                .contains("queryChars=")
                .contains("errorCategory=not_reachable")
                .doesNotContain(sensitiveQuery)
                .doesNotContain("abcdef1234567890");
    }

    @Nested
    class SuccessfulSearch {
        @Test
        void shouldMapSuccessfulResponseToDomainResults(CapturedOutput output) {
            var r1 = searxngResult("Spring Boot 3.4", "https://spring.io/blog/spring-boot-3.4", "Spring Boot 3.4 release notes", "google", 0.95);
            var r2 = searxngResult("Spring Framework", "https://spring.io/projects/spring-framework", "Spring Framework overview", "bing", 0.8);

            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withSuccess(searxngJson(List.of(r1, r2)), org.springframework.http.MediaType.APPLICATION_JSON));

            String query = "spring boot latest private token abcdef1234567890";
            WebSearchResponse response = client.search(request(query));

            assertThat(response.success()).isTrue();
            assertThat(response.results()).hasSize(2);
            assertThat(response.results().get(0).title()).isEqualTo("Spring Boot 3.4");
            assertThat(response.results().get(0).url()).isEqualTo("https://spring.io/blog/spring-boot-3.4");
            assertThat(response.results().get(0).snippet()).isEqualTo("Spring Boot 3.4 release notes");
            assertThat(response.results().get(0).engine()).isEqualTo("google");
            assertThat(response.results().get(0).score()).isEqualTo(0.95);
            assertThat(response.results().get(1).engine()).isEqualTo("bing");
            assertThat(output.getAll())
                    .contains("SearXNG web search started")
                    .contains("SearXNG web search completed")
                    .contains("queryChars=")
                    .contains("resultCount=2")
                    .doesNotContain(query)
                    .doesNotContain("abcdef1234567890");
        }

        @Test
        void shouldReturnEmptyForEmptyResults() {
            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withSuccess(searxngJson(List.of()), org.springframework.http.MediaType.APPLICATION_JSON));

            WebSearchResponse response = client.search(request("obscure query xyz"));

            assertThat(response.success()).isTrue();
            assertThat(response.results()).isEmpty();
        }

        @Test
        void shouldReturnEmptyForNullResults() {
            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withSuccess("{\"results\":null}", org.springframework.http.MediaType.APPLICATION_JSON));

            WebSearchResponse response = client.search(request("test"));

            assertThat(response.success()).isTrue();
            assertThat(response.results()).isEmpty();
        }
    }

    @Nested
    class DomainFiltering {
        @Test
        void shouldPostFilterByDomain() {
            var r1 = searxngResult("Spring", "https://spring.io/guides", "Guide", "google", 0.9);
            var r2 = searxngResult("Other", "https://example.com/page", "Page", "google", 0.8);

            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("site%3Aspring.io")))
                    .andRespond(withSuccess(searxngJson(List.of(r1, r2)), org.springframework.http.MediaType.APPLICATION_JSON));

            WebSearchResponse response = client.search(request("spring guides", "spring.io"));

            assertThat(response.success()).isTrue();
            // r1 matches, r2 doesn't; a note is appended about reduced results
            assertThat(response.results()).hasSize(2); // 1 result + 1 note
            assertThat(response.results().get(0).title()).isEqualTo("Spring");
            assertThat(response.results().get(1).title()).isEqualTo("Note");
            assertThat(response.results().get(1).snippet()).contains("reduced results from 2 to 1");
        }

        @Test
        void shouldReturnAllResultsWhenAllMatchDomain() {
            var r1 = searxngResult("A", "https://docs.spring.io/a", "A", "google", 0.9);
            var r2 = searxngResult("B", "https://spring.io/b", "B", "google", 0.8);

            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("site%3Aspring.io")))
                    .andRespond(withSuccess(searxngJson(List.of(r1, r2)), org.springframework.http.MediaType.APPLICATION_JSON));

            WebSearchResponse response = client.search(request("spring", "spring.io"));

            assertThat(response.success()).isTrue();
            assertThat(response.results()).hasSize(2);
            assertThat(response.results().stream().noneMatch(r -> "Note".equals(r.title()))).isTrue();
        }

        @Test
        void shouldReturnNoteWhenNoResultsMatchDomain() {
            var r1 = searxngResult("Other", "https://example.com/page", "Page", "google", 0.8);

            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("site%3Aspring.io")))
                    .andRespond(withSuccess(searxngJson(List.of(r1)), org.springframework.http.MediaType.APPLICATION_JSON));

            WebSearchResponse response = client.search(request("test", "spring.io"));

            assertThat(response.success()).isTrue();
            assertThat(response.results()).hasSize(1);
            assertThat(response.results().get(0).title()).isEqualTo("Note");
            assertThat(response.results().get(0).snippet()).contains("excluded all 1 results");
        }

        @Test
        void shouldFindDomainMatchBeyondInitialMaxResults() {
            // SearXNG returns 5 results where first 4 don't match, 5th does.
            // With default maxResults=5, the matching result would be lost if
            // limit was applied before domain filter.
            var r1 = searxngResult("Other1", "https://other1.com/a", "A", "google", 0.9);
            var r2 = searxngResult("Other2", "https://other2.com/b", "B", "google", 0.8);
            var r3 = searxngResult("Other3", "https://other3.com/c", "C", "google", 0.7);
            var r4 = searxngResult("Other4", "https://other4.com/d", "D", "google", 0.6);
            var r5 = searxngResult("Spring", "https://spring.io/guides", "Guide", "google", 0.5);

            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("site%3Aspring.io")))
                    .andRespond(withSuccess(searxngJson(List.of(r1, r2, r3, r4, r5)), org.springframework.http.MediaType.APPLICATION_JSON));

            WebSearchResponse response = client.search(request("spring guides", "spring.io"));

            assertThat(response.success()).isTrue();
            // Should find the spring.io match even though it's the 5th result
            assertThat(response.results().stream().anyMatch(r -> "Spring".equals(r.title()))).isTrue();
        }
    }

    @Nested
    class DomainMatching {
        @Test
        void shouldMatchExactHost() {
            assertThat(SearXNGWebSearchClient.matchesDomain("example.com", List.of("example.com"))).isTrue();
        }

        @Test
        void shouldMatchSubdomain() {
            assertThat(SearXNGWebSearchClient.matchesDomain("docs.example.com", List.of("example.com"))).isTrue();
        }

        @Test
        void shouldNotMatchUnrelatedDomain() {
            assertThat(SearXNGWebSearchClient.matchesDomain("other.com", List.of("example.com"))).isFalse();
        }

        @Test
        void shouldMatchCaseInsensitive() {
            assertThat(SearXNGWebSearchClient.matchesDomain("Docs.Example.COM", List.of("example.com"))).isTrue();
        }
    }

    @Nested
    class QueryDecoration {
        @Test
        void shouldPrependSiteFilterToQuery() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, null, "spring.io,oracle.com", HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            String decorated = client.decorateQueryWithDomains(req);

            assertThat(decorated).startsWith("site:spring.io OR site:oracle.com ");
            assertThat(decorated).endsWith(" test");
        }

        @Test
        void shouldNotModifyQueryWithoutDomains() {
            WebSearchRequest req = request("test");
            assertThat(client.decorateQueryWithDomains(req)).isEqualTo("test");
        }
    }

    @Nested
    class ProviderFailures {
        @Test
        void shouldReturnSafeErrorOnTimeout() {
            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

            WebSearchResponse response = client.search(request("test"));

            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("timed out");
        }

        @Test
        void shouldReturnSafeErrorOnConnectionRefused() {
            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withException(new java.net.ConnectException("Connection refused")));

            WebSearchResponse response = client.search(request("test"));

            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("not reachable");
        }

        @Test
        void shouldReturnSafeErrorOnMalformedJson() {
            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withSuccess("not json at all", org.springframework.http.MediaType.APPLICATION_JSON));

            WebSearchResponse response = client.search(request("test"));

            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).contains("unreadable");
        }

        @Test
        void shouldReturnRateLimitErrorOnHttp429() {
            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

            WebSearchResponse response = client.search(request("test"));

            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).containsIgnoringCase("rate limit");
        }

        @Test
        void shouldReturnGenericUnavailableOnHttp500() {
            mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/search?")))
                    .andRespond(withServerError());

            WebSearchResponse response = client.search(request("test"));

            assertThat(response.success()).isFalse();
            assertThat(response.errorMessage()).containsIgnoringCase("unavailable");
        }
        @Test
        void shouldIncludeSafesearchAndFormat() {
            WebSearchRequest req = request("test");
            URI uri = client.buildSearchUri("test", req);

            String uriStr = uri.toString();
            assertThat(uriStr).contains("format=json");
            assertThat(uriStr).contains("safesearch=1");
            assertThat(uriStr).contains("categories=general");
            assertThat(uriStr).contains("language=auto");
        }

        @Test
        void shouldIncludeTimeRangeWhenNotAny() {
            WebSearchRequest req = WebSearchRequest.validate("test", null, "MONTH", null, HARD_MAX, MAX_QUERY_CHARS, DEFAULT_MAX);
            URI uri = client.buildSearchUri("test", req);

            assertThat(uri.toString()).contains("time_range=month");
        }

        @Test
        void shouldOmitTimeRangeForAny() {
            WebSearchRequest req = request("test");
            URI uri = client.buildSearchUri("test", req);

            assertThat(uri.toString()).doesNotContain("time_range");
        }
    }
}
