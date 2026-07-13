package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.memory.application.UserMemoryIndexService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Test-only memory wiring for routing resilience runs that intentionally disable L3. */
@Profile("resilience-test")
@Configuration
public class ResilienceTestMemoryConfiguration {

    @Bean
    UserMemoryIndexService resilienceTestUserMemoryIndexService() {
        return new CapacityNoOpUserMemoryIndexService();
    }
}
