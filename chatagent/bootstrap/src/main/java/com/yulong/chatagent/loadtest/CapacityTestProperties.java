package com.yulong.chatagent.loadtest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Latency knobs for the in-process stub LLM providers active under the
 * {@code capacity-test} profile.
 *
 * <p>These simulate realistic model latency so the measured throughput reflects
 * application/infrastructure capacity under an assumed model cost, not a real
 * provider's variance. Sleeping occupies the {@code agent-concurrency} slot
 * for the turn, which is exactly what a real model call does.</p>
 */
@Component
@Profile("capacity-test")
@ConfigurationProperties(prefix = "chatagent.capacity-test")
@Data
public class CapacityTestProperties {

    /**
     * Wait in milliseconds before the stub emits its first content chunk
     * (time-to-first-token). Defaults to a realistic ~400 ms.
     */
    private long mockTtftMs = 400L;

    /**
     * Streaming window in milliseconds AFTER the first chunk, during which the
     * remaining content chunks are spaced. The stub round-trip is therefore
     * {@code mockTtftMs + mockStreamTotalMs} (default ~1.2 s). This is NOT the
     * full round-trip on its own.
     */
    private long mockStreamTotalMs = 800L;
}
