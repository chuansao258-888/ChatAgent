package com.yulong.chatagent.admin.model.request;

import lombok.Data;

import java.util.List;

/**
 * Request payload for initializing the internal assistant from a template.
 */
@Data
public class InitializeAssistantFromTemplateRequest {
    private List<String> knowledgeBaseIds;
}

