package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.AssistantTemplateVO;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One assistant template payload.
 */
@Data
@AllArgsConstructor
public class GetAssistantTemplateResponse {
    private AssistantTemplateVO template;
}

