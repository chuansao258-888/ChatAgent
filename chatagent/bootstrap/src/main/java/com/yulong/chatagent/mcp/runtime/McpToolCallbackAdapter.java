package com.yulong.chatagent.mcp.runtime;

import com.yulong.chatagent.mcp.application.McpFeatureFlag;
import com.yulong.chatagent.mcp.metrics.McpMetricsRecorder;
import com.yulong.chatagent.mcp.model.McpToolCallResult;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.mcp.transport.McpTransportException;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Spring AI callback that bridges one model-facing tool definition to one remote MCP tool.
 */
@Slf4j
public class McpToolCallbackAdapter implements ToolCallback {

    private final String serverSlug;
    private final String remoteOriginalName;
    private final ToolDefinition toolDefinition;
    private final McpServerDTO server;
    private final McpTransportClient transportClient;
    private final McpFeatureFlag featureFlag;
    private final McpToolResponseSanitizer responseSanitizer;
    private final McpToolErrorEnvelopeFactory errorEnvelopeFactory;
    private final McpServerRateLimiter rateLimiter;
    private final McpServerCircuitBreakerRegistry circuitBreakerRegistry;
    private final McpMetricsRecorder metricsRecorder;

    public McpToolCallbackAdapter(String serverSlug,
                                  String remoteOriginalName,
                                  ToolDefinition toolDefinition,
                                  McpServerDTO server,
                                  McpTransportClient transportClient,
                                  McpFeatureFlag featureFlag,
                                  McpToolResponseSanitizer responseSanitizer,
                                  McpToolErrorEnvelopeFactory errorEnvelopeFactory,
                                  McpServerRateLimiter rateLimiter,
                                  McpServerCircuitBreakerRegistry circuitBreakerRegistry,
                                  McpMetricsRecorder metricsRecorder) {
        this.serverSlug = serverSlug;
        this.remoteOriginalName = remoteOriginalName;
        this.toolDefinition = toolDefinition;
        this.server = server;
        this.transportClient = transportClient;
        this.featureFlag = featureFlag;
        this.responseSanitizer = responseSanitizer;
        this.errorEnvelopeFactory = errorEnvelopeFactory;
        this.rateLimiter = rateLimiter;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.metricsRecorder = metricsRecorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (!featureFlag.isEnabled()) {
            return errorEnvelopeFactory.error("MCP_DISABLED", "MCP outbound calls are globally disabled");
        }

        InternalToolContext internalToolContext = InternalToolContext.from(toolContext);
        if (!internalToolContext.isReady()) {
            return errorEnvelopeFactory.error("MCP_CONTEXT_MISSING", "Tool context is missing; outbound call rejected");
        }

        if (!rateLimiter.tryAcquire(server.getId())) {
            metricsRecorder.recordRateLimited(server, toolDefinition.name());
            log.warn("MCP tool call rejected by rate limiter: traceId={}, serverId={}, serverSlug={}, tool={}",
                    TraceContext.getTraceId(), server.getId(), serverSlug, toolDefinition.name());
            return errorEnvelopeFactory.error("MCP_RATE_LIMITED", "MCP server rate limit exceeded; retry later");
        }

        McpServerCircuitBreaker circuitBreaker = circuitBreakerRegistry.get(server);
        if (!circuitBreaker.allowRequest()) {
            metricsRecorder.recordFailure(server, toolDefinition.name(), "MCP_CIRCUIT_OPEN", 0L);
            log.warn("MCP tool call rejected by circuit breaker: traceId={}, serverId={}, serverSlug={}, tool={}",
                    TraceContext.getTraceId(), server.getId(), serverSlug, toolDefinition.name());
            return errorEnvelopeFactory.error("MCP_CIRCUIT_OPEN", "MCP server is temporarily unavailable");
        }

        long startTime = System.nanoTime();
        try {
            McpToolCallResult callResult = transportClient.callTool(server, remoteOriginalName, toolInput);
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            circuitBreaker.recordSuccess(latencyMs);
            McpToolResponseSanitizer.SanitizedToolResponse sanitized = responseSanitizer.sanitize(callResult.payload());
            metricsRecorder.recordSuccess(server, toolDefinition.name(), latencyMs);
            log.info("MCP tool call completed: traceId={}, serverId={}, serverSlug={}, tool={}, remoteTool={}, latencyMs={}, truncated={}, outcome=success",
                    TraceContext.getTraceId(), server.getId(), serverSlug, toolDefinition.name(), remoteOriginalName, latencyMs, sanitized.truncated());
            return errorEnvelopeFactory.ok(
                    serverSlug,
                    toolDefinition.name(),
                    sanitized.content(),
                    sanitized.truncated()
            );
        } catch (McpTransportException ex) {
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            circuitBreaker.recordFailure(latencyMs);
            metricsRecorder.recordFailure(server, toolDefinition.name(), ex.getErrorCode(), latencyMs);
            log.warn("MCP tool call failed: traceId={}, serverId={}, serverSlug={}, tool={}, remoteTool={}, latencyMs={}, outcome=failure, errorCode={}, message={}",
                    TraceContext.getTraceId(), server.getId(), serverSlug, toolDefinition.name(), remoteOriginalName, latencyMs, ex.getErrorCode(), ex.getMessage());
            return errorEnvelopeFactory.error(ex.getErrorCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            circuitBreaker.recordFailure(latencyMs);
            metricsRecorder.recordFailure(server, toolDefinition.name(), "MCP_RUNTIME_FAILED", latencyMs);
            log.warn("MCP tool call crashed: traceId={}, serverId={}, serverSlug={}, tool={}, remoteTool={}, latencyMs={}, outcome=failure, errorCode=MCP_RUNTIME_FAILED, message={}",
                    TraceContext.getTraceId(), server.getId(), serverSlug, toolDefinition.name(), remoteOriginalName, latencyMs, ex.getMessage());
            return errorEnvelopeFactory.error("MCP_RUNTIME_FAILED", ex.getMessage());
        }
    }
}
