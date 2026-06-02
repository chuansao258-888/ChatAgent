package com.yulong.chatagent.websearch.searxng;

import com.yulong.chatagent.websearch.WebSearchProperties;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Probes SearXNG availability once on application startup.
 * <p>
 * The reachability flag is stored in an {@link AtomicBoolean} and can be
 * queried by the tool layer to decide whether to expose the web search tool.
 * Runtime failures are handled by the client itself (safe error messages);
 * this check only gates initial tool exposure.
 */
@Component
public class SearXNGHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(SearXNGHealthChecker.class);
    private static final String HEALTH_QUERY = "health_check";

    private final WebSearchProperties properties;
    private final AtomicBoolean reachable = new AtomicBoolean(false);

    public SearXNGHealthChecker(WebSearchProperties properties) {
        this.properties = properties;
    }

    /**
     * Probe SearXNG after the application has started.
     * If the tool is disabled, skip the probe entirely.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void probe() {
        if (!properties.isEnabled()) {
            log.debug("Web search disabled; skipping SearXNG health probe.");
            return;
        }

        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(Math.min(properties.getConnectTimeoutMs(), 2000)));
            factory.setReadTimeout(Duration.ofMillis(3000));

            RestClient probeClient = RestClient.builder()
                    .requestFactory(factory)
                    .build();

            URI uri = URI.create(properties.getSearxngBaseUrl()
                    + "/search?q=" + HEALTH_QUERY
                    + "&format=json"
                    + "&safesearch=0");

            probeClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            reachable.set(true);
            log.info("SearXNG is reachable at {} — web search tool will be exposed.", properties.getSearxngBaseUrl());
        } catch (Exception e) {
            reachable.set(false);
            log.warn("SearXNG is NOT reachable at {}: {}. Web search tool will not be exposed.",
                    properties.getSearxngBaseUrl(), e.getMessage());
        }
    }

    /**
     * Return whether SearXNG was reachable at application startup.
     */
    public boolean isReachable() {
        return reachable.get();
    }
}
