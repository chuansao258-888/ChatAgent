package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.ChatRoutingCandidateVO;
import lombok.Builder;
import lombok.Data;

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
