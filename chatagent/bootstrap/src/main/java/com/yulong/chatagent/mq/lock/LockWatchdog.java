package com.yulong.chatagent.mq.lock;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renews RUNNING task locks so long-running MQ consumers keep ownership until they finish.
 */
@Component
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
@Slf4j
public class LockWatchdog {

    private final ScheduledExecutorService scheduler;
    private final DistributedLockManager distributedLockManager;
    private final ChatAgentMqProperties properties;

    @Autowired
    public LockWatchdog(DistributedLockManager distributedLockManager, ChatAgentMqProperties properties) {
        this(distributedLockManager, properties, Executors.newScheduledThreadPool(2, new WatchdogThreadFactory()));
    }

    LockWatchdog(DistributedLockManager distributedLockManager,
                 ChatAgentMqProperties properties,
                 ScheduledExecutorService scheduler) {
        this.distributedLockManager = distributedLockManager;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    public Registration watch(MqTaskLockLease lease) {
        return watch(lease, null);
    }

    public Registration watch(MqTaskLockLease taskLease, MqSessionExecLockLease sessionLease) {
        if (taskLease == null && sessionLease == null) {
            return () -> {
            };
        }
        long intervalMs = properties.getLocks().getWatchdogIntervalMs();
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> renewQuietly(taskLease, sessionLease),
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        return () -> future.cancel(false);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void renewQuietly(MqTaskLockLease taskLease, MqSessionExecLockLease sessionLease) {
        if (taskLease != null) {
            try {
                if (!distributedLockManager.renew(taskLease)) {
                    log.warn("MQ task lock watchdog lost ownership: taskType={}, idempotencyKey={}",
                            taskLease.identity().taskType(), taskLease.identity().idempotencyKey());
                }
            } catch (Exception e) {
                log.warn("MQ task lock watchdog renewal failed: taskType={}, idempotencyKey={}, error={}",
                        taskLease.identity().taskType(),
                        taskLease.identity().idempotencyKey(),
                        e.getMessage());
            }
        }
        if (sessionLease != null) {
            try {
                if (!distributedLockManager.renewSessionExecLock(sessionLease)) {
                    log.warn("Session exec lock watchdog lost ownership: sessionId={}", sessionLease.sessionId());
                }
            } catch (Exception e) {
                log.warn("Session exec lock watchdog renewal failed: sessionId={}, error={}",
                        sessionLease.sessionId(),
                        e.getMessage());
            }
        }
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class WatchdogThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mq-lock-watchdog-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
