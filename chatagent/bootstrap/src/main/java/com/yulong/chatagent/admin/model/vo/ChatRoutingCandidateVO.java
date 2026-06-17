package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Admin-facing view of one routing candidate: configured vs. effective settings (after runtime
 * override), client registration status, and live circuit-breaker state.
 */
@Data
@Builder
public class ChatRoutingCandidateVO {
    private String id;
    private String springClientKey;
    private Boolean runtimeOverrideActive;
    private Boolean configuredEnabled;
    private Boolean effectiveEnabled;
    private Integer configuredPriority;
    private Integer effectivePriority;
    private Boolean configuredSupportsThinking;
    private Boolean effectiveSupportsThinking;
    private String configuredThinkingStrategy;
    private String effectiveThinkingStrategy;
    private String configuredThinkingModel;
    private String effectiveThinkingModel;
    private Boolean registered;
    private String circuitState;
    private Integer consecutiveFailures;
    private Long reopenInMs;
    private Long halfOpenStartMs;
    private Long probeGeneration;
}
