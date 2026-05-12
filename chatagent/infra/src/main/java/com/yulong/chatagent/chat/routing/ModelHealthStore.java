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
    // 每个模型 id 独立维护一份断路器状态，避免一个模型故障影响其他模型。
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();
    private RoutingMetrics routingMetrics = RoutingMetrics.noop();

    @Autowired(required = false)
    void setRoutingMetrics(RoutingMetrics routingMetrics) {
        if (routingMetrics != null) {
            this.routingMetrics = routingMetrics;
        }
    }

    // 旧兼容入口已停用：当前路由必须使用 tryAcquire(id)，这样 HALF_OPEN 探针 generation
    // 可以被 markSuccess/markFailure 带回，避免旧探针结果污染新的健康状态。
    // public boolean allowCall(String id) {
    //     return tryAcquire(id).allowed();
    // }

    public CallPermit tryAcquire(String id) {
        if (id == null) return CallPermit.denied();
        long now = System.currentTimeMillis();
        // compute lambda 里不能直接修改普通局部变量，因此用一格数组把结果带出来。
        final boolean[] allowed = {false};
        final long[] generation = {0L};
        // compute 对同一个 key 的更新是原子的，避免并发请求同时修改同一模型健康状态。
        healthById.compute(id, (k, v) -> {
            if (v == null) v = new ModelHealth();
            if (v.state == State.OPEN) {
                // OPEN 且冷却期未结束：直接拒绝调用，让路由尝试下一个候选。
                if (v.openUntil > now) {
                    log.debug("Model circuit call denied: modelId={}, state={}, reopenInMs={}",
                            id, v.state, v.openUntil - now);
                    routingMetrics.recordCircuitDecision(id, "denied_open");
                    return v;
                }
                // OPEN 冷却结束后，不是立刻完全恢复，而是进入 HALF_OPEN。
                // 只放行一个真实业务请求作为探针。
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
                    // 旧探针可能还卡在网络里；这里发新一代探针，并用 generation 防止旧结果污染状态。
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
                    // HALF_OPEN 同一时间只允许一个探针飞行，其他请求跳过该模型。
                    log.debug("Model circuit call denied: modelId={}, state={}, activeGeneration={}",
                            id, v.state, v.probeGeneration);
                    routingMetrics.recordCircuitDecision(id, "denied_half_open");
                    return v; // 仍在飞行中，拒绝
                }
                // 理论兜底：HALF_OPEN 但没有记录探针开始时间，则补发一个探针。
                v.halfOpenStartMs = now;
                v.probeGeneration++;
                allowed[0] = true;
                generation[0] = v.probeGeneration;
                log.info("Model circuit HALF_OPEN probe admitted: modelId={}, generation={}", id, v.probeGeneration);
                routingMetrics.recordCircuitEvent(id, "half_open_probe_admitted");
                return v;
            }
            // CLOSED 是正常状态，请求直接放行；generation=0 表示不是 HALF_OPEN 探针。
            allowed[0] = true;
            return v;
        });
        return new CallPermit(allowed[0], generation[0]);
    }

    public Map<String, HealthSnapshot> snapshot() {
        long now = System.currentTimeMillis();
        // 管理/健康检查只需要不可变快照，不暴露内部可变 ModelHealth。
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
        // generation=0 表示普通 CLOSED 状态下的成功。
        markSuccess(id, 0L);
    }

    public void markSuccess(String id, long probeGeneration) {
        if (id == null) return;
        healthById.compute(id, (k, v) -> {
            if (v == null) return new ModelHealth();
            if (probeGeneration > 0L) {
                // HALF_OPEN 探针成功只有在“当前状态仍是 HALF_OPEN 且 generation 匹配”时才有效。
                // 否则说明这是过期探针返回，不能关闭当前断路器。
                if (v.state != State.HALF_OPEN || v.probeGeneration != probeGeneration) {
                    log.debug("Ignore stale model circuit success: modelId={}, probeGeneration={}, state={}, activeGeneration={}",
                            id, probeGeneration, v.state, v.probeGeneration);
                    routingMetrics.recordCircuitEvent(id, "stale_success_ignored");
                    return v;
                }
            } else if (v.state == State.OPEN) {
                // 普通请求的迟到成功不允许在 OPEN 状态下关闭断路器。
                // OPEN 只能由冷却后的 HALF_OPEN 探针成功来恢复。
                log.debug("Ignore model circuit success while OPEN: modelId={}, probeGeneration={}",
                        id, probeGeneration);
                routingMetrics.recordCircuitEvent(id, "success_ignored_open");
                return v; // 拦截陈旧的探针，防止污染刚刚重开的断路器
            }
            // 成功会把模型恢复到 CLOSED，并清空失败计数和 OPEN/HALF_OPEN 的临时状态。
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
        // generation=0 表示普通调用失败。
        markFailure(id, 0L);
    }

    public void markFailure(String id, long probeGeneration) {
        if (id == null) return;
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) v = new ModelHealth();
            // 带 generation 的失败必须来自当前 HALF_OPEN 探针；旧探针失败直接忽略。
            if (probeGeneration > 0L
                    && (v.state != State.HALF_OPEN || v.probeGeneration != probeGeneration)) {
                log.debug("Ignore stale model circuit failure: modelId={}, probeGeneration={}, state={}, activeGeneration={}",
                        id, probeGeneration, v.state, v.probeGeneration);
                routingMetrics.recordCircuitEvent(id, "stale_failure_ignored");
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                // HALF_OPEN 探针失败，说明模型还没恢复，重新 OPEN 并进入下一轮冷却。
                v.state = State.OPEN;
                v.openUntil = now + properties.getHealth().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenStartMs = 0L;
                log.warn("Model circuit reopened after HALF_OPEN failure: modelId={}, probeGeneration={}, openDurationMs={}",
                        id, probeGeneration, properties.getHealth().getOpenDurationMs());
                routingMetrics.recordCircuitEvent(id, "reopened");
                return v;
            }
            // CLOSED 状态下普通失败先累加；达到阈值才真正 OPEN。
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
        // CLOSED 状态连续失败计数；成功会清零。
        int consecutiveFailures = 0;
        // OPEN 截止时间戳；now 小于它时直接拒绝调用。
        long openUntil = 0L;
        /** 0 表示无探针飞行，>0 表示探针开始时间（用于超时兜底） */
        long halfOpenStartMs = 0L;
        // HALF_OPEN 探针代数，用于识别迟到的旧探针结果。
        long probeGeneration = 0L;
        // 默认 CLOSED：模型健康，可正常调用。
        State state = State.CLOSED;
    }

    /**
     * tryAcquire 返回给调用方的“调用许可”。
     *
     * <p>allowed 表示是否可以调用；generation=0 表示普通调用，
     * generation>0 表示这是第 N 代 HALF_OPEN 探针，后续 markSuccess/markFailure 必须带回它。</p>
     */
    public record CallPermit(boolean allowed, long generation) {
        static CallPermit denied() {
            return new CallPermit(false, 0L);
        }
    }

    /** 暴露给管理端/健康检查的只读健康快照。 */
    public record HealthSnapshot(
            String state,
            int consecutiveFailures,
            long reopenInMs,
            long halfOpenStartMs,
            long probeGeneration) {
    }

    private long halfOpenFlightTimeoutMs() {
        // 配置异常时至少给 1ms，避免 0 或负数导致探针立即反复重发。
        return Math.max(1L, properties.getHealth().getHalfOpenFlightTimeoutMs());
    }

    /** CLOSED=正常，OPEN=熔断冷却中，HALF_OPEN=冷却后允许一个探针验证恢复。 */
    private enum State { CLOSED, OPEN, HALF_OPEN }
}
