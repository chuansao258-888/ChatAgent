package com.yulong.chatagent.admin.model.request;

import com.yulong.chatagent.support.dto.AgentDTO;
import lombok.Data;

import java.util.List;

/**
 * Unified payload for creating or updating an agent.
 */
@Data
public class UpsertAgentRequest {
    private String name;
    private String description;
    private String systemPrompt;
    private String model;
    private List<String> allowedTools;
    private AgentDTO.ChatOptions chatOptions;
}
