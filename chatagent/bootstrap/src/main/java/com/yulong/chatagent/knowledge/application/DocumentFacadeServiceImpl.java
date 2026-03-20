package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ServiceException;
import com.yulong.chatagent.knowledge.port.DocumentRepository;
import com.yulong.chatagent.knowledge.port.IngestionTaskRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.DocumentDTO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.knowledge.model.request.CreateDocumentRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateDocumentRequest;
import com.yulong.chatagent.knowledge.model.response.CreateDocumentResponse;
import com.yulong.chatagent.knowledge.model.response.GetDocumentsResponse;
import com.yulong.chatagent.knowledge.model.vo.DocumentVO;
import com.yulong.chatagent.rag.service.DocumentIngestionService;
import com.yulong.chatagent.rag.service.DocumentStorageService;
import com.yulong.chatagent.support.dto.IngestionTaskDTO;
import com.yulong.chatagent.knowledge.converter.DocumentConverter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of document management and upload orchestration.
 */
@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentRepository documentRepository;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final DocumentIngestionService documentIngestionService;
    private final IngestionTaskService ingestionTaskService;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Override
    public GetDocumentsResponse getDocuments() {
        String userId = requireCurrentUserId();
        List<DocumentDTO> documents = documentRepository.findByUserId(userId);
        List<DocumentVO> result = new ArrayList<>();
        for (DocumentDTO document : documents) {
            result.add(documentConverter.toVO(document));
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        String userId = requireCurrentUserId();
        requireOwnedKnowledgeBase(kbId, userId);
        List<DocumentDTO> documents = documentRepository.findByKnowledgeBaseIdAndUserId(kbId, userId);
        List<DocumentVO> result = new ArrayList<>();
        for (DocumentDTO document : documents) {
            result.add(documentConverter.toVO(document));
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        String userId = requireCurrentUserId();
        requireOwnedKnowledgeBase(request.getKbId(), userId);
        DocumentDTO documentDTO = documentConverter.toDTO(request);
        documentDTO.setUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        documentDTO.setCreatedAt(now);
        documentDTO.setUpdatedAt(now);

        if (!documentRepository.save(documentDTO)) {
            throw new BizException("Failed to create document");
        }

        return CreateDocumentResponse.builder()
                .documentId(documentDTO.getId())
                .build();
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            String userId = requireCurrentUserId();
            requireOwnedKnowledgeBase(kbId, userId);
            if (file.isEmpty()) {
                throw new BizException("Uploaded file is empty");
            }

            String originalFilename = file.getOriginalFilename();
            String fileType = getFileType(originalFilename);
            long fileSize = file.getSize();
            String taskId;

            DocumentDTO documentDTO = DocumentDTO.builder()
                    .userId(userId)
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(fileType)
                    .size(fileSize)
                    .build();

            LocalDateTime now = LocalDateTime.now();
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            if (!documentRepository.save(documentDTO)) {
                throw new BizException("Failed to create document record");
            }

            String documentId = documentDTO.getId();
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            documentRepository.update(documentDTO);

            log.info("Document uploaded: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            // Markdown files trigger asynchronous ingestion. Other file types are stored
            // but currently not sent through a chunking/indexing pipeline.
            if ("md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType)) {
                IngestionTaskDTO pendingTask = ingestionTaskService.createPendingTask(kbId, documentId, filePath, fileType);
                taskId = pendingTask.getId();
                ingestionTaskService.runMarkdownTaskAsync(taskId, kbId, documentId, filePath);

            } else {
                taskId = null;
                log.warn("Unsupported ingestion file type: {}", fileType);
            }

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .taskId(taskId)
                    .build();
        } catch (IOException e) {
            log.error("Failed to store uploaded file", e);
            throw new ServiceException("Failed to store uploaded file");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(String documentId) {
        DocumentDTO document = requireOwnedDocument(documentId, requireCurrentUserId());
        if(ingestionTaskRepository.existsActiveTaskByDocumentId(documentId)){
            throw new BizException("Document ingestion task is still running: " + documentId);
        }
        ingestionTaskRepository.deleteByDocumentId(documentId);
        documentIngestionService.deleteDocumentChunks(documentId);

        try {
            if (document.getMetadata() != null && document.getMetadata().getFilePath() != null) {
                documentStorageService.deleteFile(document.getMetadata().getFilePath());

            }
        } catch (Exception e) {
            log.warn("Failed to delete document file, continue deleting record: documentId={}, error={}",
                    documentId, e.getMessage());
        }

        if (!documentRepository.deleteById(documentId)) {
            throw new BizException("Failed to delete document");
        }
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        DocumentDTO existingDocument = requireOwnedDocument(documentId, requireCurrentUserId());

        documentConverter.updateDTOFromRequest(existingDocument, request);
        existingDocument.setUpdatedAt(LocalDateTime.now());

        if (!documentRepository.update(existingDocument)) {
            throw new BizException("Failed to update document");
        }
    }

    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private String requireCurrentUserId() {
        return UserContext.requireUser().getUserId();
    }

    private KnowledgeBaseDTO requireOwnedKnowledgeBase(String kbId, String userId) {
        KnowledgeBaseDTO knowledgeBase = knowledgeBaseRepository.findById(kbId);
        if (knowledgeBase == null || !userId.equals(knowledgeBase.getUserId())) {
            throw new BizException("Knowledge base not found: " + kbId);
        }
        return knowledgeBase;
    }

    private DocumentDTO requireOwnedDocument(String documentId, String userId) {
        DocumentDTO document = documentRepository.findById(documentId);
        if (document == null || !userId.equals(document.getUserId())) {
            throw new BizException("Document not found: " + documentId);
        }
        return document;
    }
}
