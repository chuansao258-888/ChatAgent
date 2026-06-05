package com.yulong.chatagent.memory.application;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.yulong.chatagent.rag.vector.milvus.MilvusProperties;
import com.yulong.chatagent.rag.vector.milvus.UserMemoryMilvusCollectionFields;
import com.yulong.chatagent.rag.vector.milvus.UserMemoryMilvusProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Milvus-backed index for L3 user long-term memory recall.
 *
 * <p>Active only when both {@code milvus.enabled=true} and
 * {@code chatagent.memory.l3.enabled=true}.
 */
@Service
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "chatagent.memory.l3", name = "enabled", havingValue = "true")
@Slf4j
public class DefaultUserMemoryMilvusIndexService implements UserMemoryIndexService {

    private static final int ID_MAX_LENGTH = 64;
    private static final int TYPE_MAX_LENGTH = 32;
    private static final int TEXT_MAX_LENGTH = 65_535;

    private final MilvusClientV2 milvusClient;
    private final MilvusProperties properties;
    private final UserMemoryMilvusProperties userMemoryProperties;
    private final Gson gson = new Gson();

    public DefaultUserMemoryMilvusIndexService(MilvusClientV2 milvusClient,
                                               MilvusProperties properties,
                                               UserMemoryMilvusProperties userMemoryProperties) {
        this.milvusClient = milvusClient;
        this.properties = properties;
        this.userMemoryProperties = userMemoryProperties;
    }

    @PostConstruct
    public void initialize() {
        ensureCollection();
    }

    @Override
    public void ensureCollection() {
        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(userMemoryProperties.getCollection())
                .build());
        if (exists) {
            return;
        }

        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.MEMORY_ID)
                .dataType(DataType.VarChar)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.USER_ID)
                .dataType(DataType.VarChar)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.TYPE)
                .dataType(DataType.VarChar)
                .maxLength(TYPE_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.STATUS)
                .dataType(DataType.VarChar)
                .maxLength(TYPE_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.TAGS)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.UPDATED_AT)
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(UserMemoryMilvusCollectionFields.EMBEDDING)
                .dataType(DataType.FloatVector)
                .dimension(properties.getDimension())
                .build());

        List<IndexParam> indexParams = List.of(IndexParam.builder()
                .fieldName(UserMemoryMilvusCollectionFields.EMBEDDING)
                .indexType(IndexParam.IndexType.valueOf(properties.getIndexType().toUpperCase(Locale.ROOT)))
                .metricType(IndexParam.MetricType.valueOf(properties.getMetricType().toUpperCase(Locale.ROOT)))
                .build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(userMemoryProperties.getCollection())
                .collectionSchema(schema)
                .indexParams(indexParams)
                .consistencyLevel(parseConsistencyLevel())
                .build());

        log.info("User-memory Milvus collection created: database={}, collection={}, dimension={}",
                properties.getDatabase(), userMemoryProperties.getCollection(), properties.getDimension());
    }

    @Override
    public boolean upsertMemory(String memoryId, String userId, String type, String status,
                                String content, String tagsJson, float[] embedding) {
        try {
            ensureCollection();
            JsonObject row = new JsonObject();
            row.addProperty(UserMemoryMilvusCollectionFields.MEMORY_ID, memoryId);
            row.addProperty(UserMemoryMilvusCollectionFields.USER_ID, userId);
            row.addProperty(UserMemoryMilvusCollectionFields.TYPE, type);
            row.addProperty(UserMemoryMilvusCollectionFields.STATUS, status);
            row.addProperty(UserMemoryMilvusCollectionFields.CONTENT, content);
            row.addProperty(UserMemoryMilvusCollectionFields.TAGS, tagsJson != null ? tagsJson : "[]");
            row.addProperty(UserMemoryMilvusCollectionFields.UPDATED_AT, System.currentTimeMillis());
            row.add(UserMemoryMilvusCollectionFields.EMBEDDING, gson.toJsonTree(toFloatList(embedding)));

            milvusClient.upsert(UpsertReq.builder()
                    .collectionName(userMemoryProperties.getCollection())
                    .data(List.of(row))
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("User-memory Milvus upsert failed: memoryId={}, error={}", memoryId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<UserMemorySearchHit> search(String userId, float[] queryEmbedding, int topK) {
        if (userId == null || queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return List.of();
        }

        ensureCollection();
        String filter = UserMemoryMilvusCollectionFields.USER_ID + " == \"" + escapeFilterValue(userId) + "\" and "
                + UserMemoryMilvusCollectionFields.STATUS + " == \"active\"";

        SearchResp response = milvusClient.search(SearchReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(userMemoryProperties.getCollection())
                .annsField(UserMemoryMilvusCollectionFields.EMBEDDING)
                .data(List.of(new FloatVec(queryEmbedding)))
                .filter(filter)
                .topK(topK)
                .outputFields(outputFields())
                .consistencyLevel(parseConsistencyLevel())
                .build());
        return toHits(response);
    }

    private List<String> outputFields() {
        return List.of(
                UserMemoryMilvusCollectionFields.MEMORY_ID,
                UserMemoryMilvusCollectionFields.TYPE,
                UserMemoryMilvusCollectionFields.CONTENT
        );
    }

    private List<UserMemorySearchHit> toHits(SearchResp response) {
        List<List<SearchResp.SearchResult>> searchResults = response.getSearchResults();
        if (searchResults == null || searchResults.isEmpty()) {
            return List.of();
        }

        List<UserMemorySearchHit> hits = new ArrayList<>();
        for (SearchResp.SearchResult result : searchResults.get(0)) {
            Object entity = result.getEntity();
            hits.add(new UserMemorySearchHit(
                    stringifyField(entity, UserMemoryMilvusCollectionFields.MEMORY_ID),
                    stringifyField(entity, UserMemoryMilvusCollectionFields.TYPE),
                    stringifyField(entity, UserMemoryMilvusCollectionFields.CONTENT),
                    result.getScore()
            ));
        }
        return hits;
    }

    private ConsistencyLevel parseConsistencyLevel() {
        return ConsistencyLevel.valueOf(properties.getConsistencyLevel().toUpperCase(Locale.ROOT));
    }

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String stringifyField(Object entity, String fieldName) {
        if (entity instanceof Map<?, ?> map) {
            Object value = map.get(fieldName);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private List<Float> toFloatList(float[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return result;
    }
}
