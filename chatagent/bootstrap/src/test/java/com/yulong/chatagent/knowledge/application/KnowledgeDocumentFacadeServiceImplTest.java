package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.knowledge.converter.KnowledgeDocumentConverter;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxEventPublisher;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
import com.yulong.chatagent.rag.parser.FileTypeDetector;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.service.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import com.yulong.chatagent.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.MessageDigest;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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
    private KnowledgeDocumentStatusSseService knowledgeDocumentStatusSseService;

    @Mock
    private ResourceAccessGuard resourceAccessGuard;

    @Mock
    private OutboxEventPublisher outboxEventPublisher;

    @Mock
    private KnowledgeDocumentSignalService knowledgeDocumentSignalService;

    private ChatAgentMqProperties mqProperties;
    private final FileTypeDetector fileTypeDetector = new FileTypeDetector();

    private KnowledgeDocumentFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        mqProperties = new ChatAgentMqProperties();
        facadeService = new KnowledgeDocumentFacadeServiceImpl(
                adminAccessService,
                knowledgeDocumentRepository,
                knowledgeChunkRepository,
                knowledgeDocumentConverter,
                documentStorageService,
                knowledgeDocumentIngestionService,
                knowledgeBaseMilvusIndexer,
                knowledgeDocumentStatusSseService,
                resourceAccessGuard,
                mqProperties,
                knowledgeDocumentSignalService,
                fileTypeDetector
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

    @Test
    void shouldWriteOutboxInsteadOfTriggeringAsyncIngestWhenMqEnabled() throws IOException {
        mqProperties.setEnabled(true);
        ReflectionTestUtils.setField(facadeService, "outboxEventPublisher", outboxEventPublisher);

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
        when(documentStorageService.saveKnowledgeDocument(eq("kb-1"), any(), any()))
                .thenReturn("knowledge-bases/kb-1/doc-new/source.md");
        when(knowledgeDocumentRepository.save(any())).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.md",
                "text/markdown",
                "hello stage4a".getBytes()
        );

        facadeService.uploadKnowledgeDocument("kb-1", file);

        verify(outboxEventPublisher).publish(
                eq("knowledge.ingest"),
                eq(mqProperties.getExchanges().getChatDirect()),
                eq(mqProperties.getRoutingKeys().getIngestTask()),
                any(),
                any()
        );
        verify(knowledgeDocumentIngestionService, never()).ingest(eq("kb-1"), any(KnowledgeDocumentDTO.class));
        verify(knowledgeChunkRepository, never()).deleteByKnowledgeDocumentId(any());
        verify(knowledgeBaseMilvusIndexer, never()).deleteByKnowledgeDocumentId(any());
    }

    @Test
    void shouldRejectStandaloneImagesBeforeKnowledgeStorage() throws Exception {
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

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "scan.png",
                "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}
        );

        assertThatThrownBy(() -> facadeService.uploadKnowledgeDocument("kb-1", file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("standalone images");

        verify(documentStorageService, never()).saveKnowledgeDocument(eq("kb-1"), any(), any());
        verify(knowledgeDocumentRepository, never()).save(any());
        verify(knowledgeDocumentIngestionService, never()).ingest(eq("kb-1"), any(KnowledgeDocumentDTO.class));
    }

    @Test
    void shouldUseDocumentVersionIdempotencyKeyWhenReplacingKnowledgeDocumentInMqMode() throws Exception {
        mqProperties.setEnabled(true);
        ReflectionTestUtils.setField(facadeService, "outboxEventPublisher", outboxEventPublisher);

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
                .storagePath("knowledge-bases/kb-1/doc-1/old.pdf")
                .parseStatus("COMPLETED")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build());
        when(documentStorageService.saveKnowledgeDocument(eq("kb-1"), eq("doc-1"), any()))
                .thenReturn("knowledge-bases/kb-1/doc-1/new.pdf");
        when(knowledgeDocumentRepository.update(any())).thenReturn(true);

        byte[] content = "updated knowledge content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "new.pdf",
                "application/pdf",
                content
        );

        facadeService.replaceKnowledgeDocument("kb-1", "doc-1", file);

        String expectedContentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        verify(outboxEventPublisher).publish(
                eq("knowledge.ingest"),
                eq(mqProperties.getExchanges().getChatDirect()),
                eq(mqProperties.getRoutingKeys().getIngestTask()),
                any(),
                argThat((MqMessageIdentity identity) -> {
                    assertThat(identity.idempotencyKey()).isEqualTo("doc-1:" + expectedContentHash);
                    return true;
                })
        );
        verify(knowledgeDocumentIngestionService, never()).ingest(eq("kb-1"), any(KnowledgeDocumentDTO.class));
    }

    @Test
    void shouldDeleteDocumentMetadataIndexedContentAndStoredFile() throws Exception {
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
                .storagePath("knowledge-bases/kb-1/doc-1/source.pdf")
                .parseStatus("COMPLETED")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build());
        when(knowledgeDocumentRepository.deleteById("doc-1")).thenReturn(true);

        facadeService.deleteKnowledgeDocument("kb-1", "doc-1");

        InOrder inOrder = inOrder(knowledgeChunkRepository, knowledgeBaseMilvusIndexer, knowledgeDocumentRepository, documentStorageService);
        inOrder.verify(knowledgeChunkRepository).deleteByKnowledgeDocumentId("doc-1");
        inOrder.verify(knowledgeBaseMilvusIndexer).deleteByKnowledgeDocumentId("doc-1");
        inOrder.verify(knowledgeDocumentRepository).deleteById("doc-1");
        inOrder.verify(documentStorageService).deleteFile("knowledge-bases/kb-1/doc-1/source.pdf");
        verify(knowledgeDocumentSignalService).evictCache("doc-1");
        verify(knowledgeDocumentIngestionService, never()).ingest(eq("kb-1"), any(KnowledgeDocumentDTO.class));
    }
}
