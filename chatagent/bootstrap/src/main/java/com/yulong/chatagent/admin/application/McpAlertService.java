package com.yulong.chatagent.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.admin.port.McpAlertEventRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.McpAlertEventDTO;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAlertSeverity;
import com.yulong.chatagent.support.enums.McpAlertStatus;
import com.yulong.chatagent.support.enums.McpAlertType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raises and resolves persisted MCP admin alerts.
 */
@Component
public class McpAlertService {

    private final McpAlertEventRepository alertEventRepository;
    private final ObjectMapper objectMapper;

    public McpAlertService(McpAlertEventRepository alertEventRepository, ObjectMapper objectMapper) {
        this.alertEventRepository = alertEventRepository;
        this.objectMapper = objectMapper;
    }

    public void raiseServerFailed(McpServerDTO server, int consecutiveFailures, String errorCode, String errorMessage) {
        raiseOrRefresh(
                server,
                null,
                McpAlertType.SERVER_FAILED,
                McpAlertSeverity.ERROR,
                "MCP server entered FAILED state after repeated connectivity errors",
                details(
                        "consecutiveFailures", consecutiveFailures,
                        "errorCode", errorCode,
                        "errorMessage", trim(errorMessage)
                )
        );
    }

    public void raiseSchemaDrift(McpServerDTO server, int staleToolCount, String message) {
        raiseOrRefresh(
                server,
                null,
                McpAlertType.SCHEMA_DRIFT,
                McpAlertSeverity.WARNING,
                "MCP schema drift detected; runtime exposure requires admin sync",
                details(
                        "staleToolCount", staleToolCount,
                        "message", trim(message)
                )
        );
    }

    public void raiseUnresolvedReference(String serverId,
                                         String serverSlug,
                                         int activeReferenceCount,
                                         List<String> referencePaths) {
        raiseOrRefresh(
                McpServerDTO.builder().id(serverId).slug(serverSlug).build(),
                null,
                McpAlertType.UNRESOLVED_REFERENCE,
                McpAlertSeverity.WARNING,
                "MCP server was force-deleted while active references still exist",
                details(
                        "activeReferenceCount", activeReferenceCount,
                        "referencePaths", referencePaths == null ? List.of() : referencePaths
                )
        );
    }

    public void resolveServerRecovered(String serverId) {
        resolve(serverId, McpAlertType.SERVER_FAILED);
    }

    public void resolveSchemaDrift(String serverId) {
        resolve(serverId, McpAlertType.SCHEMA_DRIFT);
    }

    public List<McpAlertEventDTO> recentOpenAlerts(int limit) {
        return alertEventRepository.findRecentOpen(Math.max(1, limit));
    }

    public long openAlertCount() {
        return alertEventRepository.countOpen();
    }

    private void raiseOrRefresh(McpServerDTO server,
                                String toolName,
                                McpAlertType alertType,
                                McpAlertSeverity severity,
                                String summary,
                                Map<String, Object> details) {
        LocalDateTime now = LocalDateTime.now();
        McpAlertEventDTO existing = server == null || server.getId() == null
                ? null
                : alertEventRepository.findOpenByServerAndType(server.getId(), alertType);
        McpAlertEventDTO payload = McpAlertEventDTO.builder()
                .id(existing == null ? UUID.randomUUID().toString() : existing.getId())
                .serverId(server == null ? null : server.getId())
                .serverSlug(server == null ? null : server.getSlug())
                .toolName(toolName)
                .alertType(alertType)
                .severity(severity)
                .status(McpAlertStatus.OPEN)
                .summary(summary)
                .detailsJson(toJson(details))
                .resolvedAt(null)
                .createdAt(existing == null ? now : existing.getCreatedAt())
                .updatedAt(now)
                .build();
        boolean success = existing == null
                ? alertEventRepository.save(payload)
                : alertEventRepository.update(payload);
        if (!success) {
            throw new BizException("Failed to persist MCP alert event: " + alertType);
        }
    }

    private void resolve(String serverId, McpAlertType alertType) {
        if (serverId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        alertEventRepository.resolveOpenByServerAndType(serverId, alertType, now, now);
    }

    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new BizException("Failed to serialize MCP alert details");
        }
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private Map<String, Object> details(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            details.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return details;
    }
}
