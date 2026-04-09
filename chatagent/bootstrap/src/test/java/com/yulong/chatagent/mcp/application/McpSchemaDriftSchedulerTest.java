package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.admin.application.McpFeatureFlag;
import com.yulong.chatagent.admin.port.McpServerRepository;
import com.yulong.chatagent.support.dto.McpServerDTO;
import com.yulong.chatagent.support.enums.McpAuthType;
import com.yulong.chatagent.support.enums.McpProtocol;
import com.yulong.chatagent.support.enums.McpServerStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSchemaDriftSchedulerTest {

    @Test
    void shouldOnlyCheckActiveServersWhenSchedulerRuns() {
        McpFeatureFlag featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(true);
        McpSchemaDriftProperties properties = new McpSchemaDriftProperties();
        properties.setEnabled(true);
        McpServerRepository serverRepository = mock(McpServerRepository.class);
        McpSchemaDriftDetector detector = mock(McpSchemaDriftDetector.class);
        McpSchemaDriftScheduler scheduler = new McpSchemaDriftScheduler(featureFlag, properties, serverRepository, detector);
        McpServerDTO activeServer = server("srv-1", McpServerStatus.ACTIVE);
        McpServerDTO staleServer = server("srv-2", McpServerStatus.STALE);
        when(serverRepository.findAll()).thenReturn(List.of(activeServer, staleServer));

        scheduler.run();

        verify(detector).detect(activeServer);
        verify(detector, never()).detect(staleServer);
    }

    @Test
    void shouldSkipWhenFeatureFlagIsDisabled() {
        McpFeatureFlag featureFlag = new McpFeatureFlag();
        featureFlag.setEnabled(false);
        McpSchemaDriftProperties properties = new McpSchemaDriftProperties();
        McpServerRepository serverRepository = mock(McpServerRepository.class);
        McpSchemaDriftDetector detector = mock(McpSchemaDriftDetector.class);
        McpSchemaDriftScheduler scheduler = new McpSchemaDriftScheduler(featureFlag, properties, serverRepository, detector);

        scheduler.run();

        verify(serverRepository, never()).findAll();
        verify(detector, never()).detect(org.mockito.ArgumentMatchers.any());
    }

    private McpServerDTO server(String id, McpServerStatus status) {
        return McpServerDTO.builder()
                .id(id)
                .slug(id)
                .name(id)
                .protocol(McpProtocol.HTTP)
                .authType(McpAuthType.NONE)
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
