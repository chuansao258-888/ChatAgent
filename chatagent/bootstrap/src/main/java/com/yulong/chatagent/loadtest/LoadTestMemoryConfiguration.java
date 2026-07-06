package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.memory.application.UserMemoryIndexService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Provides a no-op {@link UserMemoryIndexService} under the {@code load-test}
 * profile so the backend starts without Milvus or L3 memory.
 *
 * <p>The plain-chat load-test path does not exercise RAG or long-term-memory
 * recall, so the Milvus-backed index is unnecessary. The production
 * {@code NoOpUserMemoryIndexService} uses {@code @ConditionalOnMissingBean} on
 * the interface it implements, which does not reliably activate when the Milvus
 * bean is also guarded; this explicit profile bean removes the ambiguity for the
 * load-test profile only.</p>
 */
@Profile("load-test")
@Configuration
public class LoadTestMemoryConfiguration {

    @Bean
    UserMemoryIndexService loadTestUserMemoryIndexService() {
        return new NoOpUserMemoryIndexServiceBean();
    }
}
