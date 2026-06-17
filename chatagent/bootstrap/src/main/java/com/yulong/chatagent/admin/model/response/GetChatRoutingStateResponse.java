package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import lombok.Builder;
import lombok.Data;

/** Snapshot of chat-routing configuration plus live candidate and circuit-breaker state for admin visibility. */
@Data
@Builder
public class GetChatRoutingStateResponse {
    private String defaultModel;
    private String deepThinkingModel;
    private Integer firstPacketTimeoutSeconds;
    private Integer streamTotalTimeoutSeconds;
    private Integer httpConnectTimeoutSeconds;
    private Integer httpReadTimeoutSeconds;
    private String[] registeredModels;
    private String[] orphanOverrideCandidateIds;
    private ChatRoutingCandidateVO[] candidates;
}
