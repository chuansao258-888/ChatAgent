package com.yulong.chatagent.rag.service.impl;

import com.yulong.chatagent.rag.ingestion.MarkdownIngestionPipeline;
import com.yulong.chatagent.rag.repository.DocumentChunkRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionServiceImplTest {

    @Test
    void shouldDelegateMarkdownIngestionToPipeline() throws Exception {
        MarkdownIngestionPipeline pipeline = mock(MarkdownIngestionPipeline.class);
        DocumentChunkRepository repository = mock(DocumentChunkRepository.class);
        when(pipeline.ingest("kb-1", "doc-1", "docs/a.md")).thenReturn(2);

        DocumentIngestionServiceImpl service = new DocumentIngestionServiceImpl(pipeline, repository);

        service.ingestMarkdownDocument("kb-1", "doc-1", "docs/a.md");

        verify(pipeline).ingest("kb-1", "doc-1", "docs/a.md");
    }

    @Test
    void shouldDelegateChunkDeletionToRepository() {
        MarkdownIngestionPipeline pipeline = mock(MarkdownIngestionPipeline.class);
        DocumentChunkRepository repository = mock(DocumentChunkRepository.class);

        DocumentIngestionServiceImpl service = new DocumentIngestionServiceImpl(pipeline, repository);

        service.deleteDocumentChunks("doc-1");

        verify(repository).deleteByDocumentId("doc-1");
    }
}
