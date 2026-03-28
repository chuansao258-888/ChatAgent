package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ServiceException;
import com.yulong.chatagent.knowledge.converter.KnowledgeDocumentConverter;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeDocumentsResponse;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeDocumentVO;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
import com.yulong.chatagent.rag.service.DocumentStorageService;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Administrator-facing document upload and maintenance flows for knowledge bases.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentFacadeServiceImpl implements KnowledgeDocumentFacadeService {

    private final AdminAccessService adminAccessService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeDocumentConverter knowledgeDocumentConverter;
    private final DocumentStorageService documentStorageService;
    private final KnowledgeDocumentIngestionService knowledgeDocumentIngestionService;
    private final com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;
    private final ResourceAccessGuard resourceAccessGuard;

    @Override
    public GetKnowledgeDocumentsResponse getKnowledgeDocuments(String knowledgeBaseId) {
        adminAccessService.requireAdmin();
        resourceAccessGuard.assertCanManageKnowledgeBase(UserContext.requireUser(), knowledgeBaseId);

        List<KnowledgeDocumentVO> result = new ArrayList<>();
        for (KnowledgeDocumentDTO document : knowledgeDocumentRepository.findByKnowledgeBaseId(knowledgeBaseId)) {
            result.add(knowledgeDocumentConverter.toVO(document));
        }
        return GetKnowledgeDocumentsResponse.builder()
                .documents(result.toArray(new KnowledgeDocumentVO[0]))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadKnowledgeDocumentResponse uploadKnowledgeDocument(String knowledgeBaseId, MultipartFile file) {
        adminAccessService.requireAdmin();
        KnowledgeBaseDTO knowledgeBase = resourceAccessGuard.assertCanManageKnowledgeBase(
                UserContext.requireUser(),
                knowledgeBaseId
        );
        requireActiveKnowledgeBase(knowledgeBase);
        return createOrReplaceDocument(knowledgeBase, null, file);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadKnowledgeDocumentResponse replaceKnowledgeDocument(String knowledgeBaseId, String documentId, MultipartFile file) {
        adminAccessService.requireAdmin();
        KnowledgeBaseDTO knowledgeBase = resourceAccessGuard.assertCanManageKnowledgeBase(
                UserContext.requireUser(),
                knowledgeBaseId
        );
        requireActiveKnowledgeBase(knowledgeBase);
        KnowledgeDocumentDTO existingDocument = requireKnowledgeDocument(knowledgeBaseId, documentId);
        return createOrReplaceDocument(knowledgeBase, existingDocument, file);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveKnowledgeDocument(String knowledgeBaseId, String documentId) {
        adminAccessService.requireAdmin();
        resourceAccessGuard.assertCanManageKnowledgeBase(UserContext.requireUser(), knowledgeBaseId);
        KnowledgeDocumentDTO document = requireKnowledgeDocument(knowledgeBaseId, documentId);
        if (Boolean.TRUE.equals(document.getDeleted())) {
            return;
        }

        document.setDeleted(true);
        document.setUpdatedAt(LocalDateTime.now());
        if (!knowledgeDocumentRepository.update(document)) {
            throw new BizException("Failed to archive knowledge document");
        }

        knowledgeChunkRepository.deleteByKnowledgeDocumentId(documentId);
        knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(documentId);
    }

    private UploadKnowledgeDocumentResponse createOrReplaceDocument(KnowledgeBaseDTO knowledgeBase,
                                                                    KnowledgeDocumentDTO existingDocument,
                                                                    MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("Uploaded file is empty");
        }

        String documentId = existingDocument == null ? UUID.randomUUID().toString() : existingDocument.getId();
        LocalDateTime now = LocalDateTime.now();
        String storedPath;
        try {
            storedPath = documentStorageService.saveKnowledgeDocument(knowledgeBase.getId(), documentId, file);
        } catch (IOException e) {
            throw new ServiceException("Failed to store knowledge document");
        }

        String previousStoragePath = existingDocument == null ? null : existingDocument.getStoragePath();
        KnowledgeDocumentDTO document = existingDocument == null
                ? KnowledgeDocumentDTO.builder().id(documentId).knowledgeBaseId(knowledgeBase.getId()).build()
                : existingDocument;
        try {
            document.setFilename(defaultFilename(file.getOriginalFilename()));
            document.setOriginalFilename(defaultFilename(file.getOriginalFilename()));
            document.setMimeType(defaultMimeType(file.getContentType()));
            document.setSizeBytes(file.getSize());
            document.setStoragePath(storedPath);
            document.setParseStatus("PENDING");
            document.setContentHash(sha256(file));
            document.setFailedReason(null);
            document.setIndexedAt(null);
            document.setRetryCount(0);
            document.setMetadata("{}");
            document.setDeleted(false);
            document.setUpdatedAt(now);
            if (existingDocument == null) {
                document.setCreatedAt(now);
                if (!knowledgeDocumentRepository.save(document)) {
                    throw new BizException("Failed to create knowledge document record");
                }
            } else if (!knowledgeDocumentRepository.update(document)) {
                throw new BizException("Failed to replace knowledge document");
            }

            scheduleIngestionAfterCommit(knowledgeBase.getId(), document, existingDocument != null);

            if (existingDocument != null && previousStoragePath != null && !previousStoragePath.equals(storedPath)) {
                cleanupStoredFileQuietly(documentId, previousStoragePath, "replace");
            }

            return UploadKnowledgeDocumentResponse.builder()
                    .knowledgeBaseId(knowledgeBase.getId())
                    .documentId(documentId)
                    .build();
        } catch (RuntimeException e) {
            cleanupStoredFileQuietly(documentId, storedPath, "rollback");
            throw e;
        }
    }

    private void scheduleIngestionAfterCommit(String knowledgeBaseId,
                                              KnowledgeDocumentDTO knowledgeDocument,
                                              boolean clearExistingContentFirst) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearIndexedContentForReplace(knowledgeDocument, clearExistingContentFirst);
                    knowledgeDocumentIngestionService.ingest(knowledgeBaseId, knowledgeDocument);
                }
            });
            return;
        }
        clearIndexedContentForReplace(knowledgeDocument, clearExistingContentFirst);
        knowledgeDocumentIngestionService.ingest(knowledgeBaseId, knowledgeDocument);
    }

    private void clearIndexedContentForReplace(KnowledgeDocumentDTO knowledgeDocument, boolean clearExistingContentFirst) {
        if (!clearExistingContentFirst || knowledgeDocument == null || !StringUtils.hasText(knowledgeDocument.getId())) {
            return;
        }
        if ("PENDING".equalsIgnoreCase(knowledgeDocument.getParseStatus())) {
            knowledgeChunkRepository.deleteByKnowledgeDocumentId(knowledgeDocument.getId());
            knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(knowledgeDocument.getId());
        }
    }

    private void requireActiveKnowledgeBase(KnowledgeBaseDTO knowledgeBase) {
        if (!"ACTIVE".equalsIgnoreCase(knowledgeBase.getStatus())) {
            throw new BizException("Knowledge base is archived: " + knowledgeBase.getId());
        }
    }

    private KnowledgeDocumentDTO requireKnowledgeDocument(String knowledgeBaseId, String documentId) {
        KnowledgeDocumentDTO document = knowledgeDocumentRepository.findById(documentId);
        if (document == null || !knowledgeBaseId.equals(document.getKnowledgeBaseId())) {
            throw new BizException("Knowledge document not found: " + documentId);
        }
        return document;
    }

    private String defaultFilename(String originalFilename) {
        return (originalFilename == null || originalFilename.isBlank()) ? "knowledge-document" : originalFilename;
    }

    private String defaultMimeType(String mimeType) {
        return (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
    }

    private String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(file.getBytes()));
        } catch (Exception e) {
            throw new ServiceException("Failed to calculate knowledge document hash");
        }
    }

    private void cleanupStoredFileQuietly(String documentId, String storagePath, String reason) {
        if (storagePath == null) {
            return;
        }
        try {
            documentStorageService.deleteFile(storagePath);
        } catch (Exception ex) {
            log.warn("Failed to clean up stored knowledge document after {}: documentId={}, error={}",
                    reason, documentId, ex.getMessage());
        }
    }
}
