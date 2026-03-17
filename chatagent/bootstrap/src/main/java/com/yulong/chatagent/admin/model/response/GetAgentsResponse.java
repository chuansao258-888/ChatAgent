package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.AgentVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetAgentsResponse {
    private AgentVO[] agents;
}

