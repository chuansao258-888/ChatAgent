package com.yulong.chatagent.support.health;

import com.yulong.chatagent.admin.application.ChatRoutingAdminFacadeService;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;
import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRoutingHealthIndicatorTest {

    @Test
    void shouldReportDownWhenNoRoutableCandidatesExist() {
        Health health = healthFor(state(candidate("deepseek-chat", true, false, "CLOSED")));

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "no_routable_candidates");
        assertThat(health.getDetails()).containsEntry("routableCandidateCount", 0);
    }

    @Test
    void shouldReportOutOfServiceWhenAllRoutableCandidatesAreOpen() {
        Health health = healthFor(state(
                candidate("deepseek-chat", true, true, "OPEN"),
                candidate("glm-4", true, true, "OPEN")));

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("reason", "all_routable_candidates_open");
        assertThat(health.getDetails()).containsEntry("openCircuitCount", 2L);
    }

    @Test
    void shouldReportDegradedWhenOpenCircuitRatioReachesThreshold() {
        Health health = healthFor(state(
                candidate("deepseek-chat", true, true, "OPEN"),
                candidate("glm-4", true, true, "CLOSED")));

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("reason", "open_circuit_ratio_exceeded");
        assertThat(health.getDetails()).containsEntry("openCircuitWarningRatio", 0.5D);
    }

    @Test
    void shouldReportDegradedWhenOrphanOverridesExist() {
        GetChatRoutingStateResponse state = state(candidate("deepseek-chat", true, true, "CLOSED"));
        state.setOrphanOverrideCandidateIds(new String[]{"missing-model"});

        Health health = healthFor(state);

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("reason", "orphan_runtime_overrides");
        assertThat(health.getDetails()).containsEntry("orphanOverrideCount", 1);
    }

    @Test
    void shouldReportUpWhenRoutableCandidatesAreHealthy() {
        Health health = healthFor(state(
                candidate("deepseek-chat", true, true, "CLOSED"),
                candidate("glm-4", true, true, "HALF_OPEN")));

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).doesNotContainKey("reason");
        assertThat(health.getDetails()).containsEntry("halfOpenCircuitCount", 1L);
    }

    private static Health healthFor(GetChatRoutingStateResponse state) {
        ChatRoutingAdminFacadeService facadeService = mock(ChatRoutingAdminFacadeService.class);
        when(facadeService.getRoutingState()).thenReturn(state);
        return new ChatRoutingHealthIndicator(facadeService, new ChatRoutingProperties()).health();
    }

    private static GetChatRoutingStateResponse state(ChatRoutingCandidateVO... candidates) {
        return GetChatRoutingStateResponse.builder()
                .defaultModel("deepseek-chat")
                .deepThinkingModel("glm-4")
                .orphanOverrideCandidateIds(new String[0])
                .candidates(candidates)
                .build();
    }

    private static ChatRoutingCandidateVO candidate(String id,
                                                    boolean enabled,
                                                    boolean registered,
                                                    String circuitState) {
        return ChatRoutingCandidateVO.builder()
                .id(id)
                .effectiveEnabled(enabled)
                .registered(registered)
                .circuitState(circuitState)
                .build();
    }
}
