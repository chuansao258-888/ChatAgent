package com.yulong.chatagent.knowledge.controller;

import com.yulong.chatagent.knowledge.application.DocumentFacadeService;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.knowledge.model.request.CreateDocumentRequest;
import com.yulong.chatagent.knowledge.model.request.UpdateDocumentRequest;
import com.yulong.chatagent.knowledge.model.response.CreateDocumentResponse;
import com.yulong.chatagent.knowledge.model.response.GetDocumentsResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for knowledge-base document management.
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class DocumentController {

    private final DocumentFacadeService documentFacadeService;

    /**
     * Returns all documents.
     *
     * @return document list response
     */
    @GetMapping("/documents")
    public ApiResponse<GetDocumentsResponse> getDocuments() {
        return ApiResponse.success(documentFacadeService.getDocuments());
    }

    /**
     * Returns documents under one knowledge base.
     *
     * @param kbId knowledge base identifier
     * @return document list response
     */
    @GetMapping("/documents/kb/{kbId}")
    public ApiResponse<GetDocumentsResponse> getDocumentsByKbId(@PathVariable String kbId) {
        return ApiResponse.success(documentFacadeService.getDocumentsByKbId(kbId));
    }

    /**
     * Creates a new document entry.
     *
     * @param request create document request
     * @return created document response
     */
    @PostMapping("/documents")
    public ApiResponse<CreateDocumentResponse> createDocument(@RequestBody CreateDocumentRequest request) {
        return ApiResponse.success(documentFacadeService.createDocument(request));
    }

    /**
     * Uploads a file and creates the corresponding document.
     *
     * @param kbId knowledge base identifier
     * @param file uploaded file
     * @return created document response
     */
    @PostMapping("/documents/upload")
    public ApiResponse<CreateDocumentResponse> uploadDocument(@RequestParam("kbId") String kbId,
                                                              @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(documentFacadeService.uploadDocument(kbId, file));
    }

    /**
     * Deletes a document.
     *
     * @param documentId document identifier
     * @return empty success response
     */
    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String documentId) {
        documentFacadeService.deleteDocument(documentId);
        return ApiResponse.success();
    }

    /**
     * Updates document metadata.
     *
     * @param documentId document identifier
     * @param request update request payload
     * @return empty success response
     */
    @PatchMapping("/documents/{documentId}")
    public ApiResponse<Void> updateDocument(@PathVariable String documentId,
                                            @RequestBody UpdateDocumentRequest request) {
        documentFacadeService.updateDocument(documentId, request);
        return ApiResponse.success();
    }
}

