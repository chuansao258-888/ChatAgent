package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.response.GetKnowledgeDocumentsResponse;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentFacadeService {

    GetKnowledgeDocumentsResponse getKnowledgeDocuments(String knowledgeBaseId);

    UploadKnowledgeDocumentResponse uploadKnowledgeDocument(String knowledgeBaseId, MultipartFile file);

    UploadKnowledgeDocumentResponse replaceKnowledgeDocument(String knowledgeBaseId, String documentId, MultipartFile file);

    void deleteKnowledgeDocument(String knowledgeBaseId, String documentId);

    KnowledgeDocumentDTO getKnowledgeDocument(String documentId);

    void ingestKnowledgeDocument(KnowledgeDocumentDTO document);

    void markIngestionFailed(String documentId, String error);
}
