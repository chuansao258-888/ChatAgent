package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.knowledge.converter.KnowledgeBaseConverter;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantKnowledgeBaseFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ResourceAccessGuard resourceAccessGuard;

    private AssistantKnowledgeBaseFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new AssistantKnowledgeBaseFacadeServiceImpl(
                adminAccessService,
                agentKnowledgeBaseRepository,
                knowledgeBaseRepository,
                new KnowledgeBaseConverter(),
                resourceAccessGuard
        );
    }

    @Test
    void shouldOnlyReturnActiveKnowledgeBaseBindings() {
        when(adminAccessService.requireAdmin()).thenReturn(LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build());
        when(agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId("3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10"))
                .thenReturn(List.of("kb-active", "kb-archived"));
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-active", "kb-archived")))
                .thenReturn(List.of("kb-active"));
        when(knowledgeBaseRepository.findByIds(List.of("kb-active")))
                .thenReturn(List.of(KnowledgeBaseDTO.builder()
                        .id("kb-active")
                        .name("Employee Handbook")
                        .status("ACTIVE")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build()));

        KnowledgeBaseVO[] response = facadeService.getAssistantKnowledgeBases();

        assertThat(response).extracting(KnowledgeBaseVO::getId)
                .containsExactly("kb-active");
        verify(knowledgeBaseRepository).filterActiveIds(List.of("kb-active", "kb-archived"));
    }

    @Test
    void shouldMarkAssistantBindingRewriteAsTransactional() throws NoSuchMethodException {
        Method method = AssistantKnowledgeBaseFacadeServiceImpl.class.getMethod(
                "setAssistantKnowledgeBases",
                com.yulong.chatagent.knowledge.model.request.SetAssistantKnowledgeBasesRequest.class
        );

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }
}
