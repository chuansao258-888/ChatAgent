package com.yulong.chatagent.mcp.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime resilience settings for MCP tool execution.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.mcp.runtime")
@Data
public class McpRuntimeProtectionProperties {

    private boolean rateLimitEnabled = true;
    private int rateLimitRequestsPerSecond = 10;
    private int rateLimitBurstCapacity = 10;

    private boolean circuitBreakerEnabled = true;
    private int circuitBreakerSlidingWindowSize = 10;
    private int circuitBreakerFailureThreshold = 5;
    private int circuitBreakerFailureRateThresholdPercent = 50;
    private int circuitBreakerMinimumRequestVolume = 10;
    private long circuitBreakerOpenStateMs = 30000L;
    private int circuitBreakerHalfOpenProbeCount = 3;
    private long circuitBreakerSlowCallDurationMs = 10000L;
    private int circuitBreakerSlowCallRateThresholdPercent = 80;
}
