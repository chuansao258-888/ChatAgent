package com.yulong.chatagent.admin.model.request;

import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.AssistantTemplateDTO;
import lombok.Data;

import java.util.List;

/**
 * Request payload for updating an assistant template.
 */
@Data
public class UpdateAssistantTemplateRequest {
    private String code;
    private String name;
    private String description;
    private String systemPrompt;
    private String model;
    private List<String> allowedTools;
    private AgentDTO.ChatOptions chatOptions;
    private List<AssistantTemplateDTO.IntentTreeNodeTemplateDTO> intentTree;
}

