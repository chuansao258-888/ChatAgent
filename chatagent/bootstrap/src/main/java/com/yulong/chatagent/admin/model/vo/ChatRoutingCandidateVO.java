package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

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
