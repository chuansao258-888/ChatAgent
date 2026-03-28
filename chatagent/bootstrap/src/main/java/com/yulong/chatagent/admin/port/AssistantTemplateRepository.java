package com.yulong.chatagent.admin.port;

import com.yulong.chatagent.support.dto.AssistantTemplateDTO;

import java.util.List;

/**
 * Persistence port for assistant templates.
 */
public interface AssistantTemplateRepository {

    List<AssistantTemplateDTO> findAll();

    AssistantTemplateDTO findById(String id);

    AssistantTemplateDTO findByCode(String code);

    boolean save(AssistantTemplateDTO template);

    boolean update(AssistantTemplateDTO template);

    boolean deleteById(String id);
}

