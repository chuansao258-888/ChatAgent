package com.yulong.chatagent.admin.model.request;

import lombok.Data;

@Data
public class UpdateChatRoutingCandidateOverrideRequest {
    private String candidateId;
    private Boolean enabled;
    private Integer priority;
    private Boolean supportsThinking;
    private String thinkingStrategy;
    private String thinkingModel;
}
