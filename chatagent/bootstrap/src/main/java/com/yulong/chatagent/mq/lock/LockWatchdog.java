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
 * MQ 锁看门狗。
 *
 * Agent 运行、文档入库都可能超过 Redis lock 的初始 TTL；
 * 如果不续租，任务还没跑完锁就过期，其他 consumer 可能误以为可以接手。
 * 所以消费者拿到锁后，会用这个类定时 renew task lock / session lock。
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
            // 返回空实现，方便调用方无脑 try-with-resources，不需要额外判空。
            return () -> {
            };
        }
        long intervalMs = properties.getLocks().getWatchdogIntervalMs();
        // scheduleWithFixedDelay 表示：上一次 renew 执行结束后，再等待 intervalMs 才执行下一次。
        // 比 fixedRate 更适合这里，因为 Redis 偶发慢请求不会导致续租任务堆积。
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> renewQuietly(taskLease, sessionLease),
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        // Registration.close() 会被 try-with-resources 自动调用，从而取消后续续租。
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
                    // 续租失败通常说明锁过期/被别人接手/token 不匹配；这里不抛出，避免打断业务线程。
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
                    // session lock 丢失会有并发风险，因此记录告警；真正的业务异常仍由 consumer 主流程处理。
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
        // AutoCloseable 让 watch(...) 可以写在 try-with-resources 里：
        // try (Registration ignored = watchdog.watch(...)) { processTask(); }
        @Override
        void close();
    }

    private static final class WatchdogThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mq-lock-watchdog-" + counter.getAndIncrement());
            // daemon 线程不会阻止 JVM 退出；应用关闭时 @PreDestroy 也会主动 shutdownNow。
            thread.setDaemon(true);
            return thread;
        }
    }
}
