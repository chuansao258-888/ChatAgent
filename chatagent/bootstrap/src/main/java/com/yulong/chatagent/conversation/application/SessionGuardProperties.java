package com.yulong.chatagent.conversation.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for request-entry session locking.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.session-guard")
@Data
public class SessionGuardProperties {

    private boolean enabled = true;
    private int ttlMs = 120_000;
    private boolean failOpen = true;
}
