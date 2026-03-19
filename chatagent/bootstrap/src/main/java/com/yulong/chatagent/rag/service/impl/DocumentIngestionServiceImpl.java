package com.yulong.chatagent.rag.service.impl;

import com.yulong.chatagent.errorcode.BaseErrorCode;
import com.yulong.chatagent.exception.RemoteException;
import com.yulong.chatagent.exception.ServiceException;
import com.yulong.chatagent.rag.ingestion.MarkdownIngestionPipeline;
import com.yulong.chatagent.rag.repository.DocumentChunkRepository;
import com.yulong.chatagent.rag.service.DocumentIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
/**
 * Default service for converting stored markdown documents into persisted vector chunks.
 */
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private final MarkdownIngestionPipeline markdownIngestionPipeline;
    private final DocumentChunkRepository documentChunkRepository;

    public DocumentIngestionServiceImpl(MarkdownIngestionPipeline markdownIngestionPipeline,
                                        DocumentChunkRepository documentChunkRepository) {
        this.markdownIngestionPipeline = markdownIngestionPipeline;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Override
    public int ingestMarkdownDocument(String kbId, String documentId, String filePath) {
        log.info("Start ingesting markdown document: kbId={}, documentId={}, filePath={}",
                kbId, documentId, filePath);
        try {
            int chunkCount = markdownIngestionPipeline.ingest(kbId, documentId, filePath);
            log.info("Markdown ingestion completed: documentId={}, chunks={}", documentId, chunkCount);
            return chunkCount;
        } catch (RemoteException e) {
            log.error("Remote ingestion failure: documentId={}", documentId, e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to ingest markdown document: documentId={}", documentId, e);
            throw new ServiceException(
                    BaseErrorCode.SERVICE_ERROR,
                    "Failed to ingest markdown document: " + documentId,
                    e
            );
        }
    }

    @Override
    public void deleteDocumentChunks(String documentId) {
        documentChunkRepository.deleteByDocumentId(documentId);
    }
}
