package com.yulong.chatagent.admin.model.response;

import com.yulong.chatagent.admin.model.vo.AssistantTemplateVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Template catalog for the admin UI.
 */
@Data
@AllArgsConstructor
public class GetAssistantTemplatesResponse {
    private List<AssistantTemplateVO> templates;
}

