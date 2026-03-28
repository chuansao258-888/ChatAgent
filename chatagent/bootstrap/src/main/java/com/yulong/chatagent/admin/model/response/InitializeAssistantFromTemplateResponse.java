package com.yulong.chatagent.admin.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Result payload after initializing the internal assistant from a template.
 */
@Data
@AllArgsConstructor
public class InitializeAssistantFromTemplateResponse {
    private String templateId;
    private Integer activeIntentVersion;
}
