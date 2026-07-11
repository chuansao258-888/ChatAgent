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
 * 构建当前会话附件和绑定知识库的轻量摘要。
 * <p>
 * 这份摘要不会替代 RAG 检索，只是让模型在系统提示词里知道“有哪些材料可查”。
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
     * 为一次 Agent 运行生成 prompt 友好的资源列表。
     *
     * @param agentConfig ignored — retained for backward compatibility; the summary
     *                    is resolved from the session via {@link #resolveForSession}
     * @param chatSessionId 当前会话 ID
     * @return 附件和知识库摘要
     */
    public String resolve(AgentDTO agentConfig, String chatSessionId) {
        return resolveForSession(chatSessionId);
    }

    /** Builds the same content-only summary before the full Agent config is loaded. */
    public String resolveForSession(String chatSessionId) {
        List<String> sections = new ArrayList<>();

        // 会话附件是用户当前上传的资料。
        String sessionFileSummary = resolveAttachedSessionFiles(chatSessionId);
        if (StringUtils.hasText(sessionFileSummary)) {
            sections.add("Attached session files: " + sessionFileSummary);
        }

        // 绑定知识库是 Agent 长期可检索的资料范围。
        String knowledgeBaseSummary = resolveBoundKnowledgeBases(chatSessionId);
        if (StringUtils.hasText(knowledgeBaseSummary)) {
            sections.add("Bound knowledge bases: " + knowledgeBaseSummary);
        }

        if (sections.isEmpty()) {
            return "No attached session files or bound knowledge bases available";
        }
        return String.join("; ", sections);
    }

    private String resolveAttachedSessionFiles(String chatSessionId) {
        // 只列出文件名，不把正文塞进系统提示词；正文内容通过 SessionFileSearchTool 检索。
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
        // 从会话找到绑定的 Agent，再查 Agent 绑定的 ACTIVE 知识库名称。
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
