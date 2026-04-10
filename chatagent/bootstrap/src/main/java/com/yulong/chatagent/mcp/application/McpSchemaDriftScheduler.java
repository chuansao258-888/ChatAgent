package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.mcp.application.McpFeatureFlag;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpServerStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic scheduler that checks live MCP schemas for drift on active servers.
 */
@Component
@Slf4j
public class McpSchemaDriftScheduler {

    private final McpFeatureFlag featureFlag;
    private final McpSchemaDriftProperties properties;
    private final McpServerRepository serverRepository;
    private final McpSchemaDriftDetector schemaDriftDetector;

    public McpSchemaDriftScheduler(McpFeatureFlag featureFlag,
                                   McpSchemaDriftProperties properties,
                                   McpServerRepository serverRepository,
                                   McpSchemaDriftDetector schemaDriftDetector) {
        this.featureFlag = featureFlag;
        this.properties = properties;
        this.serverRepository = serverRepository;
        this.schemaDriftDetector = schemaDriftDetector;
    }

    @Scheduled(fixedDelayString = "${chatagent.mcp.schema-drift.fixed-delay-ms:600000}")
    public void run() {
        if (!featureFlag.isEnabled() || !properties.isEnabled()) {
            return;
        }
        for (McpServerDTO server : serverRepository.findAll()) {
            if (server.getStatus() != McpServerStatus.ACTIVE) {
                continue;
            }
            schemaDriftDetector.detect(server);
        }
        log.debug("MCP schema drift pass completed");
    }
}
