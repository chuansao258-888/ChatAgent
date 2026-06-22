package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.websearch.WebSearchProperties;
import com.yulong.chatagent.websearch.WebSearchRequest;
import com.yulong.chatagent.websearch.WebSearchResponse;
import com.yulong.chatagent.websearch.WebSearchResult;
import com.yulong.chatagent.websearch.searxng.SearXNGHealthChecker;
import com.yulong.chatagent.websearch.searxng.SearXNGWebSearchClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class WebSearchToolsTest {

    private WebSearchProperties properties;
    private SearXNGHealthChecker healthChecker;
    private SearXNGWebSearchClient searchClient;
    private WebSearchTools tool;

    @BeforeEach
    void setUp() {
        properties = new WebSearchProperties();
        properties.setEnabled(true);
        properties.setDefaultMaxResults(3);
        properties.setMaxResults(3);
        properties.setMaxResultSnippetChars(240);
        properties.setMaxQueryChars(300);
        healthChecker = mock(SearXNGHealthChecker.class);
        searchClient = mock(SearXNGWebSearchClient.class);
        when(healthChecker.isReachable()).thenReturn(true);
        tool = new WebSearchTools(properties, healthChecker, searchClient);
    }

    @Test
    void shouldExposeExpectedBackendMetadata() {
        assertThat(tool.getName()).isEqualTo("webSearchTool");
        assertThat(tool.getType()).isEqualTo(ToolType.OPTIONAL);
        assertThat(tool.getDescription()).containsIgnoringCase("web");
    }

    @Test
    void shouldReportAvailabilityFromEnabledAndHealthState() {
        assertThat(tool.isAvailable()).isTrue();

        when(healthChecker.isReachable()).thenReturn(false);
        assertThat(tool.isAvailable()).isFalse();

        properties.setEnabled(false);
        assertThat(tool.isAvailable()).isFalse();
    }

    @Test
    void disabledToolShouldReturnSafeErrorWithoutCallingProvider() {
        properties.setEnabled(false);

        String output = tool.webSearch("spring boot", null, null, null);

        assertThat(output).contains("disabled");
        verifyNoInteractions(healthChecker, searchClient);
    }

    @Test
    void unreachableProviderShouldReturnSafeErrorWithoutCallingProvider() {
        when(healthChecker.isReachable()).thenReturn(false);

        String output = tool.webSearch("spring boot", null, null, null);

        assertThat(output).contains("not reachable");
        verify(healthChecker).isReachable();
        verifyNoInteractions(searchClient);
    }

    @Test
    void invalidRequestShouldReturnSafeErrorWithoutCallingProvider() {
        String output = tool.webSearch("   ", null, null, null);

        assertThat(output).contains("invalid web search request");
        verifyNoInteractions(searchClient);
    }

    @Test
    void shouldValidateAndPassNormalizedRequestToClient() {
        when(searchClient.search(any(WebSearchRequest.class)))
                .thenReturn(WebSearchResponse.empty());

        tool.webSearch("  Spring Boot  ", 99, "day", " Spring.IO,SPRING.io ");

        ArgumentCaptor<WebSearchRequest> captor = ArgumentCaptor.forClass(WebSearchRequest.class);
        verify(searchClient).search(captor.capture());
        WebSearchRequest request = captor.getValue();
        assertThat(request.query()).isEqualTo("Spring Boot");
        assertThat(request.maxResults()).isEqualTo(3);
        assertThat(request.freshness()).isEqualTo(WebSearchRequest.Freshness.DAY);
        assertThat(request.domains()).containsExactly("spring.io");
    }

    @Test
    void successfulSearchShouldFormatResultsForModelConsumption() {
        when(searchClient.search(any(WebSearchRequest.class)))
                .thenReturn(WebSearchResponse.success(List.of(
                        new WebSearchResult(
                                "Spring Boot 3.4",
                                "https://spring.io/blog/spring-boot-3-4",
                                "Release notes",
                                Instant.parse("2025-01-01T00:00:00Z"),
                                "google",
                                0.95
                        ),
                        new WebSearchResult(
                                "Note",
                                null,
                                "Domain filter reduced results from 3 to 1.",
                                null,
                                null,
                                0.0
                        )
                )));

        String output = tool.webSearch("spring boot", null, null, null);

        assertThat(output).contains("Web search results:");
        assertThat(output).contains("1. Spring Boot 3.4");
        assertThat(output).contains("URL: https://spring.io/blog/spring-boot-3-4");
        assertThat(output).contains("Snippet: Release notes");
        assertThat(output).contains("Source: google");
        assertThat(output).contains("Published: 2025-01-01T00:00:00Z");
        assertThat(output).contains("Note: Domain filter reduced results from 3 to 1.");
    }

    @Test
    void successfulSearchShouldTrimLongSnippetsForModelConsumption() {
        properties.setMaxResultSnippetChars(12);
        when(searchClient.search(any(WebSearchRequest.class)))
                .thenReturn(WebSearchResponse.success(List.of(
                        new WebSearchResult(
                                "Compact result",
                                "https://example.org/news",
                                "0123456789abcdef",
                                null,
                                "google",
                                0.95
                        )
                )));

        String output = tool.webSearch("compact result", null, null, null);

        assertThat(output).contains("1. Compact result");
        assertThat(output).contains("URL: https://example.org/news");
        assertThat(output).contains("Snippet: 0123456789ab...");
        assertThat(output).doesNotContain("cdef");
    }

    @Test
    void emptySearchShouldReturnNoResultsMessage() {
        when(searchClient.search(any(WebSearchRequest.class)))
                .thenReturn(WebSearchResponse.empty());

        String output = tool.webSearch("nothing", null, null, null);

        assertThat(output).isEqualTo("No web search results found.");
    }

    @Test
    void providerFailureShouldReturnSafeErrorText() {
        when(searchClient.search(any(WebSearchRequest.class)))
                .thenReturn(WebSearchResponse.failure("web search provider rate limit exceeded."));

        String output = tool.webSearch("test", null, null, null);

        assertThat(output).isEqualTo("Error: web search provider rate limit exceeded.");
    }

    @Test
    void unexpectedProviderExceptionShouldReturnSafeErrorText() {
        when(searchClient.search(any(WebSearchRequest.class)))
                .thenThrow(new IllegalStateException("boom"));

        String output = tool.webSearch("test", null, null, null);

        assertThat(output).isEqualTo("Error: web search is temporarily unavailable.");
    }

    @Test
    void unexpectedProviderExceptionLogShouldNotExposeFullQuery(CapturedOutput output) {
        String sensitiveQuery = "password reset token abcdef1234567890 internal private user data";
        when(searchClient.search(any(WebSearchRequest.class)))
                .thenThrow(new IllegalStateException("failure while handling " + sensitiveQuery));

        tool.webSearch(sensitiveQuery, null, null, null);

        assertThat(output.getAll())
                .contains("Web search tool execution failed unexpectedly")
                .contains("exception=IllegalStateException")
                .doesNotContain(sensitiveQuery)
                .doesNotContain("abcdef1234567890");
    }
}
