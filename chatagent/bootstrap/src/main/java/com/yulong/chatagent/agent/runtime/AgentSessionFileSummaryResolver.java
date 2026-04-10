package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a compact summary of files attached to the current chat session.
 */
@Component
public class AgentSessionFileSummaryResolver {

    private final ChatSessionFileRepository chatSessionFileRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public AgentSessionFileSummaryResolver(ChatSessionFileRepository chatSessionFileRepository,
                                           ChatSessionRepository chatSessionRepository,
                                           AgentKnowledgeBaseRepository agentKnowledgeBaseRepository,
                                           KnowledgeBaseRepository knowledgeBaseRepository) {
        this.chatSessionFileRepository = chatSessionFileRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.agentKnowledgeBaseRepository = agentKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * Produces a human-readable list of attached session files for one agent run.
     *
     * @param agentConfig persisted agent configuration
     * @param chatSessionId current chat session identifier
     * @return prompt-friendly session-file summary
     */
    public String resolve(AgentDTO agentConfig, String chatSessionId) {
        List<String> sections = new ArrayList<>();

        String sessionFileSummary = resolveAttachedSessionFiles(agentConfig, chatSessionId);
        if (StringUtils.hasText(sessionFileSummary)) {
            sections.add("Attached session files: " + sessionFileSummary);
        }

        String knowledgeBaseSummary = resolveBoundKnowledgeBases(chatSessionId);
        if (StringUtils.hasText(knowledgeBaseSummary)) {
            sections.add("Bound knowledge bases: " + knowledgeBaseSummary);
        }

        if (sections.isEmpty()) {
            return "No attached session files or bound knowledge bases available";
        }
        return String.join("; ", sections);
    }

    private String resolveAttachedSessionFiles(AgentDTO agentConfig, String chatSessionId) {
        if (!StringUtils.hasText(chatSessionId)) {
            return "";
        }

        List<ChatSessionFileDTO> files = chatSessionFileRepository.findBySessionId(chatSessionId).stream()
                .filter(file -> StringUtils.hasText(file.getId()))
                .toList();
        if (files.isEmpty()) {
            return "";
        }

        return files.stream()
                .map(file -> StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : file.getFilename())
                .collect(Collectors.joining(", "));
    }

    private String resolveBoundKnowledgeBases(String chatSessionId) {
        ChatSessionDTO session = chatSessionRepository.findById(chatSessionId);
        if (session == null || !StringUtils.hasText(session.getAgentId())) {
            return "";
        }

        List<String> boundKnowledgeBaseIds = agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId(session.getAgentId());
        if (boundKnowledgeBaseIds.isEmpty()) {
            return "";
        }

        return knowledgeBaseRepository.findByIds(boundKnowledgeBaseIds).stream()
                .filter(kb -> "ACTIVE".equalsIgnoreCase(kb.getStatus()))
                .map(KnowledgeBaseDTO::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
    }
}
