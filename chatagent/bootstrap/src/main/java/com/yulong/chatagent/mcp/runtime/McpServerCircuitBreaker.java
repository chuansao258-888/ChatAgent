package com.yulong.chatagent.mcp.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Lightweight per-server circuit breaker tailored for MCP tool calls.
 */
public class McpServerCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final McpRuntimeProtectionProperties properties;
    private final LongSupplier currentTimeMsSupplier;
    private final Deque<CallSample> samples = new ArrayDeque<>();

    private State state = State.CLOSED;
    private long lastOpenedAtMs = 0L;
    private int halfOpenAttempts = 0;
    private int halfOpenSuccesses = 0;

    public McpServerCircuitBreaker(McpRuntimeProtectionProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    McpServerCircuitBreaker(McpRuntimeProtectionProperties properties, LongSupplier currentTimeMsSupplier) {
        this.properties = properties;
        this.currentTimeMsSupplier = currentTimeMsSupplier;
    }

    public synchronized boolean allowRequest() {
        if (!properties.isCircuitBreakerEnabled()) {
            return true;
        }
        long now = currentTimeMsSupplier.getAsLong();
        if (state == State.OPEN) {
            if (now - lastOpenedAtMs < properties.getCircuitBreakerOpenStateMs()) {
                return false;
            }
            state = State.HALF_OPEN;
            halfOpenAttempts = 0;
            halfOpenSuccesses = 0;
        }

        if (state == State.HALF_OPEN) {
            if (halfOpenAttempts >= Math.max(1, properties.getCircuitBreakerHalfOpenProbeCount())) {
                return false;
            }
            halfOpenAttempts++;
            return true;
        }

        return true;
    }

    public synchronized void recordSuccess(long durationMs) {
        if (!properties.isCircuitBreakerEnabled()) {
            return;
        }
        if (state == State.HALF_OPEN) {
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= Math.max(1, properties.getCircuitBreakerHalfOpenProbeCount())) {
                closeCircuit();
            }
            return;
        }
        addSample(true, durationMs);
        evaluateWindow();
    }

    public synchronized void recordFailure(long durationMs) {
        if (!properties.isCircuitBreakerEnabled()) {
            return;
        }
        if (state == State.HALF_OPEN) {
            openCircuit();
            return;
        }
        addSample(false, durationMs);
        evaluateWindow();
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized double stateMetricValue() {
        return switch (state) {
            case CLOSED -> 0.0d;
            case HALF_OPEN -> 1.0d;
            case OPEN -> 2.0d;
        };
    }

    private void addSample(boolean success, long durationMs) {
        boolean slow = durationMs >= properties.getCircuitBreakerSlowCallDurationMs();
        samples.addLast(new CallSample(success, slow));
        while (samples.size() > Math.max(1, properties.getCircuitBreakerSlidingWindowSize())) {
            samples.removeFirst();
        }
    }

    private void evaluateWindow() {
        int totalRequests = samples.size();
        if (totalRequests < Math.max(1, properties.getCircuitBreakerMinimumRequestVolume())) {
            return;
        }

        long failures = samples.stream().filter(sample -> !sample.success()).count();
        long slowCalls = samples.stream().filter(CallSample::slow).count();
        double failureRate = (failures * 100.0d) / totalRequests;
        double slowRate = (slowCalls * 100.0d) / totalRequests;

        if ((failures >= Math.max(1, properties.getCircuitBreakerFailureThreshold())
                && failureRate >= properties.getCircuitBreakerFailureRateThresholdPercent())
                || slowRate >= properties.getCircuitBreakerSlowCallRateThresholdPercent()) {
            openCircuit();
        }
    }

    private void openCircuit() {
        state = State.OPEN;
        lastOpenedAtMs = currentTimeMsSupplier.getAsLong();
        halfOpenAttempts = 0;
        halfOpenSuccesses = 0;
    }

    private void closeCircuit() {
        state = State.CLOSED;
        halfOpenAttempts = 0;
        halfOpenSuccesses = 0;
        samples.clear();
    }

    private record CallSample(boolean success, boolean slow) {
    }
}
