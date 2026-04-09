package com.yulong.chatagent.config;

import com.yulong.chatagent.trace.TraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
/**
 * Configures asynchronous task execution for application events and background work.
 *
 * <p>The task decorator preserves the request trace ID so async log lines can
 * still be correlated with the originating request.</p>
 */
public class AsyncConfig {

    @Bean
    public Executor taskExecutor() {
        return buildExecutor(4, 10, 100, "async-event-", null);
    }

    @Bean("summaryExecutor")
    public Executor summaryExecutor() {
        return buildExecutor(1, 2, 8, "summary-task-", new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Bean("modelStreamExecutor")
    public Executor modelStreamExecutor() {
        return buildExecutor(20, 100, 200, "LlmStream-", new ThreadPoolExecutor.AbortPolicy());
    }

    private TaskDecorator traceTaskDecorator() {
        return runnable -> {
            String traceId = TraceContext.getTraceId();
            return () -> {
                // Preserve any previous async trace state in case async work is nested.
                String previousTraceId = TraceContext.getTraceId();
                if (traceId != null) {
                    TraceContext.setTraceId(traceId);
                } else {
                    TraceContext.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previousTraceId != null) {
                        TraceContext.setTraceId(previousTraceId);
                    } else {
                        TraceContext.clear();
                    }
                }
            };
        };
    }

    private Executor buildExecutor(int corePoolSize,
                                   int maxPoolSize,
                                   int queueCapacity,
                                   String threadNamePrefix,
                                   RejectedExecutionHandler rejectedExecutionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setTaskDecorator(traceTaskDecorator());
        if (rejectedExecutionHandler != null) {
            executor.setRejectedExecutionHandler(rejectedExecutionHandler);
        }
        executor.initialize();
        return executor;
    }
}
