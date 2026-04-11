package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.InitializeAssistantFromTemplateRequest;
import com.yulong.chatagent.admin.model.request.UpsertAssistantTemplateRequest;
import com.yulong.chatagent.admin.model.response.InitializeAssistantFromTemplateResponse;
import com.yulong.chatagent.admin.model.vo.AssistantTemplateVO;

import java.util.List;

/**
 * Admin-facing template operations for initializing the internal assistant.
 */
public interface AssistantTemplateFacadeService {

    List<AssistantTemplateVO> getTemplates();

    AssistantTemplateVO getTemplate(String templateId);

    String createTemplate(UpsertAssistantTemplateRequest request);

    void updateTemplate(String templateId, UpsertAssistantTemplateRequest request);

    void deleteTemplate(String templateId);

    InitializeAssistantFromTemplateResponse initializeAssistantFromTemplate(String templateId,
                                                                           InitializeAssistantFromTemplateRequest request);
}
