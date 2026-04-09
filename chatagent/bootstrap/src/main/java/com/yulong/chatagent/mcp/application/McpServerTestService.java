package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.admin.application.McpAlertService;
import com.yulong.chatagent.admin.application.McpServerStatusMachine;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.mcp.model.McpDiscoveryResult;
import com.yulong.chatagent.mcp.model.McpServerProbeOutcome;
import com.yulong.chatagent.mcp.transport.McpTransportClient;
import com.yulong.chatagent.mcp.transport.McpTransportException;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpServerStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Runs admin-side MCP connectivity tests and persists resulting server state.
 */
@Component
public class McpServerTestService {

    private final McpFeatureFlag featureFlag;
    private final McpTransportClient transportClient;
    private final McpServerRepository serverRepository;
    private final McpServerStatusMachine statusMachine;
    private final McpAlertService alertService;

    public McpServerTestService(McpFeatureFlag featureFlag,
                                McpTransportClient transportClient,
                                McpServerRepository serverRepository,
                                McpServerStatusMachine statusMachine,
                                McpAlertService alertService) {
        this.featureFlag = featureFlag;
        this.transportClient = transportClient;
        this.serverRepository = serverRepository;
        this.statusMachine = statusMachine;
        this.alertService = alertService;
    }

    public McpServerProbeOutcome test(McpServerDTO server) {
        if (!featureFlag.isEnabled()) {
            return new McpServerProbeOutcome(false, "MCP_DISABLED", "MCP outbound transport is disabled by configuration", null, server);
        }
        try {
            McpDiscoveryResult discoveryResult = transportClient.discover(server);
            McpServerDTO updated = persistProbeSuccess(server, discoveryResult);
            return new McpServerProbeOutcome(true, null, null, discoveryResult, updated);
        } catch (McpTransportException ex) {
            McpServerDTO updated = persistFailure(server, ex.getErrorCode(), ex.getMessage());
            return new McpServerProbeOutcome(false, ex.getErrorCode(), ex.getMessage(), null, updated);
        }
    }

    McpServerDTO persistProbeSuccess(McpServerDTO existing, McpDiscoveryResult discoveryResult) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime initializedAt = discoveryResult == null ? now : discoveryResult.initializedAt();
        McpServerDTO updated = McpServerDTO.builder()
                .id(existing.getId())
                .slug(existing.getSlug())
                .name(existing.getName())
                .description(existing.getDescription())
                .protocol(existing.getProtocol())
                .authType(existing.getAuthType())
                .endpointUrl(existing.getEndpointUrl())
                .encryptedCredentials(existing.getEncryptedCredentials())
                .credentialKeyVersion(existing.getCredentialKeyVersion())
                .status(statusMachine.activate())
                .consecutiveFailures(0)
                .lastTestedAt(now)
                .lastInitializedAt(initializedAt)
                .lastSyncAt(existing.getLastSyncAt())
                .lastErrorCode(null)
                .lastErrorMessage(null)
                .createdAt(existing.getCreatedAt())
                .updatedAt(now)
                .build();
        persist(updated);
        alertService.resolveServerRecovered(updated.getId());
        return updated;
    }

    private McpServerDTO persistFailure(McpServerDTO existing, String errorCode, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        int consecutiveFailures = (existing.getConsecutiveFailures() == null ? 0 : existing.getConsecutiveFailures()) + 1;
        McpServerDTO updated = McpServerDTO.builder()
                .id(existing.getId())
                .slug(existing.getSlug())
                .name(existing.getName())
                .description(existing.getDescription())
                .protocol(existing.getProtocol())
                .authType(existing.getAuthType())
                .endpointUrl(existing.getEndpointUrl())
                .encryptedCredentials(existing.getEncryptedCredentials())
                .credentialKeyVersion(existing.getCredentialKeyVersion())
                .status(statusMachine.markConnectivityFailure(consecutiveFailures))
                .consecutiveFailures(consecutiveFailures)
                .lastTestedAt(now)
                .lastInitializedAt(existing.getLastInitializedAt())
                .lastSyncAt(existing.getLastSyncAt())
                .lastErrorCode(errorCode)
                .lastErrorMessage(trimForStorage(errorMessage))
                .createdAt(existing.getCreatedAt())
                .updatedAt(now)
                .build();
        persist(updated);
        if (updated.getStatus() == McpServerStatus.FAILED) {
            alertService.raiseServerFailed(updated, consecutiveFailures, errorCode, errorMessage);
        }
        return updated;
    }

    protected McpServerDTO persistSyncFailure(McpServerDTO existing, String errorCode, String errorMessage) {
        return persistFailure(existing, errorCode, errorMessage);
    }

    protected McpServerDTO persistSyncSuccess(McpServerDTO existing, McpDiscoveryResult discoveryResult, LocalDateTime syncTime) {
        LocalDateTime now = syncTime == null ? LocalDateTime.now() : syncTime;
        McpServerDTO updated = McpServerDTO.builder()
                .id(existing.getId())
                .slug(existing.getSlug())
                .name(existing.getName())
                .description(existing.getDescription())
                .protocol(existing.getProtocol())
                .authType(existing.getAuthType())
                .endpointUrl(existing.getEndpointUrl())
                .encryptedCredentials(existing.getEncryptedCredentials())
                .credentialKeyVersion(existing.getCredentialKeyVersion())
                .status(statusMachine.activate())
                .consecutiveFailures(0)
                .lastTestedAt(now)
                .lastInitializedAt(discoveryResult == null ? existing.getLastInitializedAt() : discoveryResult.initializedAt())
                .lastSyncAt(now)
                .lastErrorCode(null)
                .lastErrorMessage(null)
                .createdAt(existing.getCreatedAt())
                .updatedAt(now)
                .build();
        persist(updated);
        alertService.resolveServerRecovered(updated.getId());
        alertService.resolveSchemaDrift(updated.getId());
        return updated;
    }

    McpServerDTO persistSchemaDriftDetected(McpServerDTO existing,
                                            McpDiscoveryResult discoveryResult,
                                            LocalDateTime detectionTime,
                                            int staleToolCount,
                                            String message) {
        LocalDateTime now = detectionTime == null ? LocalDateTime.now() : detectionTime;
        McpServerDTO updated = McpServerDTO.builder()
                .id(existing.getId())
                .slug(existing.getSlug())
                .name(existing.getName())
                .description(existing.getDescription())
                .protocol(existing.getProtocol())
                .authType(existing.getAuthType())
                .endpointUrl(existing.getEndpointUrl())
                .encryptedCredentials(existing.getEncryptedCredentials())
                .credentialKeyVersion(existing.getCredentialKeyVersion())
                .status(statusMachine.markSensitiveConfigChanged(existing.getStatus()))
                .consecutiveFailures(0)
                .lastTestedAt(now)
                .lastInitializedAt(discoveryResult == null ? existing.getLastInitializedAt() : discoveryResult.initializedAt())
                .lastSyncAt(existing.getLastSyncAt())
                .lastErrorCode("MCP_SCHEMA_DRIFT")
                .lastErrorMessage(trimForStorage(message))
                .createdAt(existing.getCreatedAt())
                .updatedAt(now)
                .build();
        persist(updated);
        alertService.raiseSchemaDrift(updated, staleToolCount, message);
        return updated;
    }

    private void persist(McpServerDTO updated) {
        if (!serverRepository.update(updated)) {
            throw new BizException("Failed to persist MCP server state: " + updated.getId());
        }
    }

    private String trimForStorage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() > 512 ? errorMessage.substring(0, 512) : errorMessage;
    }
}
