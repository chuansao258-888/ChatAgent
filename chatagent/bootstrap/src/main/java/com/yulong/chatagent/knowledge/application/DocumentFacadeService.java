package com.yulong.chatagent.knowledge.application;

import com.yulong.chatagent.knowledge.model.request.CreateDocumentRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateDocumentRequest;
import com.yulong.chatagent.knowledge.model.response.CreateDocumentResponse;
import com.yulong.chatagent.knowledge.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * Facade for document management inside a knowledge base.
 */
public interface DocumentFacadeService {

    /**
     * Lists all documents visible to the current scope.
     *
     * @return document list response
     */
    GetDocumentsResponse getDocuments();

    /**
     * Lists documents within one knowledge base.
     *
     * @param kbId knowledge base identifier
     * @return document list response
     */
    GetDocumentsResponse getDocumentsByKbId(String kbId);

    /**
     * Creates a logical document record without uploading a file.
     *
     * @param request create document request
     * @return created document response
     */
    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    /**
     * Uploads a physical document file and creates the matching document record.
     *
     * @param kbId knowledge base identifier
     * @param file uploaded file
     * @return created document response
     */
    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    /**
     * Deletes a document and its related resources.
     *
     * @param documentId document identifier
     */
    void deleteDocument(String documentId);

    /**
     * Updates mutable document metadata.
     *
     * @param documentId document identifier
     * @param request update request payload
     */
    void updateDocument(String documentId, UpdateDocumentRequest request);
}

