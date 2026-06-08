package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.ingestion.SessionFileIndexedChunkAssembler;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusChunkDocument;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

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
 * Regression tests for the batch-embedding path in {@link SessionFileMilvusIndexer}.
 *
 * <p>Verifies that {@code upsert()} delegates to {@code embedBatch()} in 32-chunk
 * batches and never calls the single-text {@code embed()} fallback.
 */
@ExtendWith(MockitoExtension.class)
class SessionFileMilvusIndexerBatchTest {

    private static final int BATCH_SIZE = 32;

    @Mock
    private OllamaEmbeddingClient embeddingClient;

    @Mock
    private ObjectProvider<MilvusIndexService> indexServiceProvider;

    @Mock
    private MilvusIndexService indexService;

    @Mock
    private SessionFileIndexedChunkAssembler assembler;

    private final SessionScopedMilvusChunkMapper mapper = new SessionScopedMilvusChunkMapper();

    private SessionFileMilvusIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new SessionFileMilvusIndexer(
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

        ChatSessionFileDTO sessionFile = ChatSessionFileDTO.builder()
                .id("sf-1")
                .sessionId("session-1")
                .originalFilename("report.pdf")
                .build();
        List<FileChunkDTO> chunks = IntStream.range(0, totalChunks)
                .mapToObj(i -> FileChunkDTO.builder()
                        .id("chunk-" + i)
                        .sessionFileId("sf-1")
                        .chunkIndex(i)
                        .content("content-" + i)
                        .build())
                .toList();

        // --- Act ---
        indexer.upsert("session-1", sessionFile, chunks);

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
        ArgumentCaptor<List<MilvusChunkDocument>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(indexService).upsertChunks(docsCaptor.capture());
        assertThat(docsCaptor.getValue()).hasSize(totalChunks);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static List<IndexedChunkDocument> buildIndexedChunks(int count) {
        List<IndexedChunkDocument> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new IndexedChunkDocument(
                    "chunk-" + i,                    // chunkId
                    RagSourceType.SESSION_FILE,      // sourceType
                    "session-1",                     // scopeId
                    "sf-1",                          // sourceId
                    "sf-1",                          // documentId
                    "report.pdf",                    // documentName
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
