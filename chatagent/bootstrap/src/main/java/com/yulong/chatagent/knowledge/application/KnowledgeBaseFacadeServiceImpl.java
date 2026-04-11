package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.admin.application.AdminAccessService;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.knowledge.converter.KnowledgeBaseConverter;
import com.yulong.chatagent.knowledge.model.request.UpsertKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Administrator-facing knowledge-base management flows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseFacadeServiceImpl implements KnowledgeBaseFacadeService {

    private final AdminAccessService adminAccessService;
    private final AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;
    private final IntentKnowledgeBaseRepository intentKnowledgeBaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;
    private final KnowledgeDocumentSignalService knowledgeDocumentSignalService;
    private final DocumentStorageService documentStorageService;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ResourceAccessGuard resourceAccessGuard;

    @Override
    public KnowledgeBaseVO[] getKnowledgeBases() {
        adminAccessService.requireAdmin();
        List<KnowledgeBaseVO> result = new ArrayList<>();
        for (KnowledgeBaseDTO knowledgeBase : knowledgeBaseRepository.findAll()) {
            result.add(knowledgeBaseConverter.toVO(knowledgeBase));
        }
        return result.toArray(new KnowledgeBaseVO[0]);
    }

    @Override
    public KnowledgeBaseVO getKnowledgeBase(String knowledgeBaseId) {
        LoginUser adminUser = adminAccessService.requireAdmin();
        return knowledgeBaseConverter.toVO(
                resourceAccessGuard.assertCanManageKnowledgeBase(adminUser, knowledgeBaseId)
        );
    }

    @Override
    public String createKnowledgeBase(UpsertKnowledgeBaseRequest request) {
        String adminUserId = adminAccessService.requireAdminUserId();
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new BizException("Knowledge base name is required");
        }

        LocalDateTime now = LocalDateTime.now();
        KnowledgeBaseDTO knowledgeBase = KnowledgeBaseDTO.builder()
                .id(UUID.randomUUID().toString())
                .createdBy(adminUserId)
                .name(request.getName().trim())
                .description(trimToNull(request.getDescription()))
                .visibility("SHARED")
                .status("ACTIVE")
                .metadata("{}")
                .createdAt(now)
                .updatedAt(now)
                .build();
        if (!knowledgeBaseRepository.save(knowledgeBase)) {
            throw new BizException("Failed to create knowledge base");
        }
        return knowledgeBase.getId();
    }

    @Override
    public void updateKnowledgeBase(String knowledgeBaseId, UpsertKnowledgeBaseRequest request) {
        KnowledgeBaseDTO knowledgeBase = resourceAccessGuard.assertCanManageKnowledgeBase(
                UserContext.requireUser(),
                knowledgeBaseId
        );
        if (request == null) {
            throw new BizException("Update payload is required");
        }
        if (StringUtils.hasText(request.getName())) {
            knowledgeBase.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            knowledgeBase.setDescription(trimToNull(request.getDescription()));
        }
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        if (!knowledgeBaseRepository.update(knowledgeBase)) {
            throw new BizException("Failed to update knowledge base");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        KnowledgeBaseDTO knowledgeBase = resourceAccessGuard.assertCanManageKnowledgeBase(
                UserContext.requireUser(),
                knowledgeBaseId
        );
        List<KnowledgeDocumentDTO> documents = knowledgeDocumentRepository.findByKnowledgeBaseId(knowledgeBase.getId());
        List<String> storagePaths = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();
        for (KnowledgeDocumentDTO document : documents) {
            knowledgeChunkRepository.deleteByKnowledgeDocumentId(document.getId());
            if (!knowledgeDocumentRepository.deleteById(document.getId())) {
                throw new BizException("Failed to delete knowledge document: " + document.getId());
            }
            if (StringUtils.hasText(document.getId())) {
                documentIds.add(document.getId());
            }
            if (StringUtils.hasText(document.getStoragePath())) {
                storagePaths.add(document.getStoragePath());
            }
        }

        knowledgeBaseMilvusIndexer.deleteByKnowledgeBaseId(knowledgeBase.getId());
        agentKnowledgeBaseRepository.deleteByKnowledgeBaseId(knowledgeBase.getId());
        intentKnowledgeBaseRepository.deleteByKnowledgeBaseId(knowledgeBase.getId());
        if (!knowledgeBaseRepository.deleteById(knowledgeBase.getId())) {
            throw new BizException("Failed to delete knowledge base");
        }
        scheduleSignalCacheEvictionAfterCommit(documentIds);
        scheduleStoredFileDeletionAfterCommit(knowledgeBase.getId(), storagePaths);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private void scheduleStoredFileDeletionAfterCommit(String knowledgeBaseId, List<String> storagePaths) {
        if (storagePaths == null || storagePaths.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupStoredFilesQuietly(knowledgeBaseId, storagePaths);
                }
            });
            return;
        }
        cleanupStoredFilesQuietly(knowledgeBaseId, storagePaths);
    }

    private void scheduleSignalCacheEvictionAfterCommit(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictSignalCachesQuietly(documentIds);
                }
            });
            return;
        }
        evictSignalCachesQuietly(documentIds);
    }

    private void cleanupStoredFilesQuietly(String knowledgeBaseId, List<String> storagePaths) {
        for (String storagePath : storagePaths) {
            if (!StringUtils.hasText(storagePath)) {
                continue;
            }
            try {
                documentStorageService.deleteFile(storagePath);
            } catch (Exception ex) {
                log.warn("Failed to clean up stored knowledge-base document after delete: knowledgeBaseId={}, path={}, error={}",
                        knowledgeBaseId, storagePath, ex.getMessage());
            }
        }
    }

    private void evictSignalCachesQuietly(List<String> documentIds) {
        try {
            knowledgeDocumentSignalService.evictCaches(documentIds);
        } catch (Exception ex) {
            log.warn("Failed to evict knowledge document signal caches after knowledge-base delete: documentCount={}, error={}",
                    documentIds == null ? 0 : documentIds.size(), ex.getMessage());
        }
    }
}
