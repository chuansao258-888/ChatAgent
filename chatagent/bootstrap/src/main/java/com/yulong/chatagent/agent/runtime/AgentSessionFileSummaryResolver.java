package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
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

    public AgentSessionFileSummaryResolver(ChatSessionFileRepository chatSessionFileRepository) {
        this.chatSessionFileRepository = chatSessionFileRepository;
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

        if (sections.isEmpty()) {
            return "No attached session files available";
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
}
