package com.yulong.chatagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeReliabilityScenarioTest {

    @Test
    void manifestHasStableUniqueCasesAndCrossPhaseCoverage() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/scenarios/agent-runtime-reliability-v1.json")) {
            assertThat(input).isNotNull();
            JsonNode root = new ObjectMapper().readTree(input);
            assertThat(root.path("version").asText()).isEqualTo("ARRB-SCENARIOS-1");
            JsonNode cases = root.path("cases");
            assertThat(cases.isArray()).isTrue();
            assertThat(cases.size()).isGreaterThanOrEqualTo(12);
            Set<String> ids = new HashSet<>();
            Set<String> areas = new HashSet<>();
            cases.forEach(item -> {
                assertThat(item.path("id").asText()).matches("ARRB-S\\d{2}");
                assertThat(item.path("oracle").asText()).isNotBlank();
                assertThat(ids.add(item.path("id").asText())).isTrue();
                areas.add(item.path("area").asText());
            });
            assertThat(areas).containsExactlyInAnyOrder(
                    "tool", "deepthink", "mcp", "search", "final", "privacy");
        }
    }

    @Test
    void terminalStreamStatusesAreExplicitAndOnlyCompleteIsSuccess() {
        for (AgentMessageBridge.FinalStreamStatus status : AgentMessageBridge.FinalStreamStatus.values()) {
            AgentMessageBridge.FinalStreamResult result =
                    new AgentMessageBridge.FinalStreamResult(status, "partial");
            assertThat(result.complete()).isEqualTo(status == AgentMessageBridge.FinalStreamStatus.COMPLETE);
        }
    }
}
