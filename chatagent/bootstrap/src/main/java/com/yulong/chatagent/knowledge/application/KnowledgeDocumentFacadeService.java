package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.response.GetKnowledgeDocumentsResponse;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentFacadeService {

    GetKnowledgeDocumentsResponse getKnowledgeDocuments(String knowledgeBaseId);

    UploadKnowledgeDocumentResponse uploadKnowledgeDocument(String knowledgeBaseId, MultipartFile file);

    UploadKnowledgeDocumentResponse replaceKnowledgeDocument(String knowledgeBaseId, String documentId, MultipartFile file);

    void deleteKnowledgeDocument(String knowledgeBaseId, String documentId);

    com.yulong.chatagent.support.persistence.entity.KnowledgeDocument getKnowledgeDocument(String documentId);

    void ingestKnowledgeDocument(com.yulong.chatagent.support.persistence.entity.KnowledgeDocument document);

    void markIngestionFailed(String documentId, String error);
}
