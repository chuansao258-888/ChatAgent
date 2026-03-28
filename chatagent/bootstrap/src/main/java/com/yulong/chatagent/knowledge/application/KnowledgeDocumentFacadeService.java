package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.response.GetKnowledgeDocumentsResponse;
import com.yulong.chatagent.knowledge.model.response.UploadKnowledgeDocumentResponse;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentFacadeService {

    GetKnowledgeDocumentsResponse getKnowledgeDocuments(String knowledgeBaseId);

    UploadKnowledgeDocumentResponse uploadKnowledgeDocument(String knowledgeBaseId, MultipartFile file);

    UploadKnowledgeDocumentResponse replaceKnowledgeDocument(String knowledgeBaseId, String documentId, MultipartFile file);

    void archiveKnowledgeDocument(String knowledgeBaseId, String documentId);
}
