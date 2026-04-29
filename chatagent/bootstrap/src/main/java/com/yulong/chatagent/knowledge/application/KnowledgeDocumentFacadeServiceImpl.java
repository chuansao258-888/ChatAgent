package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ServiceException;
import com.yulong.chatagent.knowledge.converter.KnowledgeDocumentConverter;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeDocumentVO;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.mq.outbox.OutboxEventPublisher;
import com.yulong.chatagent.mq.outbox.event.KnowledgeIngestTaskPayload;
import com.yulong.chatagent.mq.support.MqMessageIdentity;
import com.yulong.chatagent.rag.ingestion.FileSizeGuard;
import com.yulong.chatagent.rag.ingestion.KnowledgeDocumentIngestionService;
import com.yulong.chatagent.rag.parser.DetectedFileType;
import com.yulong.chatagent.rag.parser.FileTypeDetector;
import com.yulong.chatagent.rag.parser.PipelineSource;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import com.yulong.chatagent.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
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

    private static final int DETECTION_PREFIX_BYTES = 8192;

    private final AdminAccessService adminAccessService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeDocumentConverter knowledgeDocumentConverter;
    private final DocumentStorageService documentStorageService;
    private final KnowledgeDocumentIngestionService knowledgeDocumentIngestionService;
    private final com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;
    private final KnowledgeDocumentStatusSseService knowledgeDocumentStatusSseService;
    private final ResourceAccessGuard resourceAccessGuard;
    private final ChatAgentMqProperties mqProperties;
    private final KnowledgeDocumentSignalService knowledgeDocumentSignalService;
    private final FileTypeDetector fileTypeDetector;

    // Absent when chatagent.mq.enabled=false; present when true.
    @Autowired(required = false)
    private OutboxEventPublisher outboxEventPublisher;

    @Override
    public KnowledgeDocumentVO[] getKnowledgeDocuments(String knowledgeBaseId) {
        adminAccessService.requireAdmin();
        resourceAccessGuard.assertCanManageKnowledgeBase(UserContext.requireUser(), knowledgeBaseId);

        List<KnowledgeDocumentVO> result = new ArrayList<>();
        for (KnowledgeDocumentDTO document : knowledgeDocumentRepository.findByKnowledgeBaseId(knowledgeBaseId)) {
            result.add(knowledgeDocumentConverter.toVO(document));
        }
        return result.toArray(new KnowledgeDocumentVO[0]);
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
    public void deleteKnowledgeDocument(String knowledgeBaseId, String documentId) {
        adminAccessService.requireAdmin();
        resourceAccessGuard.assertCanManageKnowledgeBase(UserContext.requireUser(), knowledgeBaseId);
        KnowledgeDocumentDTO document = requireKnowledgeDocument(knowledgeBaseId, documentId);
        knowledgeChunkRepository.deleteByKnowledgeDocumentId(documentId);
        knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(documentId);
        if (!knowledgeDocumentRepository.deleteById(documentId)) {
            throw new BizException("Failed to delete knowledge document");
        }
        scheduleSignalCacheEvictionAfterCommit(documentId);
        scheduleStoredFileDeletionAfterCommit(documentId, document.getStoragePath());
    }

    @Override
    public KnowledgeDocumentDTO getKnowledgeDocument(String documentId) {
        return knowledgeDocumentRepository.findById(documentId);
    }

    @Override
    public void ingestKnowledgeDocument(KnowledgeDocumentDTO document) {
        if (document == null || !StringUtils.hasText(document.getKnowledgeBaseId())) {
            throw new BizException("Knowledge document is invalid for ingestion");
        }
        knowledgeDocumentIngestionService.ingestSync(document.getKnowledgeBaseId(), document);
    }

    @Override
    public void markIngestionFailed(String documentId, String error) {
        if (!StringUtils.hasText(documentId)) {
            throw new BizException("Knowledge document id is required");
        }
        KnowledgeDocumentDTO document = knowledgeDocumentRepository.findById(documentId);
        if (document == null) {
            log.warn("Skip marking knowledge ingestion as failed because document does not exist: documentId={}", documentId);
            return;
        }
        document.setParseStatus("FAILED");
        document.setFailedReason(error);
        document.setRetryCount((document.getRetryCount() == null ? 0 : document.getRetryCount()) + 1);
        document.setUpdatedAt(LocalDateTime.now());
        if (!knowledgeDocumentRepository.update(document)) {
            throw new BizException("Failed to update knowledge document failure status: " + documentId);
        }
        knowledgeDocumentStatusSseService.publishStatusUpdated(document);
    }

    private UploadKnowledgeDocumentResponse createOrReplaceDocument(KnowledgeBaseDTO knowledgeBase,
                                                                    KnowledgeDocumentDTO existingDocument,
                                                                    MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("Uploaded file is empty");
        }
        validateKnowledgeUploadSize(file);
        validateKnowledgeUpload(file);
        String incomingContentHash = sha256(file);
        if (shouldSkipReplacement(existingDocument, incomingContentHash)) {
            log.info("Knowledge document replacement skipped as no-op: knowledgeBaseId={}, documentId={}, filename={}, reason=same-content-already-indexed",
                    knowledgeBase.getId(),
                    existingDocument.getId(),
                    existingDocument.getOriginalFilename());
            return UploadKnowledgeDocumentResponse.builder()
                    .knowledgeBaseId(knowledgeBase.getId())
                    .documentId(existingDocument.getId())
                    .build();
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
            document.setContentHash(incomingContentHash);
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

    private boolean shouldSkipReplacement(KnowledgeDocumentDTO existingDocument, String incomingContentHash) {
        if (existingDocument == null) {
            return false;
        }
        if (!StringUtils.hasText(incomingContentHash) || !StringUtils.hasText(existingDocument.getContentHash())) {
            return false;
        }
        if (!incomingContentHash.equals(existingDocument.getContentHash().trim())) {
            return false;
        }
        return "COMPLETED".equalsIgnoreCase(existingDocument.getParseStatus());
    }

    private void validateKnowledgeUploadSize(MultipartFile file) {
        if (file.getSize() > FileSizeGuard.MAX_FILE_BYTES) {
            throw new BizException("Knowledge document file size cannot exceed 30MB");
        }
    }

    private void scheduleIngestionAfterCommit(String knowledgeBaseId,
                                              KnowledgeDocumentDTO knowledgeDocument,
                                              boolean clearExistingContentFirst) {
        if (mqProperties.isEnabled() && outboxEventPublisher != null) {
            publishToOutbox(knowledgeBaseId, knowledgeDocument, clearExistingContentFirst);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        clearIndexedContentForReplace(knowledgeDocument, clearExistingContentFirst);
                    }
                });
            } else {
                clearIndexedContentForReplace(knowledgeDocument, clearExistingContentFirst);
            }
            return;
        }

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

    private void publishToOutbox(String knowledgeBaseId,
                                 KnowledgeDocumentDTO knowledgeDocument,
                                 boolean clearExistingContentFirst) {
        KnowledgeIngestTaskPayload payload = new KnowledgeIngestTaskPayload(
                knowledgeBaseId,
                knowledgeDocument.getId(),
                clearExistingContentFirst
        );
        MqMessageIdentity identity = MqMessageIdentity.initial(
                "knowledge.ingest",
                buildKnowledgeIngestIdempotencyKey(knowledgeDocument),
                TraceContext.getTraceId(),
                mqProperties.getExchanges().getChatDirect(),
                mqProperties.getRoutingKeys().getIngestTask()
        );
        outboxEventPublisher.publish(
                "knowledge.ingest",
                mqProperties.getExchanges().getChatDirect(),
                mqProperties.getRoutingKeys().getIngestTask(),
                payload,
                identity
        );
        log.info("Knowledge ingest task published to outbox: documentId={}, eventId={}",
                knowledgeDocument.getId(), identity.eventId());
    }

    private String buildKnowledgeIngestIdempotencyKey(KnowledgeDocumentDTO knowledgeDocument) {
        if (knowledgeDocument == null || !StringUtils.hasText(knowledgeDocument.getId())) {
            throw new BizException("Knowledge document is invalid for MQ ingestion");
        }
        if (StringUtils.hasText(knowledgeDocument.getContentHash())) {
            return knowledgeDocument.getId() + ":" + knowledgeDocument.getContentHash().trim();
        }
        return knowledgeDocument.getId();
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
            try (InputStream inputStream = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new ServiceException("Failed to calculate knowledge document hash");
        }
    }

    private void validateKnowledgeUpload(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] prefix = inputStream.readNBytes(DETECTION_PREFIX_BYTES);
            DetectedFileType detectedFileType = fileTypeDetector.detect(
                    prefix,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    PipelineSource.KNOWLEDGE
            );
            if (detectedFileType.rejected()) {
                throw new BizException(detectedFileType.rejectionReason());
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new ServiceException("Failed to inspect uploaded knowledge document");
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

    private void scheduleStoredFileDeletionAfterCommit(String documentId, String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupStoredFileQuietly(documentId, storagePath, "delete");
                }
            });
            return;
        }
        cleanupStoredFileQuietly(documentId, storagePath, "delete");
    }

    private void scheduleSignalCacheEvictionAfterCommit(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    knowledgeDocumentSignalService.evictCache(documentId);
                }
            });
            return;
        }
        knowledgeDocumentSignalService.evictCache(documentId);
    }

}
