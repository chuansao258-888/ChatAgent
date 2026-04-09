package com.yulong.chatagent.chat.routing;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ModelHealthStore {

    private final ChatRoutingProperties properties;
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();
    private RoutingMetrics routingMetrics = RoutingMetrics.noop();

    @Autowired(required = false)
    void setRoutingMetrics(RoutingMetrics routingMetrics) {
        if (routingMetrics != null) {
            this.routingMetrics = routingMetrics;
        }
    }

    public boolean allowCall(String id) {
        return tryAcquire(id).allowed();
    }

    public CallPermit tryAcquire(String id) {
        if (id == null) return CallPermit.denied();
        long now = System.currentTimeMillis();
        final boolean[] allowed = {false};
        final long[] generation = {0L};
        healthById.compute(id, (k, v) -> {
            if (v == null) v = new ModelHealth();
            if (v.state == State.OPEN) {
                if (v.openUntil > now) {
                    log.debug("Model circuit call denied: modelId={}, state={}, reopenInMs={}",
                            id, v.state, v.openUntil - now);
                    routingMetrics.recordCircuitDecision(id, "denied_open");
                    return v;
                }
                v.state = State.HALF_OPEN;
                v.halfOpenStartMs = now;
                v.probeGeneration++;
                allowed[0] = true;
                generation[0] = v.probeGeneration;
                log.info("Model circuit entering HALF_OPEN: modelId={}, generation={}", id, v.probeGeneration);
                routingMetrics.recordCircuitEvent(id, "half_open");
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenStartMs > 0) {
                    // 飞行超时兜底：如果探针飞行时间超过阈值，强制重置
                    if (now - v.halfOpenStartMs > halfOpenFlightTimeoutMs()) {
                        v.halfOpenStartMs = now;
                        v.probeGeneration++;
                        allowed[0] = true;
                        generation[0] = v.probeGeneration;
                        log.warn("Model HALF_OPEN probe flight timeout, issuing new probe: modelId={}, generation={}",
                                id, v.probeGeneration);
                        routingMetrics.recordCircuitEvent(id, "half_open_probe_timeout");
                        return v;
                    }
                    log.debug("Model circuit call denied: modelId={}, state={}, activeGeneration={}",
                            id, v.state, v.probeGeneration);
                    routingMetrics.recordCircuitDecision(id, "denied_half_open");
                    return v; // 仍在飞行中，拒绝
                }
                v.halfOpenStartMs = now;
                v.probeGeneration++;
                allowed[0] = true;
                generation[0] = v.probeGeneration;
                log.info("Model circuit HALF_OPEN probe admitted: modelId={}, generation={}", id, v.probeGeneration);
                routingMetrics.recordCircuitEvent(id, "half_open_probe_admitted");
                return v;
            }
            allowed[0] = true;
            return v;
        });
        return new CallPermit(allowed[0], generation[0]);
    }

    public Map<String, HealthSnapshot> snapshot() {
        long now = System.currentTimeMillis();
        return healthById.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> {
                            ModelHealth health = entry.getValue();
                            return new HealthSnapshot(
                                    health.state.name(),
                                    health.consecutiveFailures,
                                    Math.max(health.openUntil - now, 0L),
                                    health.halfOpenStartMs,
                                    health.probeGeneration);
                        }));
    }

    public void markSuccess(String id) {
        markSuccess(id, 0L);
    }

    public void markSuccess(String id, long probeGeneration) {
        if (id == null) return;
        healthById.compute(id, (k, v) -> {
            if (v == null) return new ModelHealth();
            if (probeGeneration > 0L) {
                if (v.state != State.HALF_OPEN || v.probeGeneration != probeGeneration) {
                    log.debug("Ignore stale model circuit success: modelId={}, probeGeneration={}, state={}, activeGeneration={}",
                            id, probeGeneration, v.state, v.probeGeneration);
                    routingMetrics.recordCircuitEvent(id, "stale_success_ignored");
                    return v;
                }
            } else if (v.state == State.OPEN) {
                log.debug("Ignore model circuit success while OPEN: modelId={}, probeGeneration={}",
                        id, probeGeneration);
                routingMetrics.recordCircuitEvent(id, "success_ignored_open");
                return v; // 拦截陈旧的探针，防止污染刚刚重开的断路器
            }
            State previous = v.state;
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openUntil = 0L;
            v.halfOpenStartMs = 0L;
            if (previous != State.CLOSED || probeGeneration > 0L) {
                log.info("Model circuit CLOSED: modelId={}, probeGeneration={}", id, probeGeneration);
                routingMetrics.recordCircuitEvent(id, "closed");
            }
            return v;
        });
    }

    public void markFailure(String id) {
        markFailure(id, 0L);
    }

    public void markFailure(String id, long probeGeneration) {
        if (id == null) return;
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) v = new ModelHealth();
            if (probeGeneration > 0L
                    && (v.state != State.HALF_OPEN || v.probeGeneration != probeGeneration)) {
                log.debug("Ignore stale model circuit failure: modelId={}, probeGeneration={}, state={}, activeGeneration={}",
                        id, probeGeneration, v.state, v.probeGeneration);
                routingMetrics.recordCircuitEvent(id, "stale_failure_ignored");
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getHealth().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenStartMs = 0L;
                log.warn("Model circuit reopened after HALF_OPEN failure: modelId={}, probeGeneration={}, openDurationMs={}",
                        id, probeGeneration, properties.getHealth().getOpenDurationMs());
                routingMetrics.recordCircuitEvent(id, "reopened");
                return v;
            }
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= properties.getHealth().getFailureThreshold()) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getHealth().getOpenDurationMs();
                v.consecutiveFailures = 0;
                log.warn("Model circuit OPENED: modelId={}, openDurationMs={}",
                        id, properties.getHealth().getOpenDurationMs());
                routingMetrics.recordCircuitEvent(id, "opened");
            } else {
                log.debug("Model circuit failure counted: modelId={}, consecutiveFailures={}, threshold={}",
                        id, v.consecutiveFailures, properties.getHealth().getFailureThreshold());
            }
            return v;
        });
    }

    private static class ModelHealth {
        int consecutiveFailures = 0;
        long openUntil = 0L;
        /** 0 表示无探针飞行，>0 表示探针开始时间（用于超时兜底） */
        long halfOpenStartMs = 0L;
        long probeGeneration = 0L;
        State state = State.CLOSED;
    }

    public record CallPermit(boolean allowed, long generation) {
        static CallPermit denied() {
            return new CallPermit(false, 0L);
        }
    }

    public record HealthSnapshot(
            String state,
            int consecutiveFailures,
            long reopenInMs,
            long halfOpenStartMs,
            long probeGeneration) {
    }

    private long halfOpenFlightTimeoutMs() {
        return Math.max(1L, properties.getHealth().getHalfOpenFlightTimeoutMs());
    }

    private enum State { CLOSED, OPEN, HALF_OPEN }
}
