package com.yulong.chatagent.intent.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Operational rollout properties. Numeric decision values live only in the frozen profile. */
@Component
@ConfigurationProperties(prefix = "chatagent.intent.decision-policy")
@Data
public class IntentPolicyProperties {

    private IntentPolicyMode mode = IntentPolicyMode.SHADOW;
    private String profileVersion = "v1";
    private int recentContextTurns = 4;
    private int recentContextMaxChars = 4000;
    private int maxClarificationAttempts = 2;

    public int boundedRecentContextTurns() {
        return Math.max(recentContextTurns, 0);
    }

    public int boundedRecentContextMaxChars() {
        return Math.max(recentContextMaxChars, 0);
    }

    public int boundedMaxClarificationAttempts() {
        return Math.max(maxClarificationAttempts, 1);
    }
}
