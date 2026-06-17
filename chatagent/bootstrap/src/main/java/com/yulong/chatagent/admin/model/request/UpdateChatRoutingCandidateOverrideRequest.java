package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/** Request body for an admin upserting a runtime override on one routing candidate (null fields are ignored). */
@Data
public class UpdateChatRoutingCandidateOverrideRequest {
    private String candidateId;
    private Boolean enabled;
    private Integer priority;
    private Boolean supportsThinking;
    private String thinkingStrategy;
    private String thinkingModel;
}
