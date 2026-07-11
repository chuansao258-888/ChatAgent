package com.yulong.chatagent.conversation.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Local-path session-run serialization coordinator.
 *
 * <p>Guarantees that same-session Agent turns do not overlap on the local
 * (MQ-disabled) dispatch path. Uses a per-session JVM Semaphore (permits=1)
 * so only one turn per session runs at a time, while different sessions
 * remain concurrent up to the global capacity limit.</p>
 *
 * <p>The MQ path already has Redis-based session-exec-lock via
 * {@code AbstractRetryingMqConsumer}; this coordinator covers only the
 * local path gap identified in Phase 6.</p>
 */
@Slf4j
@Component
public class SessionRunCoordinator {

    private static final int ACQUIRE_TIMEOUT_SECONDS = 120;

    private final Map<String, Semaphore> sessionLocks = new ConcurrentHashMap<>();

    /**
     * Acquire the per-session run lock. Blocks up to {@code ACQUIRE_TIMEOUT_SECONDS}
     * if another turn for the same session is running.
     *
     * @param sessionId the session to serialize
     * @return true if the lock was acquired; false if it timed out
     */
    public boolean acquire(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return true;
        }
        Semaphore lock = sessionLocks.computeIfAbsent(sessionId, k -> new Semaphore(1));
        try {
            boolean acquired = lock.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Session run lock timed out after {}s: sessionId={}", ACQUIRE_TIMEOUT_SECONDS, sessionId);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Session run lock interrupted: sessionId={}", sessionId);
            return false;
        }
    }

    /**
     * Release the per-session run lock.
     *
     * @param sessionId the session to release
     */
    public void release(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Semaphore lock = sessionLocks.get(sessionId);
        if (lock != null) {
            lock.release();
        }
    }
}
