package com.yulong.chatagent.websearch.config;

import com.yulong.chatagent.websearch.WebSearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the web search subsystem.
 * <p>
 * Phase 1: registers the properties binding.
 * Phase 2 will add the SearXNG client bean and health-check wiring.
 */
@Configuration
@EnableConfigurationProperties(WebSearchProperties.class)
public class WebSearchConfiguration {
}
