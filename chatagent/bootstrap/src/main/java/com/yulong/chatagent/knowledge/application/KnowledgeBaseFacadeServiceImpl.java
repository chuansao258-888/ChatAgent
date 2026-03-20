package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.knowledge.port.DocumentRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.DocumentDTO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.knowledge.model.request.CreateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateKnowledgeBaseRequest;
import com.yulong.chatagent.knowledge.model.response.CreateKnowledgeBaseResponse;
import com.yulong.chatagent.knowledge.model.response.GetKnowledgeBasesResponse;
import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import com.yulong.chatagent.knowledge.converter.KnowledgeBaseConverter;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of knowledge-base management.
 */
@Service
@AllArgsConstructor
public class KnowledgeBaseFacadeServiceImpl implements KnowledgeBaseFacadeService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final DocumentRepository documentRepository;
    private final DocumentFacadeService documentFacadeService;

    @Override
    public GetKnowledgeBasesResponse getKnowledgeBases() {
        String userId = requireCurrentUserId();
        List<KnowledgeBaseDTO> knowledgeBases = knowledgeBaseRepository.findByUserId(userId);
        List<KnowledgeBaseVO> result = new ArrayList<>();
        for (KnowledgeBaseDTO knowledgeBase : knowledgeBases) {
            result.add(knowledgeBaseConverter.toVO(knowledgeBase));
        }
        return GetKnowledgeBasesResponse.builder()
                .knowledgeBases(result.toArray(new KnowledgeBaseVO[0]))
                .build();
    }

    @Override
    public CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        String userId = requireCurrentUserId();
        KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(request);
        knowledgeBaseDTO.setUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        knowledgeBaseDTO.setCreatedAt(now);
        knowledgeBaseDTO.setUpdatedAt(now);

        if (!knowledgeBaseRepository.save(knowledgeBaseDTO)) {
            throw new BizException("Failed to create knowledge base");
        }

        return CreateKnowledgeBaseResponse.builder()
                .knowledgeBaseId(knowledgeBaseDTO.getId())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        String userId = requireCurrentUserId();
        KnowledgeBaseDTO knowledgeBase = requireOwnedKnowledgeBase(knowledgeBaseId, userId);

        List<DocumentDTO> documents = documentRepository.findByKnowledgeBaseIdAndUserId(knowledgeBaseId, userId);
        // Delegate document deletion so file storage, ingestion tasks, and chunks are
        // removed consistently through the document service path.
        for (DocumentDTO document : documents) {
            documentFacadeService.deleteDocument(document.getId());
        }

        if (!knowledgeBaseRepository.deleteById(knowledgeBaseId)) {
            throw new BizException("Failed to delete knowledge base");
        }
    }

    @Override
    public void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        KnowledgeBaseDTO existingKnowledgeBase = requireOwnedKnowledgeBase(knowledgeBaseId, requireCurrentUserId());

        knowledgeBaseConverter.updateDTOFromRequest(existingKnowledgeBase, request);
        existingKnowledgeBase.setUpdatedAt(LocalDateTime.now());

        if (!knowledgeBaseRepository.update(existingKnowledgeBase)) {
            throw new BizException("Failed to update knowledge base");
        }
    }

    private String requireCurrentUserId() {
        return UserContext.requireUser().getUserId();
    }

    private KnowledgeBaseDTO requireOwnedKnowledgeBase(String knowledgeBaseId, String userId) {
        KnowledgeBaseDTO knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId);
        if (knowledgeBase == null || !userId.equals(knowledgeBase.getUserId())) {
            throw new BizException("Knowledge base not found: " + knowledgeBaseId);
        }
        return knowledgeBase;
    }
}

