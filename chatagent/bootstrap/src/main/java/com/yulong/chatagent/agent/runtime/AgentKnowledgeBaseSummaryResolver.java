package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class AgentKnowledgeBaseSummaryResolver {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public AgentKnowledgeBaseSummaryResolver(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    public String resolve(AgentDTO agentConfig) {
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        if (knowledgeBases.isEmpty()) {
            return "No knowledge bases available";
        }

        return knowledgeBases.stream()
                .map(kb -> "%s(%s): %s".formatted(
                        StringUtils.hasText(kb.getName()) ? kb.getName() : "Unnamed knowledge base",
                        kb.getId(),
                        StringUtils.hasText(kb.getDescription()) ? kb.getDescription() : "No description"))
                .collect(Collectors.joining("; "));
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBaseDTO> knowledgeBases = knowledgeBaseRepository.findByIds(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(knowledgeBases);
    }
}
