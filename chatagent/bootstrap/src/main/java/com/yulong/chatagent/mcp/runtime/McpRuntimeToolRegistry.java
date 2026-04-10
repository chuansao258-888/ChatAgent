package com.yulong.chatagent.mcp.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.mcp.application.McpFeatureFlag;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.admin.port.McpToolCatalogRepository;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.dto.McpToolCatalogDTO;
import com.yulong.chatagent.support.enums.McpServerStatus;
import com.yulong.chatagent.support.enums.McpToolCatalogStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cached runtime view of MCP-backed optional tools.
 */
@Component
public class McpRuntimeToolRegistry {

    private static final String OPTIONAL_TOOLS_CACHE_KEY = "optional-tools";

    private final McpFeatureFlag featureFlag;
    private final McpServerRepository mcpServerRepository;
    private final McpToolCatalogRepository mcpToolCatalogRepository;
    private final McpToolDefinitionFactory toolDefinitionFactory;
    private final McpTransportClient transportClient;
    private final McpToolResponseSanitizer responseSanitizer;
    private final McpToolErrorEnvelopeFactory errorEnvelopeFactory;
    private final McpServerRateLimiter rateLimiter;
    private final McpServerCircuitBreakerRegistry circuitBreakerRegistry;
    private final McpMetricsRecorder metricsRecorder;
    private final Cache<String, List<Tool>> optionalToolsCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public McpRuntimeToolRegistry(McpFeatureFlag featureFlag,
                                  McpServerRepository mcpServerRepository,
                                  McpToolCatalogRepository mcpToolCatalogRepository,
                                  McpToolDefinitionFactory toolDefinitionFactory,
                                  McpTransportClient transportClient,
                                  McpToolResponseSanitizer responseSanitizer,
                                  McpToolErrorEnvelopeFactory errorEnvelopeFactory,
                                  McpServerRateLimiter rateLimiter,
                                  McpServerCircuitBreakerRegistry circuitBreakerRegistry,
                                  McpMetricsRecorder metricsRecorder) {
        this.featureFlag = featureFlag;
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolCatalogRepository = mcpToolCatalogRepository;
        this.toolDefinitionFactory = toolDefinitionFactory;
        this.transportClient = transportClient;
        this.responseSanitizer = responseSanitizer;
        this.errorEnvelopeFactory = errorEnvelopeFactory;
        this.rateLimiter = rateLimiter;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.metricsRecorder = metricsRecorder;
    }

    public List<Tool> getOptionalTools() {
        if (!featureFlag.isEnabled()) {
            return List.of();
        }
        List<Tool> cached = optionalToolsCache.getIfPresent(OPTIONAL_TOOLS_CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        List<Tool> loaded = loadOptionalTools();
        optionalToolsCache.put(OPTIONAL_TOOLS_CACHE_KEY, loaded);
        return loaded;
    }

    public void invalidate() {
        optionalToolsCache.invalidateAll();
    }

    private List<Tool> loadOptionalTools() {
        List<Tool> runtimeTools = new ArrayList<>();
        for (McpServerDTO server : mcpServerRepository.findAll()) {
            if (server.getStatus() != McpServerStatus.ACTIVE) {
                continue;
            }
            for (McpToolCatalogDTO toolCatalog : mcpToolCatalogRepository.findByServerId(server.getId())) {
                if (toolCatalog.getStatus() != McpToolCatalogStatus.ENABLED) {
                    continue;
                }
                runtimeTools.add(new McpToolWrapper(
                        toolCatalog.getExposedModelName(),
                        toolCatalog.getToolDescription(),
                        List.of(new McpToolCallbackAdapter(
                                server.getSlug(),
                                toolCatalog.getRemoteOriginalName(),
                                toolDefinitionFactory.create(toolCatalog),
                                server,
                                transportClient,
                                featureFlag,
                                responseSanitizer,
                                errorEnvelopeFactory,
                                rateLimiter,
                                circuitBreakerRegistry,
                                metricsRecorder
                        ))
                ));
            }
        }
        return List.copyOf(runtimeTools);
    }
}
