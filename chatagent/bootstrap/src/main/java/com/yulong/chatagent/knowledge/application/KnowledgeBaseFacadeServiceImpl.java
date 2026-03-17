package com.yulong.chatagent.knowledge.application;

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
import com.yulong.chatagent.support.persistence.converter.KnowledgeBaseConverter;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class KnowledgeBaseFacadeServiceImpl implements KnowledgeBaseFacadeService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final DocumentRepository documentRepository;
    private final DocumentFacadeService documentFacadeService;

    @Override
    public GetKnowledgeBasesResponse getKnowledgeBases() {
        List<KnowledgeBaseDTO> knowledgeBases = knowledgeBaseRepository.findAll();
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
        KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(request);
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
        KnowledgeBaseDTO knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new BizException("Knowledge base not found: " + knowledgeBaseId);
        }

        List<DocumentDTO> documents = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        for (DocumentDTO document : documents) {
            documentFacadeService.deleteDocument(document.getId());
        }

        if (!knowledgeBaseRepository.deleteById(knowledgeBaseId)) {
            throw new BizException("Failed to delete knowledge base");
        }
    }

    @Override
    public void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        KnowledgeBaseDTO existingKnowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId);
        if (existingKnowledgeBase == null) {
            throw new BizException("Knowledge base not found: " + knowledgeBaseId);
        }

        knowledgeBaseConverter.updateDTOFromRequest(existingKnowledgeBase, request);
        existingKnowledgeBase.setUpdatedAt(LocalDateTime.now());

        if (!knowledgeBaseRepository.update(existingKnowledgeBase)) {
            throw new BizException("Failed to update knowledge base");
        }
    }
}

