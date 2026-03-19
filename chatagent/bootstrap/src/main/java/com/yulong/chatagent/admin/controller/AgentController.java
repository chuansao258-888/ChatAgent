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

/**
 * REST controller for administrator agent management.
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class AgentController {

    private final AgentFacadeService agentFacadeService;

    /**
     * Lists all agents.
     *
     * @return agent list response
     */
    @GetMapping("/agents")
    public ApiResponse<GetAgentsResponse> getAgents() {
        return ApiResponse.success(agentFacadeService.getAgents());
    }

    /**
     * Creates a new agent.
     *
     * @param request create agent request
     * @return created agent response
     */
    @PostMapping("/agents")
    public ApiResponse<CreateAgentResponse> createAgent(@RequestBody CreateAgentRequest request) {
        return ApiResponse.success(agentFacadeService.createAgent(request));
    }

    /**
     * Deletes an agent.
     *
     * @param agentId agent identifier
     * @return empty success response
     */
    @DeleteMapping("/agents/{agentId}")
    public ApiResponse<Void> deleteAgent(@PathVariable String agentId) {
        agentFacadeService.deleteAgent(agentId);
        return ApiResponse.success();
    }

    /**
     * Updates an existing agent.
     *
     * @param agentId agent identifier
     * @param request update request payload
     * @return empty success response
     */
    @PatchMapping("/agents/{agentId}")
    public ApiResponse<Void> updateAgent(@PathVariable String agentId, @RequestBody UpdateAgentRequest request) {
        agentFacadeService.updateAgent(agentId, request);
        return ApiResponse.success();
    }
}

