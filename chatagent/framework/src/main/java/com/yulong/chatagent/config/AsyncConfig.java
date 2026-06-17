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

/**
 * Configures the thread-pool executors used for asynchronous work.
 *
 * <p>Every pool is wrapped with a {@link TaskDecorator} that propagates the
 * request trace ID onto worker threads, so async log lines can still be
 * correlated with the originating request.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * General-purpose executor for application events and lightweight background work.
     *
     * @return bounded async executor
     */
    @Bean
    public Executor taskExecutor() {
        return buildExecutor(4, 10, 100, "async-event-", null);
    }

    /**
     * Single-concurrency executor for conversation summary generation.
     *
     * <p>Uses a discard-oldest rejection policy so a burst of summary tasks does
     * not build an unbounded backlog.</p>
     *
     * @return summary executor
     */
    @Bean("summaryExecutor")
    public Executor summaryExecutor() {
        return buildExecutor(1, 2, 8, "summary-task-", new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /**
     * High-throughput executor for streaming model responses back to clients.
     *
     * <p>Uses an abort rejection policy so back-pressure surfaces immediately
     * instead of being silently queued when the pool is saturated.</p>
     *
     * @return model-streaming executor
     */
    @Bean("modelStreamExecutor")
    public Executor modelStreamExecutor() {
        return buildExecutor(20, 100, 200, "LlmStream-", new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Single-concurrency executor for L3 memory promotion work.
     *
     * @return L3 promotion executor
     */
    @Bean("l3Executor")
    public Executor l3Executor() {
        return buildExecutor(1, 2, 8, "l3-promote-", new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /**
     * Builds a task decorator that snapshots the current trace ID and restores it
     * (or clears it) on the worker thread, preserving any prior async trace state
     * for nested execution.
     *
     * @return trace-propagating task decorator
     */
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

    /**
     * Builds a {@link ThreadPoolTaskExecutor} with the supplied sizing and an
     * optional rejection policy, applying the trace task decorator.
     *
     * @param corePoolSize              core pool size
     * @param maxPoolSize               maximum pool size
     * @param queueCapacity             bounded queue capacity
     * @param threadNamePrefix          worker thread name prefix
     * @param rejectedExecutionHandler  rejection policy, or {@code null} for the default
     * @return initialized executor
     */
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
