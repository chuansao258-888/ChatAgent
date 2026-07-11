package com.yulong.chatagent.conversation.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chatagent.agent.session-run")
@Data
public class SessionRunProperties {

    private boolean localSerializationEnabled = true;
    private long localAcquireTimeoutMs = 120_000L;
    private RedisFailurePolicy redisFailurePolicy = RedisFailurePolicy.LOCAL_FALLBACK;

    public enum RedisFailurePolicy {
        LOCAL_FALLBACK,
        WAIT,
        REJECT,
        FAIL_FAST
    }
}
