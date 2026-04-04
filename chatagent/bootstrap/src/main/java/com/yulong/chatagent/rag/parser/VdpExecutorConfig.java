package com.yulong.chatagent.rag.parser;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated executor for visual parsing so overload never spills back into caller threads.
 */
@Configuration
public class VdpExecutorConfig {

    @Bean("vdpExecutor")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "chatagent.rag.vdp.vlm",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public Executor vdpExecutor(VlmVdpProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), properties.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(0, properties.getQueueCapacity()));
        executor.setThreadNamePrefix("vdp-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("vdpPageDispatchExecutor")
    public Executor vdpPageDispatchExecutor(
            @org.springframework.beans.factory.annotation.Value("${chatagent.rag.vdp.pdf-page-dispatch-core-pool-size:2}") int corePoolSize,
            @org.springframework.beans.factory.annotation.Value("${chatagent.rag.vdp.pdf-page-dispatch-max-pool-size:2}") int maxPoolSize,
            @org.springframework.beans.factory.annotation.Value("${chatagent.rag.vdp.pdf-page-dispatch-queue-capacity:0}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("vdp-page-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("vdpBatchExecutor")
    public Executor vdpBatchExecutor(
            @org.springframework.beans.factory.annotation.Value("${chatagent.rag.vdp.pdf-batch-core-pool-size:1}") int corePoolSize,
            @org.springframework.beans.factory.annotation.Value("${chatagent.rag.vdp.pdf-batch-max-pool-size:1}") int maxPoolSize,
            @org.springframework.beans.factory.annotation.Value("${chatagent.rag.vdp.pdf-batch-queue-capacity:0}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("vdp-batch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
