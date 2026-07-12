package com.yulong.chatagent.load;

import io.gatling.javaapi.core.Session;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe lifecycle accounting for one Gatling JVM.
 *
 * <p>Successful completion is recorded only from a non-failed Gatling session,
 * so a failed HTTP/SSE check cannot be converted into an E2E success sample.</p>
 */
final class TurnOutcomeRecorder {

    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong SUCCESSFUL = new AtomicLong();
    private static final AtomicLong TERMINAL_FAILED = new AtomicLong();
    private static final AtomicLong TIMED_OUT = new AtomicLong();
    private static final AtomicLong INTERRUPTED = new AtomicLong();
    private static final AtomicLong INVALID_SUCCESS_AFTER_FAILED_CHECK = new AtomicLong();
    private static final ConcurrentLinkedQueue<Long> SUCCESS_DURATIONS = new ConcurrentLinkedQueue<>();

    private TurnOutcomeRecorder() {
    }

    static void reset() {
        SUBMITTED.set(0L);
        SUCCESSFUL.set(0L);
        TERMINAL_FAILED.set(0L);
        TIMED_OUT.set(0L);
        INTERRUPTED.set(0L);
        INVALID_SUCCESS_AFTER_FAILED_CHECK.set(0L);
        SUCCESS_DURATIONS.clear();
    }

    static Session submitted(Session session) {
        SUBMITTED.incrementAndGet();
        return session;
    }

    static Session successful(Session session, long durationNanos) {
        if (session.isFailed()) {
            INVALID_SUCCESS_AFTER_FAILED_CHECK.incrementAndGet();
            return session;
        }
        SUCCESSFUL.incrementAndGet();
        if (durationNanos > 0L) {
            SUCCESS_DURATIONS.add(durationNanos);
        }
        return session;
    }

    static void terminalFailed() {
        TERMINAL_FAILED.incrementAndGet();
    }

    static void timedOut() {
        TIMED_OUT.incrementAndGet();
    }

    static void interrupted() {
        INTERRUPTED.incrementAndGet();
    }

    static Snapshot snapshot(long finalInFlight) {
        return new Snapshot(
                SUBMITTED.get(),
                SUCCESSFUL.get(),
                TERMINAL_FAILED.get(),
                TIMED_OUT.get(),
                INTERRUPTED.get(),
                finalInFlight,
                INVALID_SUCCESS_AFTER_FAILED_CHECK.get());
    }

    record Snapshot(long submitted,
                    long successful,
                    long terminalFailed,
                    long timedOut,
                    long interrupted,
                    long finalInFlight,
                    long invalidSuccessAfterFailedCheck) {

        boolean reconciled() {
            return submitted == successful + terminalFailed + timedOut + interrupted + finalInFlight;
        }
    }
}
