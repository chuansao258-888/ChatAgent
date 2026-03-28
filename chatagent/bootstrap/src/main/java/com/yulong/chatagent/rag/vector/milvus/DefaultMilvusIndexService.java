package com.yulong.chatagent.rag.vector.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusChunkDocument;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
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
 * Milvus-backed chunk index for session-scoped retrieval.
 */
@Service
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true")
@Slf4j
public class DefaultMilvusIndexService implements MilvusIndexService {

    private static final int ID_MAX_LENGTH = 64;
    private static final int FILE_NAME_MAX_LENGTH = 255;
    private static final int TEXT_MAX_LENGTH = 65_535;

    private final MilvusClientV2 milvusClient;
    private final MilvusProperties properties;
    private final Gson gson = new Gson();

    public DefaultMilvusIndexService(MilvusClientV2 milvusClient, MilvusProperties properties) {
        this.milvusClient = milvusClient;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        ensureCollection();
    }

    @Override
    public void ensureCollection() {
        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(properties.getCollection())
                .build());
        if (exists) {
            return;
        }

        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.CHUNK_ID)
                .dataType(DataType.VarChar)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.SESSION_ID)
                .dataType(DataType.VarChar)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.SESSION_FILE_ID)
                .dataType(DataType.VarChar)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.CHUNK_INDEX)
                .dataType(DataType.Int32)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.FILE_NAME)
                .dataType(DataType.VarChar)
                .maxLength(FILE_NAME_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.CONTEXT_TEXT)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.RETRIEVAL_TEXT)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        if (properties.isBm25Enabled()) {
            // Milvus built-in BM25 requires both an analyzer-enabled text field and a sparse
            // vector output field produced by the BM25 function.
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusCollectionFields.BM25_TEXT)
                    .dataType(DataType.VarChar)
                    .maxLength(TEXT_MAX_LENGTH)
                    .enableAnalyzer(Boolean.TRUE)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusCollectionFields.BM25_SPARSE)
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("bm25_retrieval_text")
                    .inputFieldNames(List.of(MilvusCollectionFields.BM25_TEXT))
                    .outputFieldNames(List.of(MilvusCollectionFields.BM25_SPARSE))
                    .build());
        }
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.ENABLED)
                .dataType(DataType.Bool)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.CREATED_AT)
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(MilvusCollectionFields.EMBEDDING)
                .dataType(DataType.FloatVector)
                .dimension(properties.getDimension())
                .build());

        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(IndexParam.builder()
                .fieldName(MilvusCollectionFields.EMBEDDING)
                .indexType(IndexParam.IndexType.valueOf(properties.getIndexType().toUpperCase(Locale.ROOT)))
                .metricType(IndexParam.MetricType.valueOf(properties.getMetricType().toUpperCase(Locale.ROOT)))
                .build());
        if (properties.isBm25Enabled()) {
            indexParams.add(IndexParam.builder()
                    .fieldName(MilvusCollectionFields.BM25_SPARSE)
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build());
        }

        milvusClient.createCollection(CreateCollectionReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(properties.getCollection())
                .collectionSchema(schema)
                .indexParams(indexParams)
                .consistencyLevel(parseConsistencyLevel())
                .build());

        log.info("Milvus collection created: database={}, collection={}, dimension={}, bm25Enabled={}",
                properties.getDatabase(), properties.getCollection(), properties.getDimension(), properties.isBm25Enabled());
    }

    @Override
    public void upsertChunks(List<MilvusChunkDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        ensureCollection();
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (MilvusChunkDocument chunk : chunks) {
            rows.add(toRow(chunk));
        }

        milvusClient.upsert(UpsertReq.builder()
                .collectionName(properties.getCollection())
                .data(rows)
                .build());

        log.info("Milvus chunk upsert completed: collection={}, rowCount={}",
                properties.getCollection(), rows.size());
    }

    @Override
    public void deleteBySessionFileId(String sessionFileId) {
        if (sessionFileId == null || sessionFileId.isBlank()) {
            return;
        }

        ensureCollection();
        milvusClient.delete(DeleteReq.builder()
                .collectionName(properties.getCollection())
                .filter(MilvusCollectionFields.SESSION_FILE_ID + " == \"" + escapeFilterValue(sessionFileId) + "\"")
                .build());
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        ensureCollection();
        milvusClient.delete(DeleteReq.builder()
                .collectionName(properties.getCollection())
                .filter(MilvusCollectionFields.SESSION_ID + " == \"" + escapeFilterValue(sessionId) + "\"")
                .build());
    }

    @Override
    public List<MilvusSearchHit> searchBySessionFileIds(List<String> sessionFileIds, float[] queryEmbedding, int topK) {
        if (sessionFileIds == null || sessionFileIds.isEmpty() || queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return List.of();
        }

        // Dense retrieval runs over the shared retrieval text embedding.
        ensureCollection();
        SearchResp response = milvusClient.search(SearchReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(properties.getCollection())
                .annsField(MilvusCollectionFields.EMBEDDING)
                .data(List.of(new FloatVec(queryEmbedding)))
                .filter(sessionFileIdsFilter(sessionFileIds))
                .topK(topK)
                .outputFields(outputFields())
                .consistencyLevel(parseConsistencyLevel())
                .build());
        return toHits(response);
    }

    @Override
    public List<MilvusSearchHit> searchBySessionFileIdsBm25(List<String> sessionFileIds, String queryText, int topK) {
        if (!properties.isBm25Enabled() || sessionFileIds == null || sessionFileIds.isEmpty() || queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }

        // Sparse retrieval delegates tokenization and BM25 vectorization to Milvus.
        ensureCollection();
        SearchResp response = milvusClient.search(SearchReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(properties.getCollection())
                .annsField(MilvusCollectionFields.BM25_SPARSE)
                .data(List.of(new EmbeddedText(queryText)))
                .filter(sessionFileIdsFilter(sessionFileIds))
                .topK(topK)
                .outputFields(outputFields())
                .consistencyLevel(parseConsistencyLevel())
                .build());
        return toHits(response);
    }

    @Override
    public List<MilvusSearchHit> searchBySession(String sessionId, float[] queryEmbedding, int topK) {
        if (sessionId == null || sessionId.isBlank() || queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return List.of();
        }

        ensureCollection();
        SearchResp response = milvusClient.search(SearchReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(properties.getCollection())
                .annsField(MilvusCollectionFields.EMBEDDING)
                .data(List.of(new FloatVec(queryEmbedding)))
                .filter(sessionFilter(sessionId))
                .topK(topK)
                .outputFields(outputFields())
                .consistencyLevel(parseConsistencyLevel())
                .build());
        return toHits(response);
    }

    @Override
    public List<MilvusSearchHit> searchBySessionBm25(String sessionId, String queryText, int topK) {
        if (!properties.isBm25Enabled() || sessionId == null || sessionId.isBlank() || queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }

        ensureCollection();
        SearchResp response = milvusClient.search(SearchReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(properties.getCollection())
                .annsField(MilvusCollectionFields.BM25_SPARSE)
                .data(List.of(new EmbeddedText(queryText)))
                .filter(sessionFilter(sessionId))
                .topK(topK)
                .outputFields(outputFields())
                .consistencyLevel(parseConsistencyLevel())
                .build());
        return toHits(response);
    }

    private JsonObject toRow(MilvusChunkDocument chunk) {
        JsonObject row = new JsonObject();
        row.addProperty(MilvusCollectionFields.CHUNK_ID, chunk.chunkId());
        row.addProperty(MilvusCollectionFields.SESSION_ID, chunk.sessionId());
        row.addProperty(MilvusCollectionFields.SESSION_FILE_ID, chunk.sessionFileId());
        row.addProperty(MilvusCollectionFields.CHUNK_INDEX, chunk.chunkIndex());
        row.addProperty(MilvusCollectionFields.FILE_NAME, chunk.fileName());
        row.addProperty(MilvusCollectionFields.CONTENT, chunk.content());
        row.addProperty(MilvusCollectionFields.CONTEXT_TEXT, chunk.contextText() == null ? "" : chunk.contextText());
        row.addProperty(MilvusCollectionFields.RETRIEVAL_TEXT, chunk.retrievalText());
        if (properties.isBm25Enabled()) {
            row.addProperty(MilvusCollectionFields.BM25_TEXT, chunk.bm25Text() == null ? "" : chunk.bm25Text());
        }
        row.addProperty(MilvusCollectionFields.ENABLED, chunk.enabled());
        row.addProperty(MilvusCollectionFields.CREATED_AT, chunk.createdAtEpochMillis());
        row.add(MilvusCollectionFields.EMBEDDING, gson.toJsonTree(toFloatList(chunk.embedding())));
        return row;
    }

    /**
     * Returns the fields needed by fusion, rerank, and prompt rendering.
     */
    private List<String> outputFields() {
        return List.of(
                MilvusCollectionFields.CHUNK_ID,
                MilvusCollectionFields.SESSION_FILE_ID,
                MilvusCollectionFields.CHUNK_INDEX,
                MilvusCollectionFields.FILE_NAME,
                MilvusCollectionFields.CONTENT,
                MilvusCollectionFields.CONTEXT_TEXT,
                MilvusCollectionFields.RETRIEVAL_TEXT
        );
    }

    private String sessionFileIdsFilter(List<String> sessionFileIds) {
        return MilvusCollectionFields.SESSION_FILE_ID + " in [" + joinQuotedValues(sessionFileIds) + "] and "
                + MilvusCollectionFields.ENABLED + " == true";
    }

    private String sessionFilter(String sessionId) {
        return MilvusCollectionFields.SESSION_ID + " == \"" + escapeFilterValue(sessionId) + "\" and "
                + MilvusCollectionFields.ENABLED + " == true";
    }

    private List<Float> toFloatList(float[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return result;
    }

    private List<MilvusSearchHit> toHits(SearchResp response) {
        List<List<SearchResp.SearchResult>> searchResults = response.getSearchResults();
        if (searchResults == null || searchResults.isEmpty()) {
            return List.of();
        }

        List<MilvusSearchHit> hits = new ArrayList<>();
        for (SearchResp.SearchResult result : searchResults.get(0)) {
            Object entity = result.getEntity();
            // Both dense and BM25 search responses are normalized into the same hit model so the
            // fusion layer can treat them uniformly.
            hits.add(new MilvusSearchHit(
                    stringifyField(entity, MilvusCollectionFields.CHUNK_ID),
                    stringifyField(entity, MilvusCollectionFields.SESSION_FILE_ID),
                    stringifyField(entity, MilvusCollectionFields.SESSION_FILE_ID),
                    intField(entity, MilvusCollectionFields.CHUNK_INDEX),
                    stringifyField(entity, MilvusCollectionFields.FILE_NAME),
                    null,
                    stringifyField(entity, MilvusCollectionFields.CONTENT),
                    stringifyField(entity, MilvusCollectionFields.CONTEXT_TEXT),
                    stringifyField(entity, MilvusCollectionFields.RETRIEVAL_TEXT),
                    (double) result.getScore()
            ));
        }
        return hits;
    }

    private ConsistencyLevel parseConsistencyLevel() {
        return ConsistencyLevel.valueOf(properties.getConsistencyLevel().toUpperCase(Locale.ROOT));
    }

    private String joinQuotedValues(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeFilterValue(values.get(i))).append('"');
        }
        return builder.toString();
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

    private int intField(Object entity, String fieldName) {
        if (entity instanceof Map<?, ?> map) {
            Object value = map.get(fieldName);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value != null) {
                return Integer.parseInt(String.valueOf(value));
            }
        }
        return 0;
    }
}
