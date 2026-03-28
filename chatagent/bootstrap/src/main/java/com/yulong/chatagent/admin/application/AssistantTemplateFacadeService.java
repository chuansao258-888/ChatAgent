package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateAssistantTemplateRequest;
import com.yulong.chatagent.admin.model.request.InitializeAssistantFromTemplateRequest;
import com.yulong.chatagent.admin.model.request.UpdateAssistantTemplateRequest;
import com.yulong.chatagent.admin.model.response.GetAssistantTemplateResponse;
import com.yulong.chatagent.admin.model.response.GetAssistantTemplatesResponse;
import com.yulong.chatagent.admin.model.response.InitializeAssistantFromTemplateResponse;

/**
 * Admin-facing template operations for initializing the internal assistant.
 */
public interface AssistantTemplateFacadeService {

    GetAssistantTemplatesResponse getTemplates();

    GetAssistantTemplateResponse getTemplate(String templateId);

    String createTemplate(CreateAssistantTemplateRequest request);

    void updateTemplate(String templateId, UpdateAssistantTemplateRequest request);

    void deleteTemplate(String templateId);

    InitializeAssistantFromTemplateResponse initializeAssistantFromTemplate(String templateId,
                                                                           InitializeAssistantFromTemplateRequest request);
}

