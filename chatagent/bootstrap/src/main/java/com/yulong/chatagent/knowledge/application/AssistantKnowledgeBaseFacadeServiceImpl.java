package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.agent.application.InternalAssistantService;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.knowledge.converter.KnowledgeBaseConverter;
import com.yulong.chatagent.knowledge.model.request.SetAssistantKnowledgeBasesRequest;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Binds the single internal assistant to one or more administrator-managed knowledge bases.
 */
@Service
@RequiredArgsConstructor
public class AssistantKnowledgeBaseFacadeServiceImpl implements AssistantKnowledgeBaseFacadeService {

    private final AdminAccessService adminAccessService;
    private final AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ResourceAccessGuard resourceAccessGuard;

    @Override
    public KnowledgeBaseVO[] getAssistantKnowledgeBases() {
        adminAccessService.requireAdmin();
        List<String> boundKnowledgeBaseIds = agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId(
                InternalAssistantService.SYSTEM_ASSISTANT_ID
        );
        List<String> activeKnowledgeBaseIds = knowledgeBaseRepository.filterActiveIds(boundKnowledgeBaseIds);
        List<KnowledgeBaseVO> result = new ArrayList<>();
        for (KnowledgeBaseDTO knowledgeBase : knowledgeBaseRepository.findByIds(activeKnowledgeBaseIds)) {
            result.add(knowledgeBaseConverter.toVO(knowledgeBase));
        }
        return result.toArray(new KnowledgeBaseVO[0]);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAssistantKnowledgeBases(SetAssistantKnowledgeBasesRequest request) {
        adminAccessService.requireAdmin();
        List<String> requestedIds = normalizeKnowledgeBaseIds(request);
        if (!requestedIds.isEmpty()) {
            for (String requestedId : requestedIds) {
                resourceAccessGuard.assertCanManageKnowledgeBase(UserContext.requireUser(), requestedId);
            }
            if (knowledgeBaseRepository.filterActiveIds(requestedIds).size() != requestedIds.size()) {
                throw new BizException("Only active knowledge bases can be bound to the internal assistant");
            }
        }
        agentKnowledgeBaseRepository.replaceBindings(InternalAssistantService.SYSTEM_ASSISTANT_ID, requestedIds);
    }

    private List<String> normalizeKnowledgeBaseIds(SetAssistantKnowledgeBasesRequest request) {
        if (request == null || request.getKnowledgeBaseIds() == null || request.getKnowledgeBaseIds().length == 0) {
            return List.of();
        }
        Set<String> deduplicated = new LinkedHashSet<>();
        for (String knowledgeBaseId : request.getKnowledgeBaseIds()) {
            if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
                deduplicated.add(knowledgeBaseId.trim());
            }
        }
        return List.copyOf(deduplicated);
    }
}
