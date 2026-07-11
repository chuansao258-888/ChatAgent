package com.yulong.chatagent.conversation.application;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared local session-run serialization coordinator.
 *
 * <p>Guarantees that same-session Agent turns do not overlap on the local
 * (MQ-disabled) dispatch path. Uses a per-session JVM Semaphore (permits=1)
 * so only one turn per session runs at a time, while different sessions
 * remain concurrent up to the global capacity limit.</p>
 *
 * <p>The MQ path uses Redis first and reuses this coordinator for the configured
 * local fallback. Lease ownership prevents double release and idle entries are
 * removed after the last waiter/owner leaves.</p>
 */
@Slf4j
@Component
public class SessionRunCoordinator {

    private final SessionRunProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, LockEntry> sessionLocks = new ConcurrentHashMap<>();

    @Autowired
    public SessionRunCoordinator(SessionRunProperties properties, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(properties, meterRegistryProvider.getIfAvailable());
    }

    public SessionRunCoordinator(SessionRunProperties properties) {
        this(properties, (MeterRegistry) null);
    }

    SessionRunCoordinator(SessionRunProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Acquire the per-session run lock. Blocks up to {@code ACQUIRE_TIMEOUT_SECONDS}
     * if another turn for the same session is running.
     *
     * @param sessionId the session to serialize
     * @return true if the lock was acquired; false if it timed out
     */
    public RunLease acquire(String sessionId) {
        if (!properties.isLocalSerializationEnabled() || sessionId == null || sessionId.isBlank()) {
            return RunLease.noop();
        }
        LockEntry entry = sessionLocks.compute(sessionId, (key, current) -> {
            LockEntry selected = current == null ? new LockEntry() : current;
            selected.references.incrementAndGet();
            return selected;
        });
        if (entry.semaphore.availablePermits() == 0) {
            increment("chatagent.session.run.wait", "path", "local");
        }
        try {
            boolean acquired = entry.semaphore.tryAcquire(properties.getLocalAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                releaseReference(sessionId, entry);
                increment("chatagent.session.run.timeout", "path", "local");
                log.warn("Session run lock timed out after {}ms: sessionId={}",
                        properties.getLocalAcquireTimeoutMs(), sessionId);
                return null;
            }
            return new RunLease(() -> {
                entry.semaphore.release();
                releaseReference(sessionId, entry);
            });
        } catch (InterruptedException e) {
            releaseReference(sessionId, entry);
            Thread.currentThread().interrupt();
            log.warn("Session run lock interrupted: sessionId={}", sessionId);
            return null;
        }
    }

    /**
     * Release the per-session run lock.
     *
     * @param sessionId the session to release
     */
    public SessionRunProperties.RedisFailurePolicy redisFailurePolicy() {
        return properties.getRedisFailurePolicy();
    }

    public void recordRedisFailurePolicy(SessionRunProperties.RedisFailurePolicy policy) {
        increment("chatagent.session.run.redis.failure", "outcome", policy.name().toLowerCase());
    }

    int activeSessionCount() {
        return sessionLocks.size();
    }

    private void releaseReference(String sessionId, LockEntry entry) {
        sessionLocks.computeIfPresent(sessionId, (key, current) -> {
            if (current != entry) {
                return current;
            }
            return entry.references.decrementAndGet() == 0 ? null : entry;
        });
    }

    private void increment(String name, String tagName, String tagValue) {
        if (meterRegistry != null) {
            meterRegistry.counter(name, tagName, tagValue).increment();
        }
    }

    private static final class LockEntry {
        private final Semaphore semaphore = new Semaphore(1, true);
        private final AtomicInteger references = new AtomicInteger();
    }

    public static final class RunLease implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RunLease(Runnable release) {
            this.release = release;
        }

        private static RunLease noop() {
            return new RunLease(() -> { });
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
