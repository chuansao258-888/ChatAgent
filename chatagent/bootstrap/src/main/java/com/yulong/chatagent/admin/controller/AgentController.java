package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.admin.application.AgentFacadeService;
import com.yulong.chatagent.admin.model.request.CreateAgentRequest;
import com.yulong.chatagent.admin.model.request.UpdateAgentRequest;
import com.yulong.chatagent.admin.model.response.CreateAgentResponse;
import com.yulong.chatagent.admin.model.response.GetAgentsResponse;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class AgentController {

    private final AgentFacadeService agentFacadeService;

    @GetMapping("/agents")
    public ApiResponse<GetAgentsResponse> getAgents() {
        return ApiResponse.success(agentFacadeService.getAgents());
    }

    @PostMapping("/agents")
    public ApiResponse<CreateAgentResponse> createAgent(@RequestBody CreateAgentRequest request) {
        return ApiResponse.success(agentFacadeService.createAgent(request));
    }

    @DeleteMapping("/agents/{agentId}")
    public ApiResponse<Void> deleteAgent(@PathVariable String agentId) {
        agentFacadeService.deleteAgent(agentId);
        return ApiResponse.success();
    }

    @PatchMapping("/agents/{agentId}")
    public ApiResponse<Void> updateAgent(@PathVariable String agentId, @RequestBody UpdateAgentRequest request) {
        agentFacadeService.updateAgent(agentId, request);
        return ApiResponse.success();
    }
}

