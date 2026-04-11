package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.application.InternalAssistantService;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.IntentKnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentTreeFacadeServiceImplTest {

    @Mock
    private InternalAssistantService internalAssistantService;

    @Mock
    private IntentNodeRepository intentNodeRepository;

    @Mock
    private IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private IntentTreeCacheManager intentTreeCacheManager;

    private IntentTreeFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new IntentTreeFacadeServiceImpl(
                internalAssistantService,
                intentNodeRepository,
                intentKnowledgeBaseRepository,
                knowledgeBaseRepository,
                intentTreeCacheManager
        );
    }

    @Test
    void shouldPublishDraftSnapshotAndRefreshActiveCache() {
        AgentDTO assistant = AgentDTO.builder()
                .id("assistant-1")
                .activeIntentVersion(2)
                .build();
        List<IntentNodeDTO> draftNodes = List.of(
                IntentNodeDTO.builder()
                        .id("draft-domain")
                        .agentId("assistant-1")
                        .version(0)
                        .status(IntentNodeStatus.DRAFT)
                        .nodeLevel(IntentNodeLevel.DOMAIN)
                        .name("HR")
                        .enabled(true)
                        .sortOrder(0)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build(),
                IntentNodeDTO.builder()
                        .id("draft-topic")
                        .agentId("assistant-1")
                        .parentId("draft-domain")
                        .version(0)
                        .status(IntentNodeStatus.DRAFT)
                        .nodeLevel(IntentNodeLevel.TOPIC)
                        .name("加班申请")
                        .intentKind(IntentKind.KB)
                        .scopePolicy(ScopePolicy.FALLBACK_ALLOWED)
                        .enabled(true)
                        .sortOrder(1)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build()
        );
        when(internalAssistantService.getRequiredAssistant()).thenReturn(assistant);
        when(intentNodeRepository.findByAgentIdAndVersion("assistant-1", 0)).thenReturn(draftNodes);
        when(intentNodeRepository.findMaxVersion("assistant-1")).thenReturn(2);
        when(intentNodeRepository.saveAll(anyList())).thenReturn(true);
        when(intentKnowledgeBaseRepository.findByIntentNodeIds(List.of("draft-domain", "draft-topic"))).thenReturn(List.of(
                IntentKnowledgeBaseDTO.builder()
                        .id("binding-1")
                        .intentNodeId("draft-topic")
                        .knowledgeBaseId("kb-1")
                        .createdAt(LocalDateTime.now())
                        .build()
        ));
        when(intentKnowledgeBaseRepository.saveAll(anyList())).thenReturn(true);
        when(internalAssistantService.updateActiveIntentVersion(anyInt())).thenReturn(true);

        Integer response = facadeService.publishIntentTreeSnapshot();

        assertThat(response).isEqualTo(3);

        ArgumentCaptor<List<IntentNodeDTO>> nodeCaptor = ArgumentCaptor.forClass(List.class);
        verify(intentNodeRepository).saveAll(nodeCaptor.capture());
        List<IntentNodeDTO> publishedNodes = nodeCaptor.getValue();
        assertThat(publishedNodes).hasSize(2);
        assertThat(publishedNodes).allMatch(node -> node.getVersion() == 3);
        assertThat(publishedNodes).allMatch(node -> node.getStatus() == IntentNodeStatus.PUBLISHED);

        IntentNodeDTO publishedDomain = publishedNodes.stream()
                .filter(node -> "HR".equals(node.getName()))
                .findFirst()
                .orElseThrow();
        IntentNodeDTO publishedTopic = publishedNodes.stream()
                .filter(node -> "加班申请".equals(node.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(publishedTopic.getParentId()).isEqualTo(publishedDomain.getId());
        verify(intentTreeCacheManager).refreshActiveSnapshot("assistant-1");
        verify(internalAssistantService).updateActiveIntentVersion(3);
    }

    @Test
    void shouldSwitchActiveIntentVersionAndRefreshCache() {
        AgentDTO assistant = AgentDTO.builder()
                .id("assistant-1")
                .activeIntentVersion(2)
                .build();
        when(internalAssistantService.getRequiredAssistant()).thenReturn(assistant);
        when(intentNodeRepository.findByAgentIdAndVersion("assistant-1", 5)).thenReturn(List.of(
                IntentNodeDTO.builder()
                        .id("published-node")
                        .agentId("assistant-1")
                        .version(5)
                        .status(IntentNodeStatus.PUBLISHED)
                        .nodeLevel(IntentNodeLevel.TOPIC)
                        .name("报销制度")
                        .intentKind(IntentKind.KB)
                        .build()
        ));
        when(internalAssistantService.updateActiveIntentVersion(5)).thenReturn(true);

        facadeService.switchActiveIntentVersion(5);

        verify(internalAssistantService).updateActiveIntentVersion(5);
        verify(intentTreeCacheManager).refreshActiveSnapshot("assistant-1");
    }
}
