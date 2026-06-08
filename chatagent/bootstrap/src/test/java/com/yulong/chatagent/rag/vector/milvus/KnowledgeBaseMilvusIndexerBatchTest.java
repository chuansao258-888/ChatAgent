package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.ingestion.KnowledgeBaseIndexedChunkAssembler;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.vector.milvus.model.KnowledgeBaseMilvusChunkDocument;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the batch-embedding path in {@link KnowledgeBaseMilvusIndexer}.
 *
 * <p>Verifies that {@code upsert()} delegates to {@code embedBatch()} in 32-chunk
 * batches and never calls the single-text {@code embed()} fallback.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseMilvusIndexerBatchTest {

    private static final int BATCH_SIZE = 32;

    @Mock
    private OllamaEmbeddingClient embeddingClient;

    @Mock
    private ObjectProvider<KnowledgeBaseMilvusIndexService> indexServiceProvider;

    @Mock
    private KnowledgeBaseMilvusIndexService indexService;

    @Mock
    private KnowledgeBaseIndexedChunkAssembler assembler;

    private final KnowledgeBaseMilvusChunkMapper mapper = new KnowledgeBaseMilvusChunkMapper();

    private KnowledgeBaseMilvusIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new KnowledgeBaseMilvusIndexer(
                embeddingClient, indexServiceProvider, assembler, mapper);
    }

    @Test
    void upsert_callsEmbedBatchInBatches_andNeverCallsEmbed() {
        // --- Arrange: 33 chunks -> two batches (32 + 1) ---
        int totalChunks = 33;
        List<IndexedChunkDocument> indexedChunks = buildIndexedChunks(totalChunks);

        when(indexServiceProvider.getIfAvailable()).thenReturn(indexService);
        when(assembler.assemble(anyString(), any(), anyList())).thenReturn(indexedChunks);

        // Dynamic mock: return zero vectors matching the input batch size
        when(embeddingClient.embedBatch(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> texts = invocation.getArgument(0);
            float[][] result = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                result[i] = new float[1024];
            }
            return result;
        });

        KnowledgeDocumentDTO document = KnowledgeDocumentDTO.builder()
                .id("doc-1")
                .knowledgeBaseId("kb-1")
                .build();
        List<KnowledgeChunkDTO> chunks = IntStream.range(0, totalChunks)
                .mapToObj(i -> KnowledgeChunkDTO.builder()
                        .id("chunk-" + i)
                        .knowledgeDocumentId("doc-1")
                        .chunkIndex(i)
                        .content("content-" + i)
                        .build())
                .toList();

        // --- Act ---
        indexer.upsert("kb-1", document, chunks);

        // --- Assert: embedBatch called exactly twice ---
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient, times(2)).embedBatch(batchCaptor.capture());
        verify(embeddingClient, never()).embed(anyString());

        List<List<String>> batches = batchCaptor.getAllValues();
        assertThat(batches).hasSize(2);

        // First batch: 32 chunks, text order preserved
        List<String> firstBatch = batches.get(0);
        assertThat(firstBatch).hasSize(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            assertThat(firstBatch.get(i)).isEqualTo("retrieval-text-" + i);
        }

        // Second batch: 1 chunk
        List<String> secondBatch = batches.get(1);
        assertThat(secondBatch).hasSize(1);
        assertThat(secondBatch.get(0)).isEqualTo("retrieval-text-32");

        // Index service received all 33 mapped documents
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeBaseMilvusChunkDocument>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(indexService).upsertChunks(docsCaptor.capture());
        assertThat(docsCaptor.getValue()).hasSize(totalChunks);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static List<IndexedChunkDocument> buildIndexedChunks(int count) {
        List<IndexedChunkDocument> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new IndexedChunkDocument(
                    "chunk-" + i,                    // chunkId
                    RagSourceType.KNOWLEDGE_BASE,    // sourceType
                    "kb-1",                          // scopeId
                    "doc-1",                         // sourceId
                    "doc-1",                         // documentId
                    "test-doc.pdf",                  // documentName
                    i,                               // chunkIndex
                    null,                            // sectionPath
                    "content-" + i,                  // content
                    null,                            // contextText
                    "retrieval-text-" + i,           // retrievalText
                    true,                            // enabled
                    System.currentTimeMillis()       // createdAtEpochMillis
            ));
        }
        return result;
    }
}
