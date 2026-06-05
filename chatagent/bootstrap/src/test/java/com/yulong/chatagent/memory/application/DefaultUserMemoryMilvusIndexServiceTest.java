package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.rag.vector.milvus.MilvusProperties;
import com.yulong.chatagent.rag.vector.milvus.UserMemoryMilvusProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserMemoryMilvusIndexServiceTest {

    @Mock
    private MilvusClientV2 milvusClient;

    @Captor
    private ArgumentCaptor<UpsertReq> upsertCaptor;

    @Captor
    private ArgumentCaptor<SearchReq> searchCaptor;

    private MilvusProperties properties;
    private UserMemoryMilvusProperties userMemoryProperties;
    private DefaultUserMemoryMilvusIndexService service;

    @BeforeEach
    void setUp() {
        properties = new MilvusProperties();
        properties.setDatabase("default");
        properties.setDimension(1024);
        properties.setMetricType("COSINE");
        properties.setIndexType("AUTOINDEX");
        properties.setConsistencyLevel("BOUNDED");

        userMemoryProperties = new UserMemoryMilvusProperties();

        service = new DefaultUserMemoryMilvusIndexService(milvusClient, properties, userMemoryProperties);
    }

    @Test
    void shouldSkipEnsureCollectionWhenAlreadyExists() {
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);

        service.ensureCollection();

        verify(milvusClient, never()).createCollection(any());
    }

    @Test
    void shouldCreateCollectionWhenNotExists() {
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(false);
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        when(milvusClient.createSchema()).thenReturn(schema);

        service.ensureCollection();

        verify(milvusClient).createCollection(any());
    }

    @Test
    void shouldUpsertMemoryAndReturnTrue() {
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);

        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
        boolean result = service.upsertMemory("mem-1", "user-1", "preference", "active",
                "User prefers concise answers", "[\"style\"]", embedding);

        assertThat(result).isTrue();
        verify(milvusClient).upsert(upsertCaptor.capture());
        UpsertReq req = upsertCaptor.getValue();
        assertThat(req.getCollectionName()).isEqualTo("chat_user_memory");
    }

    @Test
    void shouldReturnFalseOnUpsertException() {
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(milvusClient.upsert(any(UpsertReq.class))).thenThrow(new RuntimeException("Connection refused"));

        boolean result = service.upsertMemory("mem-1", "user-1", "fact", "active",
                "A fact", "[]", new float[]{0.1f});

        assertThat(result).isFalse();
    }

    @Test
    void shouldReturnEmptySearchForNullInputs() {
        assertThat(service.search(null, new float[]{0.1f}, 3)).isEmpty();
        assertThat(service.search("user-1", null, 3)).isEmpty();
        assertThat(service.search("user-1", new float[0], 3)).isEmpty();
        assertThat(service.search("user-1", new float[]{0.1f}, 0)).isEmpty();
    }

    @Test
    void shouldSearchWithUserAndStatusFilter() {
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);

        // Build a fake search response with one hit.
        SearchResp.SearchResult hit = mockSearchResult(
                Map.of("memory_id", "mem-1", "type", "preference", "content", "Likes short answers"),
                0.95);
        SearchResp response = mockSearchResp(List.of(List.of(hit)));
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response);

        List<UserMemorySearchHit> results = service.search("user-1", new float[]{0.1f}, 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).memoryId()).isEqualTo("mem-1");
        assertThat(results.get(0).type()).isEqualTo("preference");
        assertThat(results.get(0).content()).isEqualTo("Likes short answers");
        assertThat(results.get(0).score()).isCloseTo(0.95, org.assertj.core.data.Offset.offset(0.001));

        // Verify filter contains user_id and status=active.
        verify(milvusClient).search(searchCaptor.capture());
        SearchReq searchReq = searchCaptor.getValue();
        assertThat(searchReq.getFilter()).contains("user-1");
        assertThat(searchReq.getFilter()).contains("active");
        assertThat(searchReq.getTopK()).isEqualTo(3);
        assertThat(searchReq.getAnnsField()).isEqualTo("embedding");
    }

    @Test
    void shouldReturnEmptyHitsOnNullSearchResults() {
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        SearchResp response = mockSearchResp(null);
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response);

        List<UserMemorySearchHit> results = service.search("user-1", new float[]{0.1f}, 3);
        assertThat(results).isEmpty();
    }

    @Test
    void shouldEscapeSpecialCharactersInUserId() {
        when(milvusClient.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        SearchResp response = mockSearchResp(List.of());
        when(milvusClient.search(any(SearchReq.class))).thenReturn(response);

        service.search("user\"with\\special", new float[]{0.1f}, 3);

        verify(milvusClient).search(searchCaptor.capture());
        String filter = searchCaptor.getValue().getFilter();
        assertThat(filter).contains("user\\\"with\\\\special");
    }

    // ── helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private SearchResp mockSearchResp(List<List<SearchResp.SearchResult>> results) {
        SearchResp resp = org.mockito.Mockito.mock(SearchResp.class);
        org.mockito.Mockito.when(resp.getSearchResults()).thenReturn(results);
        return resp;
    }

    private SearchResp.SearchResult mockSearchResult(Map<String, Object> entity, double score) {
        SearchResp.SearchResult result = org.mockito.Mockito.mock(SearchResp.SearchResult.class);
        org.mockito.Mockito.when(result.getEntity()).thenReturn(entity);
        org.mockito.Mockito.when(result.getScore()).thenReturn((float) score);
        return result;
    }
}
