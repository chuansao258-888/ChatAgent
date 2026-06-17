package com.yulong.chatagent.admin.model.vo;

import com.yulong.chatagent.support.dto.AgentDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Admin-facing view of an agent configuration. */
@Data
@Builder
public class AgentVO {
    private String id;

    private String name;

    private String description;

    private String systemPrompt;

    private AgentDTO.ModelType model;

    private List<String> allowedTools;

    private AgentDTO.ChatOptions chatOptions;
}

