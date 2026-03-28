package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.knowledge.converter.KnowledgeDocumentConverter;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
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
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentFacadeServiceImplTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Mock
    private KnowledgeDocumentConverter knowledgeDocumentConverter;

    @Mock
    private DocumentStorageService documentStorageService;

    @Mock
    private KnowledgeDocumentIngestionService knowledgeDocumentIngestionService;

    @Mock
    private KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;

    @Mock
    private ResourceAccessGuard resourceAccessGuard;

    private KnowledgeDocumentFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new KnowledgeDocumentFacadeServiceImpl(
                adminAccessService,
                knowledgeDocumentRepository,
                knowledgeChunkRepository,
                knowledgeDocumentConverter,
                documentStorageService,
                knowledgeDocumentIngestionService,
                knowledgeBaseMilvusIndexer,
                resourceAccessGuard
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldClearExistingIndexedContentBeforeReingestingReplacementDocument() throws IOException {
        LoginUser adminUser = LoginUser.builder()
                .userId("admin-1")
                .role("admin")
                .build();
        UserContext.set(adminUser);
        when(adminAccessService.requireAdmin()).thenReturn(adminUser);
        when(resourceAccessGuard.assertCanManageKnowledgeBase(adminUser, "kb-1")).thenReturn(KnowledgeBaseDTO.builder()
                .id("kb-1")
                .status("ACTIVE")
                .build());
        when(knowledgeDocumentRepository.findById("doc-1")).thenReturn(KnowledgeDocumentDTO.builder()
                .id("doc-1")
                .knowledgeBaseId("kb-1")
                .storagePath("knowledge-bases/kb-1/doc-1/old.md")
                .parseStatus("COMPLETED")
                .retryCount(1)
                .createdAt(LocalDateTime.now().minusDays(1))
                .build());
        when(documentStorageService.saveKnowledgeDocument(eq("kb-1"), eq("doc-1"), any()))
                .thenReturn("knowledge-bases/kb-1/doc-1/new.md");
        when(knowledgeDocumentRepository.update(any())).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "new-policy.md",
                "text/markdown",
                "updated leave policy".getBytes()
        );

        facadeService.replaceKnowledgeDocument("kb-1", "doc-1", file);

        InOrder inOrder = inOrder(knowledgeChunkRepository, knowledgeBaseMilvusIndexer, knowledgeDocumentIngestionService);
        inOrder.verify(knowledgeChunkRepository).deleteByKnowledgeDocumentId("doc-1");
        inOrder.verify(knowledgeBaseMilvusIndexer).deleteByKnowledgeDocumentId("doc-1");
        inOrder.verify(knowledgeDocumentIngestionService).ingest(eq("kb-1"), any(KnowledgeDocumentDTO.class));
        verify(knowledgeDocumentRepository).update(any(KnowledgeDocumentDTO.class));
    }
}
