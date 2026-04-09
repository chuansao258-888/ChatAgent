package com.yulong.chatagent.chat.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "chat.routing")
public class ChatRoutingProperties {

    private String defaultModel = "deepseek-chat";
    private String deepThinkingModel;
    private int firstPacketTimeoutSeconds = 60;
    private int streamTotalTimeoutSeconds = 300;
    private int httpConnectTimeoutSeconds = 10;
    private int httpReadTimeoutSeconds = 65;

    private HealthConfig health = new HealthConfig();
    private ObservabilityConfig observability = new ObservabilityConfig();
    private List<CandidateConfig> candidates = new ArrayList<>();

    @Data
    public static class CandidateConfig {
        private String id;
        /** 对应 ChatClientRegistry 中的 bean key */
        private String springClientKey;
        private Integer priority = 100;
        private Boolean enabled = true;
        /**
         * Optional explicit override. When null, provider capability auto-discovery is used.
         */
        private Boolean supportsThinking;
        /**
         * NONE | ZHIPU_THINKING_FLAG | MODEL_OVERRIDE
         */
        private String thinkingStrategy = "NONE";
        /**
         * Optional provider model override to use when deepThinking=true and strategy=MODEL_OVERRIDE.
         */
        private String thinkingModel;
    }

    @Data
    public static class HealthConfig {
        private int failureThreshold = 3;
        private long openDurationMs = 300_000L;
        private long halfOpenFlightTimeoutMs = 120_000L;
    }

    @Data
    public static class ObservabilityConfig {
        private double openCircuitWarningRatio = 0.5D;
        private boolean downWhenNoRoutableCandidates = true;
        private boolean outOfServiceWhenAllRoutableCandidatesOpen = true;
        private boolean warnOnOrphanOverrides = true;
    }
}
