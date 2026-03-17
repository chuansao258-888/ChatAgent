package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.CreateDocumentRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateDocumentRequest;
import com.yulong.chatagent.knowledge.model.response.CreateDocumentResponse;
import com.yulong.chatagent.knowledge.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentFacadeService {

    GetDocumentsResponse getDocuments();

    GetDocumentsResponse getDocumentsByKbId(String kbId);

    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}

