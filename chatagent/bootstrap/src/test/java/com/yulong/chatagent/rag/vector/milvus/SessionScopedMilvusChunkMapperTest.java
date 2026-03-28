package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusChunkDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionScopedMilvusChunkMapperTest {

    private final SessionScopedMilvusChunkMapper mapper = new SessionScopedMilvusChunkMapper();

    @Test
    void shouldAdaptSessionFileChunkIntoCurrentMilvusRowShape() {
        IndexedChunkDocument document = new IndexedChunkDocument(
                "chunk-1",
                RagSourceType.SESSION_FILE,
                "session-1",
                "file-1",
                "file-1",
                "Handbook.md",
                5,
                "Policies / Leave",
                "Employees receive 14 days of annual leave.",
                "Paid leave policy",
                "Paid leave policy\n\nEmployees receive 14 days of annual leave.",
                true,
                123456789L
        );

        MilvusChunkDocument mapped = mapper.toMilvusDocument(document, new float[]{0.1f, 0.2f});

        assertThat(mapped.sessionId()).isEqualTo("session-1");
        assertThat(mapped.sessionFileId()).isEqualTo("file-1");
        assertThat(mapped.fileName()).isEqualTo("Handbook.md");
        assertThat(mapped.chunkIndex()).isEqualTo(5);
        assertThat(mapped.contextText()).isEqualTo("Paid leave policy");
        assertThat(mapped.retrievalText()).contains("annual leave");
        assertThat(mapped.bm25Text()).isEqualTo(mapped.retrievalText());
    }

    @Test
    void shouldRejectNonSessionFileSourcesForCurrentCollection() {
        IndexedChunkDocument document = new IndexedChunkDocument(
                "chunk-2",
                RagSourceType.KNOWLEDGE_BASE,
                "kb-1",
                "kb-doc-1",
                "kb-doc-1",
                "Policies",
                0,
                null,
                "Chunk content",
                null,
                "Chunk content",
                true,
                1L
        );

        assertThatThrownBy(() -> mapper.toMilvusDocument(document, new float[]{0.1f}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session-file");
    }
}
