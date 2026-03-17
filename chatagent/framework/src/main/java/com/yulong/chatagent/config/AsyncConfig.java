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
