package com.yulong.chatagent.config;

import com.yulong.chatagent.trace.TraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-event-");
        executor.setTaskDecorator(traceTaskDecorator());
        executor.initialize();
        return executor;
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
}
