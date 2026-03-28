package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.admin.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.knowledge.converter.KnowledgeBaseConverter;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private ResourceAccessGuard resourceAccessGuard;

    private KnowledgeBaseFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new KnowledgeBaseFacadeServiceImpl(
                adminAccessService,
                agentKnowledgeBaseRepository,
                knowledgeBaseRepository,
                new KnowledgeBaseConverter(),
                resourceAccessGuard
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldRemoveAssistantBindingsWhenKnowledgeBaseIsArchived() {
        LoginUser adminUser = LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build();
        UserContext.set(adminUser);
        KnowledgeBaseDTO knowledgeBase = KnowledgeBaseDTO.builder()
                .id("kb-1")
                .status("ACTIVE")
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        when(resourceAccessGuard.assertCanManageKnowledgeBase(adminUser, "kb-1")).thenReturn(knowledgeBase);
        when(knowledgeBaseRepository.update(org.mockito.ArgumentMatchers.any(KnowledgeBaseDTO.class))).thenReturn(true);

        facadeService.archiveKnowledgeBase("kb-1");

        InOrder inOrder = inOrder(knowledgeBaseRepository, agentKnowledgeBaseRepository);
        inOrder.verify(knowledgeBaseRepository).update(org.mockito.ArgumentMatchers.any(KnowledgeBaseDTO.class));
        inOrder.verify(agentKnowledgeBaseRepository).deleteByKnowledgeBaseId("kb-1");
    }

    @Test
    void shouldMarkArchiveKnowledgeBaseAsTransactional() throws NoSuchMethodException {
        Method method = KnowledgeBaseFacadeServiceImpl.class.getMethod("archiveKnowledgeBase", String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }
}
