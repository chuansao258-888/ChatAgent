package com.yulong.chatagent.rag.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentEnhancementRepository;
import com.yulong.chatagent.support.dto.KnowledgeDocumentEnhancementDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentSignalServiceTest {

    @Mock
    private KnowledgeDocumentEnhancementRepository repository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private KnowledgeDocumentSignalService signalService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        KnowledgeDocumentSignalProperties properties = new KnowledgeDocumentSignalProperties();
        properties.setCacheTtlMinutes(30);
        objectMapper = new ObjectMapper();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        signalService = new KnowledgeDocumentSignalService(repository, stringRedisTemplate, objectMapper, properties);
    }

    @Test
    void shouldLoadSignalsFromRepositoryOnRedisMissAndBackfillCache() {
        when(valueOperations.multiGet(List.of("doc_signal:doc-1"))).thenReturn(Collections.singletonList(null));
        when(repository.findByKnowledgeDocumentIds(List.of("doc-1"))).thenReturn(List.of(
                KnowledgeDocumentEnhancementDTO.builder()
                        .knowledgeDocumentId("doc-1")
                        .enhancerCacheKey("cache-1")
                        .keywords(List.of("leave policy"))
                        .questions(List.of("How many leave days can be carried over?"))
                        .metadata(Map.of("doc_type", "policy", "contains_pii", false))
                        .build()
        ));

        Map<String, KnowledgeDocumentSignal> loaded = signalService.loadByDocumentIds(List.of("doc-1"));

        assertThat(loaded).containsKey("doc-1");
        assertThat(loaded.get("doc-1").keywords()).containsExactly("leave policy");
        verify(valueOperations).set(eq("doc_signal:doc-1"), any(String.class), any(java.time.Duration.class));
    }

    @Test
    void shouldAttachCachedSignalsToCandidates() throws Exception {
        when(valueOperations.multiGet(List.of("doc_signal:doc-1"))).thenReturn(List.of(
                objectMapper.writeValueAsString(new KnowledgeDocumentSignal(
                        "doc-1",
                        "cache-1",
                        List.of("leave policy"),
                        List.of("How many leave days can be carried over?"),
                        Map.of("doc_type", "policy", "contains_pii", false)
                ))
        ));

        List<com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit> enriched = signalService.attachSignals(List.of(
                com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit.builder()
                        .chunkId("chunk-1")
                        .documentId("doc-1")
                        .content("carry over rules")
                        .build()
        ));

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0).documentKeywords()).containsExactly("leave policy");
        assertThat(enriched.get(0).documentQuestions()).containsExactly("How many leave days can be carried over?");
    }

    @Test
    void shouldCacheEmptySignalToPreventRepeatedDatabaseMisses() throws Exception {
        Map<String, String> cache = new LinkedHashMap<>();
        when(valueOperations.multiGet(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> keys = invocation.getArgument(0);
            return keys.stream().map(cache::get).toList();
        });
        when(repository.findByKnowledgeDocumentIds(List.of("doc-legacy"))).thenReturn(List.of());
        doAnswer(invocation -> {
            cache.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(any(String.class), any(String.class), any(java.time.Duration.class));

        Map<String, KnowledgeDocumentSignal> firstLoad = signalService.loadByDocumentIds(List.of("doc-legacy"));
        Map<String, KnowledgeDocumentSignal> secondLoad = signalService.loadByDocumentIds(List.of("doc-legacy"));

        assertThat(firstLoad).containsKey("doc-legacy");
        assertThat(firstLoad.get("doc-legacy").keywords()).isEmpty();
        assertThat(secondLoad).containsKey("doc-legacy");
        assertThat(secondLoad.get("doc-legacy").questions()).isEmpty();
        verify(repository).findByKnowledgeDocumentIds(List.of("doc-legacy"));
    }
}
