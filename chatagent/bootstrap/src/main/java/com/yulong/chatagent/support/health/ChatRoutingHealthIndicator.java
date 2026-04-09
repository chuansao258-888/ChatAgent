package com.yulong.chatagent.support.health;

import com.yulong.chatagent.admin.application.ChatRoutingAdminFacadeService;
import com.yulong.chatagent.admin.model.response.GetChatRoutingStateResponse;
import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Exposes routing readiness through Actuator so fallback/circuit incidents can be alerted.
 */
@Component("chatRouting")
@RequiredArgsConstructor
public class ChatRoutingHealthIndicator implements HealthIndicator {

    private static final Status DEGRADED = new Status("DEGRADED");

    private final ChatRoutingAdminFacadeService chatRoutingAdminFacadeService;
    private final ChatRoutingProperties properties;

    @Override
    public Health health() {
        GetChatRoutingStateResponse state = chatRoutingAdminFacadeService.getRoutingState();
        List<ChatRoutingCandidateVO> candidates = state.getCandidates() == null
                ? List.of()
                : Arrays.asList(state.getCandidates());
        List<ChatRoutingCandidateVO> routableCandidates = candidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getEffectiveEnabled()))
                .filter(candidate -> Boolean.TRUE.equals(candidate.getRegistered()))
                .toList();
        long openCandidates = routableCandidates.stream()
                .filter(candidate -> "OPEN".equals(candidate.getCircuitState()))
                .count();
        long halfOpenCandidates = routableCandidates.stream()
                .filter(candidate -> "HALF_OPEN".equals(candidate.getCircuitState()))
                .count();
        int orphanOverrides = state.getOrphanOverrideCandidateIds() == null
                ? 0
                : state.getOrphanOverrideCandidateIds().length;

        Status status = resolveStatus(routableCandidates, openCandidates, orphanOverrides);
        Health.Builder builder = Health.status(status)
                .withDetail("defaultModel", state.getDefaultModel())
                .withDetail("deepThinkingModel", state.getDeepThinkingModel())
                .withDetail("candidateCount", candidates.size())
                .withDetail("routableCandidateCount", routableCandidates.size())
                .withDetail("openCircuitCount", openCandidates)
                .withDetail("halfOpenCircuitCount", halfOpenCandidates)
                .withDetail("openCircuitWarningRatio", observability().getOpenCircuitWarningRatio())
                .withDetail("orphanOverrideCount", orphanOverrides);

        if (status != Status.UP) {
            builder.withDetail("reason", reasonFor(status, routableCandidates, openCandidates, orphanOverrides));
        }
        return builder.build();
    }

    private Status resolveStatus(List<ChatRoutingCandidateVO> routableCandidates,
                                 long openCandidates,
                                 int orphanOverrides) {
        if (routableCandidates.isEmpty() && observability().isDownWhenNoRoutableCandidates()) {
            return Status.DOWN;
        }

        if (!routableCandidates.isEmpty()
                && openCandidates == routableCandidates.size()
                && observability().isOutOfServiceWhenAllRoutableCandidatesOpen()) {
            return Status.OUT_OF_SERVICE;
        }

        double openRatio = routableCandidates.isEmpty()
                ? 0.0D
                : (double) openCandidates / (double) routableCandidates.size();
        if (openRatio >= observability().getOpenCircuitWarningRatio()) {
            return DEGRADED;
        }

        if (orphanOverrides > 0 && observability().isWarnOnOrphanOverrides()) {
            return DEGRADED;
        }

        return Status.UP;
    }

    private String reasonFor(Status status,
                             List<ChatRoutingCandidateVO> routableCandidates,
                             long openCandidates,
                             int orphanOverrides) {
        if (status == Status.DOWN) {
            return "no_routable_candidates";
        }
        if (status == Status.OUT_OF_SERVICE) {
            return "all_routable_candidates_open";
        }
        if (orphanOverrides > 0 && observability().isWarnOnOrphanOverrides()) {
            return "orphan_runtime_overrides";
        }
        double openRatio = routableCandidates.isEmpty()
                ? 0.0D
                : (double) openCandidates / (double) routableCandidates.size();
        if (openRatio >= observability().getOpenCircuitWarningRatio()) {
            return "open_circuit_ratio_exceeded";
        }
        return "routing_degraded";
    }

    private ChatRoutingProperties.ObservabilityConfig observability() {
        return properties.getObservability() == null
                ? new ChatRoutingProperties.ObservabilityConfig()
                : properties.getObservability();
    }
}
