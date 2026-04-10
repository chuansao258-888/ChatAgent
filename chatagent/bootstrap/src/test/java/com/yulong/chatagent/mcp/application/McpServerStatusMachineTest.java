package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.support.enums.McpServerStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerStatusMachineTest {

    private final McpServerStatusMachine statusMachine = new McpServerStatusMachine();

    @Test
    void shouldDefaultNewServersToDisabled() {
        assertThat(statusMachine.initialStatus()).isEqualTo(McpServerStatus.DISABLED);
    }

    @Test
    void shouldMarkSensitiveConfigChangesAsStale() {
        assertThat(statusMachine.markSensitiveConfigChanged(McpServerStatus.ACTIVE)).isEqualTo(McpServerStatus.STALE);
    }

    @Test
    void shouldMarkHealthyServersActive() {
        assertThat(statusMachine.activate()).isEqualTo(McpServerStatus.ACTIVE);
    }

    @Test
    void shouldEscalateRepeatedConnectivityFailuresToFailed() {
        assertThat(statusMachine.markConnectivityFailure(1)).isEqualTo(McpServerStatus.STALE);
        assertThat(statusMachine.markConnectivityFailure(3)).isEqualTo(McpServerStatus.FAILED);
    }
}
