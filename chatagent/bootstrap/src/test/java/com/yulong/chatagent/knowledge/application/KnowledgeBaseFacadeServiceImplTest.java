package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.admin.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.knowledge.converter.KnowledgeBaseConverter;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.rag.service.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;

    @Mock
    private IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Mock
    private KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;

    @Mock
    private DocumentStorageService documentStorageService;

    @Mock
    private ResourceAccessGuard resourceAccessGuard;

    private KnowledgeBaseFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new KnowledgeBaseFacadeServiceImpl(
                adminAccessService,
                agentKnowledgeBaseRepository,
                intentKnowledgeBaseRepository,
                knowledgeBaseRepository,
                knowledgeDocumentRepository,
                knowledgeChunkRepository,
                knowledgeBaseMilvusIndexer,
                documentStorageService,
                new KnowledgeBaseConverter(),
                resourceAccessGuard
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldDeleteKnowledgeBaseDocumentsBindingsAndStoredFiles() throws Exception {
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
        when(knowledgeDocumentRepository.findByKnowledgeBaseId("kb-1")).thenReturn(List.of(
                KnowledgeDocumentDTO.builder()
                        .id("doc-1")
                        .knowledgeBaseId("kb-1")
                        .storagePath("knowledge-bases/kb-1/doc-1/a.pdf")
                        .build(),
                KnowledgeDocumentDTO.builder()
                        .id("doc-2")
                        .knowledgeBaseId("kb-1")
                        .storagePath("knowledge-bases/kb-1/doc-2/b.pdf")
                        .build()
        ));
        when(knowledgeDocumentRepository.deleteById("doc-1")).thenReturn(true);
        when(knowledgeDocumentRepository.deleteById("doc-2")).thenReturn(true);
        when(knowledgeBaseRepository.deleteById("kb-1")).thenReturn(true);

        facadeService.deleteKnowledgeBase("kb-1");

        InOrder inOrder = inOrder(
                knowledgeChunkRepository,
                knowledgeDocumentRepository,
                knowledgeBaseMilvusIndexer,
                agentKnowledgeBaseRepository,
                intentKnowledgeBaseRepository,
                knowledgeBaseRepository,
                documentStorageService
        );
        inOrder.verify(knowledgeChunkRepository).deleteByKnowledgeDocumentId("doc-1");
        inOrder.verify(knowledgeDocumentRepository).deleteById("doc-1");
        inOrder.verify(knowledgeChunkRepository).deleteByKnowledgeDocumentId("doc-2");
        inOrder.verify(knowledgeDocumentRepository).deleteById("doc-2");
        inOrder.verify(knowledgeBaseMilvusIndexer).deleteByKnowledgeBaseId("kb-1");
        inOrder.verify(agentKnowledgeBaseRepository).deleteByKnowledgeBaseId("kb-1");
        inOrder.verify(intentKnowledgeBaseRepository).deleteByKnowledgeBaseId("kb-1");
        inOrder.verify(knowledgeBaseRepository).deleteById("kb-1");
        inOrder.verify(documentStorageService).deleteFile("knowledge-bases/kb-1/doc-1/a.pdf");
        inOrder.verify(documentStorageService).deleteFile("knowledge-bases/kb-1/doc-2/b.pdf");
        verify(documentStorageService, never()).deleteFile((String) null);
    }

    @Test
    void shouldMarkDeleteKnowledgeBaseAsTransactional() throws NoSuchMethodException {
        Method method = KnowledgeBaseFacadeServiceImpl.class.getMethod("deleteKnowledgeBase", String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }
}
