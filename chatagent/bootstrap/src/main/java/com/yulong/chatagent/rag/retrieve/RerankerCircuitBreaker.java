package com.yulong.chatagent.rag.retrieve;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight circuit breaker for reranker HTTP calls.
 * Uses a simple sliding window approach to calculate failure rate.
 */
@Slf4j
public class RerankerCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private static final int BUCKET_COUNT = 10;
    private static final long BUCKET_WIDTH_MS = 10_000L;
    private static final long WINDOW_MS = BUCKET_COUNT * BUCKET_WIDTH_MS;

    private final String provider;
    private final int failureThreshold;
    private final int failureRateThresholdPercent;
    private final int minimumRequestVolume;
    private final long openStateMs;
    private final int halfOpenProbeCount;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong lastOpenedAt = new AtomicLong(0);
    private final AtomicInteger halfOpenRequests = new AtomicInteger(0);
    private final Bucket[] buckets;

    public RerankerCircuitBreaker(String provider, RerankerProperties properties) {
        this.provider = provider;
        this.failureThreshold = properties.getFailureThreshold();
        this.failureRateThresholdPercent = properties.getFailureRateThresholdPercent();
        this.minimumRequestVolume = properties.getMinimumRequestVolume();
        this.openStateMs = properties.getOpenStateMs();
        this.halfOpenProbeCount = properties.getHalfOpenProbeCount();

        this.buckets = new Bucket[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new Bucket();
        }
    }

    public boolean allowRequest() {
        while (true) {
            State currentState = state.get();

            if (currentState == State.OPEN) {
                if (System.currentTimeMillis() - lastOpenedAt.get() < openStateMs) {
                    return false;
                }
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("Reranker circuit breaker entering HALF_OPEN state: provider={}", provider);
                } else {
                    continue;
                }
                currentState = State.HALF_OPEN;
            }

            if (currentState == State.HALF_OPEN) {
                return tryAcquireHalfOpenProbe();
            }

            return true;
        }
    }

    public void recordSuccess() {
        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                log.info("Reranker circuit breaker CLOSED: provider={}", provider);
                resetWindow();
            }
            return;
        }

        if (currentState == State.CLOSED) {
            getCurrentBucket().recordSuccess(System.currentTimeMillis());
        }
    }

    public void recordFailure() {
        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            openCircuit();
            return;
        }

        if (currentState == State.CLOSED) {
            Bucket bucket = getCurrentBucket();
            bucket.recordFailure(System.currentTimeMillis());
            checkWindowAndTrip();
        }
    }

    public State getState() {
        return state.get();
    }

    public double stateMetricValue() {
        return switch (state.get()) {
            case CLOSED -> 0.0d;
            case HALF_OPEN -> 1.0d;
            case OPEN -> 2.0d;
        };
    }

    private boolean tryAcquireHalfOpenProbe() {
        while (true) {
            int current = halfOpenRequests.get();
            if (current >= halfOpenProbeCount) {
                return false;
            }
            if (halfOpenRequests.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void checkWindowAndTrip() {
        long totalRequests = 0;
        long totalFailures = 0;
        long now = System.currentTimeMillis();

        for (Bucket bucket : buckets) {
            BucketSnapshot snapshot = bucket.snapshot(now);
            totalRequests += snapshot.totalRequests();
            totalFailures += snapshot.failures();
        }

        double failureRate = totalRequests == 0 ? 0.0d : (totalFailures * 100.0d) / totalRequests;
        if (totalRequests >= minimumRequestVolume
                && totalFailures >= failureThreshold
                && failureRate >= failureRateThresholdPercent) {
            openCircuit();
        }
    }

    private void openCircuit() {
        lastOpenedAt.set(System.currentTimeMillis());
        halfOpenRequests.set(0);
        if (state.getAndSet(State.OPEN) != State.OPEN) {
            log.warn("Reranker circuit breaker OPENED: provider={}", provider);
        }
    }

    private void resetWindow() {
        halfOpenRequests.set(0);
        for (Bucket bucket : buckets) {
            bucket.clear();
        }
    }

    private Bucket getCurrentBucket() {
        long now = System.currentTimeMillis();
        int index = (int) ((now / BUCKET_WIDTH_MS) % BUCKET_COUNT);
        return buckets[index];
    }

    private static final class Bucket {
        private long timestamp = 0;
        private int success = 0;
        private int failure = 0;

        synchronized void recordSuccess(long now) {
            rotate(now);
            success++;
        }

        synchronized void recordFailure(long now) {
            rotate(now);
            failure++;
        }

        synchronized BucketSnapshot snapshot(long now) {
            if (timestamp == 0 || now - timestamp >= WINDOW_MS) {
                return BucketSnapshot.EMPTY;
            }
            return new BucketSnapshot(success + failure, failure);
        }

        synchronized void clear() {
            timestamp = 0;
            success = 0;
            failure = 0;
        }

        private void rotate(long now) {
            if (timestamp == 0 || now - timestamp >= BUCKET_WIDTH_MS) {
                timestamp = now;
                success = 0;
                failure = 0;
            }
        }
    }

    private record BucketSnapshot(int totalRequests, int failures) {
        private static final BucketSnapshot EMPTY = new BucketSnapshot(0, 0);
    }
}
