package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.memory.application.UserMemoryIndexService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Provides a no-op {@link UserMemoryIndexService} under the {@code capacity-test}
 * profile so the backend starts without Milvus or L3 memory.
 *
 * <p>The plain-chat capacity-test path does not exercise RAG or long-term-memory
 * recall, so the Milvus-backed index is unnecessary. The production
 * {@code NoOpUserMemoryIndexService} uses {@code @ConditionalOnMissingBean} on
 * the interface it implements, which does not reliably activate when the Milvus
 * bean is also guarded; this explicit profile bean removes the ambiguity for the
 * capacity-test profile only.</p>
 */
@Profile("capacity-test")
@Configuration
public class CapacityTestMemoryConfiguration {

    @Bean
    UserMemoryIndexService capacityTestUserMemoryIndexService() {
        return new CapacityNoOpUserMemoryIndexService();
    }

    @Bean
    @Profile("resilience-test")
    Object rejectCombinedCapacityAndResilienceProfiles() {
        throw new IllegalStateException(
                "capacity-test and resilience-test profiles are mutually exclusive");
    }
}
