package com.yulong.chatagent.rag.retrieve;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.repository.KnowledgeChunkSearchRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChunkSimilaritySearcherTest {

    @Test
    void shouldEmbedFormatAndSearchWithConfiguredTopK() {
        OllamaEmbeddingClient embeddingClient = mock(OllamaEmbeddingClient.class);
        KnowledgeChunkSearchRepository repository = mock(KnowledgeChunkSearchRepository.class);
        PgVectorFormatter formatter = mock(PgVectorFormatter.class);
        float[] embedding = new float[]{1.0f, 2.0f};

        when(embeddingClient.embed("how to deploy")).thenReturn(embedding);
        when(formatter.format(embedding)).thenReturn("[1.0,2.0]");
        when(repository.similaritySearch("kb-1", "[1.0,2.0]", 5)).thenReturn(List.of("chunk-a", "chunk-b"));

        KnowledgeChunkSimilaritySearcher searcher =
                new KnowledgeChunkSimilaritySearcher(embeddingClient, repository, formatter, 5);

        List<String> results = searcher.search("kb-1", "how to deploy");

        assertThat(results).containsExactly("chunk-a", "chunk-b");
        verify(embeddingClient).embed("how to deploy");
        verify(formatter).format(embedding);
        verify(repository).similaritySearch("kb-1", "[1.0,2.0]", 5);
    }
}
