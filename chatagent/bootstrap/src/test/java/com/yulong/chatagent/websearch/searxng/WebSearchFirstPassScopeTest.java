package com.yulong.chatagent.websearch.searxng;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.tools.WebSearchTools;
import com.yulong.chatagent.websearch.WebSearchProperties;
import com.yulong.chatagent.websearch.WebSearchRequest;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchFirstPassScopeTest {

    @Test
    void webSearchToolsShouldExposeOnlySearchFunctionToModel() {
        List<Method> toolMethods = Arrays.stream(WebSearchTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class))
                .toList();

        assertThat(toolMethods).hasSize(1);
        Method method = toolMethods.get(0);
        org.springframework.ai.tool.annotation.Tool annotation =
                method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);

        assertThat(method.getName()).isEqualTo("webSearch");
        assertThat(annotation.name()).isEqualTo("webSearch");
        assertThat(method.getName()).doesNotContainIgnoringCase("fetch");
        assertThat(annotation.name()).doesNotContainIgnoringCase("fetch");
    }

    @Test
    void userSuppliedUrlsShouldRemainSearchQueriesNotFetchDestinations() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setSearxngBaseUrl("http://localhost:8888");
        SearXNGWebSearchClient client = new SearXNGWebSearchClient(properties, new ObjectMapper());
        WebSearchRequest request = WebSearchRequest.validate(
                "https://example.com/private?token=abc",
                3,
                null,
                null,
                8,
                300,
                5
        );

        URI uri = client.buildSearchUri(request.query(), request);

        assertThat(uri.getHost()).isEqualTo("localhost");
        assertThat(uri.getPath()).isEqualTo("/search");
        assertThat(uri.getRawQuery()).contains("q=https%3A%2F%2Fexample.com%2Fprivate%3Ftoken%3Dabc");
    }
}
