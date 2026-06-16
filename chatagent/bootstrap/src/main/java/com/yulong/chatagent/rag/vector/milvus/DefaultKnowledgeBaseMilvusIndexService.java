package com.yulong.chatagent.rag.vector.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.yulong.chatagent.rag.vector.milvus.model.KnowledgeBaseMilvusChunkDocument;
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
 * Milvus-backed chunk index for knowledge-base retrieval.
 */
@Service
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true")
@Slf4j
public class DefaultKnowledgeBaseMilvusIndexService implements KnowledgeBaseMilvusIndexService {

    private static final int ID_MAX_LENGTH = 64;
    private static final int DOCUMENT_NAME_MAX_LENGTH = 255;
    private static final int TEXT_MAX_LENGTH = 65_535;

    private final MilvusClientV2 milvusClient;
    private final MilvusProperties properties;
    private final KnowledgeBaseMilvusProperties knowledgeBaseProperties;
    private final Gson gson = new Gson();

    public DefaultKnowledgeBaseMilvusIndexService(MilvusClientV2 milvusClient,
                                                  MilvusProperties properties,
                                                  KnowledgeBaseMilvusProperties knowledgeBaseProperties) {
        this.milvusClient = milvusClient;
        this.properties = properties;
        this.knowledgeBaseProperties = knowledgeBaseProperties;
    }

    @PostConstruct
    public void initialize() {
        ensureCollection();
    }

    @Override
    public void ensureCollection() {
        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(knowledgeBaseProperties.getCollection())
                .build());
        if (exists) {
            return;
        }

        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.CHUNK_ID)
                .dataType(DataType.VarChar)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.KNOWLEDGE_BASE_ID)
                .dataType(DataType.VarChar)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.DOCUMENT_ID)
                .dataType(DataType.VarChar)
                .maxLength(ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.CHUNK_INDEX)
                .dataType(DataType.Int32)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.DOCUMENT_NAME)
                .dataType(DataType.VarChar)
                .maxLength(DOCUMENT_NAME_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.SECTION_PATH)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.CONTEXT_TEXT)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.RETRIEVAL_TEXT)
                .dataType(DataType.VarChar)
                .maxLength(TEXT_MAX_LENGTH)
                .build());
        if (properties.isBm25Enabled()) {
            schema.addField(AddFieldReq.builder()
                    .fieldName(KnowledgeBaseMilvusCollectionFields.BM25_TEXT)
                    .dataType(DataType.VarChar)
                    .maxLength(TEXT_MAX_LENGTH)
                    .enableAnalyzer(Boolean.TRUE)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(KnowledgeBaseMilvusCollectionFields.BM25_SPARSE)
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("bm25_knowledge_base_retrieval_text")
                    .inputFieldNames(List.of(KnowledgeBaseMilvusCollectionFields.BM25_TEXT))
                    .outputFieldNames(List.of(KnowledgeBaseMilvusCollectionFields.BM25_SPARSE))
                    .build());
        }
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.ENABLED)
                .dataType(DataType.Bool)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.CREATED_AT)
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.EMBEDDING)
                .dataType(DataType.FloatVector)
                .dimension(properties.getDimension())
                .build());

        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(IndexParam.builder()
                .fieldName(KnowledgeBaseMilvusCollectionFields.EMBEDDING)
                .indexType(IndexParam.IndexType.valueOf(properties.getIndexType().toUpperCase(Locale.ROOT)))
                .metricType(IndexParam.MetricType.valueOf(properties.getMetricType().toUpperCase(Locale.ROOT)))
                .build());
        if (properties.isBm25Enabled()) {
            indexParams.add(IndexParam.builder()
                    .fieldName(KnowledgeBaseMilvusCollectionFields.BM25_SPARSE)
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build());
        }

        milvusClient.createCollection(CreateCollectionReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(knowledgeBaseProperties.getCollection())
                .collectionSchema(schema)
                .indexParams(indexParams)
                .consistencyLevel(parseConsistencyLevel())
                .build());

        log.info("Knowledge-base Milvus collection created: database={}, collection={}, dimension={}, bm25Enabled={}",
                properties.getDatabase(), knowledgeBaseProperties.getCollection(), properties.getDimension(), properties.isBm25Enabled());
    }

    @Override
    public void upsertChunks(List<KnowledgeBaseMilvusChunkDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.info("Knowledge-base Milvus chunk upsert skipped: collection={}, reason=no-chunks",
                    knowledgeBaseProperties.getCollection());
            return;
        }

        ensureCollection();
        log.info("Knowledge-base Milvus chunk upsert started: collection={}, rowCount={}",
                knowledgeBaseProperties.getCollection(),
                chunks.size());
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (KnowledgeBaseMilvusChunkDocument chunk : chunks) {
            rows.add(toRow(chunk));
        }

        milvusClient.upsert(UpsertReq.builder()
                .collectionName(knowledgeBaseProperties.getCollection())
                .data(rows)
                .build());

        log.info("Knowledge-base Milvus chunk upsert completed: collection={}, rowCount={}",
                knowledgeBaseProperties.getCollection(), rows.size());
    }

    @Override
    public void deleteByKnowledgeDocumentId(String knowledgeDocumentId) {
        if (knowledgeDocumentId == null || knowledgeDocumentId.isBlank()) {
            log.info("Knowledge-base Milvus delete skipped: collection={}, reason=blank-document-id",
                    knowledgeBaseProperties.getCollection());
            return;
        }

        ensureCollection();
        log.info("Knowledge-base Milvus delete request started: collection={}, knowledgeDocumentId={}",
                knowledgeBaseProperties.getCollection(),
                knowledgeDocumentId);
        milvusClient.delete(DeleteReq.builder()
                .collectionName(knowledgeBaseProperties.getCollection())
                .filter(KnowledgeBaseMilvusCollectionFields.DOCUMENT_ID + " == \"" + escapeFilterValue(knowledgeDocumentId) + "\"")
                .build());
        log.info("Knowledge-base Milvus delete request completed: collection={}, knowledgeDocumentId={}",
                knowledgeBaseProperties.getCollection(),
                knowledgeDocumentId);
    }

    @Override
    public void deleteByKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            log.info("Knowledge-base Milvus delete skipped: collection={}, reason=blank-knowledge-base-id",
                    knowledgeBaseProperties.getCollection());
            return;
        }

        ensureCollection();
        log.info("Knowledge-base Milvus delete request started: collection={}, knowledgeBaseId={}",
                knowledgeBaseProperties.getCollection(),
                knowledgeBaseId);
        milvusClient.delete(DeleteReq.builder()
                .collectionName(knowledgeBaseProperties.getCollection())
                .filter(KnowledgeBaseMilvusCollectionFields.KNOWLEDGE_BASE_ID + " == \"" + escapeFilterValue(knowledgeBaseId) + "\"")
                .build());
        log.info("Knowledge-base Milvus delete request completed: collection={}, knowledgeBaseId={}",
                knowledgeBaseProperties.getCollection(),
                knowledgeBaseId);
    }

    @Override
    public List<MilvusSearchHit> searchByKnowledgeBaseIds(List<String> knowledgeBaseIds, float[] queryEmbedding, int topK) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return List.of();
        }

        ensureCollection();
        SearchResp response = milvusClient.search(SearchReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(knowledgeBaseProperties.getCollection())
                .annsField(KnowledgeBaseMilvusCollectionFields.EMBEDDING)
                .data(List.of(new FloatVec(queryEmbedding)))
                .filter(knowledgeBaseIdsFilter(knowledgeBaseIds))
                .topK(topK)
                .outputFields(outputFields())
                .consistencyLevel(parseConsistencyLevel())
                .build());
        return toHits(response);
    }

    @Override
    public List<MilvusSearchHit> searchByKnowledgeBaseIdsBm25(List<String> knowledgeBaseIds, String queryText, int topK) {
        if (!properties.isBm25Enabled() || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        MilvusBm25Encoding.requireUtf8DefaultCharset();
        String sanitizedQueryText = sanitizeBm25Text(queryText);
        if (sanitizedQueryText.isBlank()) {
            return List.of();
        }

        ensureCollection();
        SearchResp response = milvusClient.search(SearchReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(knowledgeBaseProperties.getCollection())
                .annsField(KnowledgeBaseMilvusCollectionFields.BM25_SPARSE)
                .data(List.of(new EmbeddedText(sanitizedQueryText)))
                .filter(knowledgeBaseIdsFilter(knowledgeBaseIds))
                .topK(topK)
                .outputFields(outputFields())
                .consistencyLevel(parseConsistencyLevel())
                .build());
        return toHits(response);
    }

    static String sanitizeBm25Text(String text) {
        return sanitizeMilvusText(text, TEXT_MAX_LENGTH, true);
    }

    static String sanitizeMilvusText(String text, int maxUtf8Bytes, boolean collapseWhitespace) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxUtf8Bytes <= 0) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(text.length(), maxUtf8Bytes));
        int utf8Bytes = 0;
        boolean previousWhitespace = collapseWhitespace;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean invalid = codePoint == 0 || codePoint == 0xFFFD || (codePoint >= Character.MIN_SURROGATE
                    && codePoint <= Character.MAX_SURROGATE);
            if (invalid) {
                codePoint = ' ';
            }
            boolean whitespace = Character.isWhitespace(codePoint);
            if (collapseWhitespace && whitespace) {
                if (!previousWhitespace) {
                    if (utf8Bytes + 1 > maxUtf8Bytes) {
                        break;
                    }
                    sanitized.append(' ');
                    utf8Bytes++;
                }
            } else {
                int codePointUtf8Bytes = utf8Length(codePoint);
                if (utf8Bytes + codePointUtf8Bytes > maxUtf8Bytes) {
                    break;
                }
                sanitized.appendCodePoint(codePoint);
                utf8Bytes += codePointUtf8Bytes;
            }
            previousWhitespace = whitespace;
        }
        return collapseWhitespace ? sanitized.toString().trim() : sanitized.toString();
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private JsonObject toRow(KnowledgeBaseMilvusChunkDocument chunk) {
        JsonObject row = new JsonObject();
        row.addProperty(KnowledgeBaseMilvusCollectionFields.CHUNK_ID, chunk.chunkId());
        row.addProperty(KnowledgeBaseMilvusCollectionFields.KNOWLEDGE_BASE_ID, chunk.knowledgeBaseId());
        row.addProperty(KnowledgeBaseMilvusCollectionFields.DOCUMENT_ID, chunk.documentId());
        row.addProperty(KnowledgeBaseMilvusCollectionFields.CHUNK_INDEX, chunk.chunkIndex());
        row.addProperty(KnowledgeBaseMilvusCollectionFields.DOCUMENT_NAME,
                sanitizeMilvusText(chunk.documentName(), DOCUMENT_NAME_MAX_LENGTH, false));
        row.addProperty(KnowledgeBaseMilvusCollectionFields.SECTION_PATH,
                sanitizeMilvusText(chunk.sectionPath(), TEXT_MAX_LENGTH, false));
        row.addProperty(KnowledgeBaseMilvusCollectionFields.CONTENT,
                sanitizeMilvusText(chunk.content(), TEXT_MAX_LENGTH, false));
        row.addProperty(KnowledgeBaseMilvusCollectionFields.CONTEXT_TEXT,
                sanitizeMilvusText(chunk.contextText(), TEXT_MAX_LENGTH, false));
        row.addProperty(KnowledgeBaseMilvusCollectionFields.RETRIEVAL_TEXT,
                sanitizeMilvusText(chunk.retrievalText(), TEXT_MAX_LENGTH, false));
        if (properties.isBm25Enabled()) {
            row.addProperty(KnowledgeBaseMilvusCollectionFields.BM25_TEXT, sanitizeBm25Text(chunk.bm25Text()));
        }
        row.addProperty(KnowledgeBaseMilvusCollectionFields.ENABLED, chunk.enabled());
        row.addProperty(KnowledgeBaseMilvusCollectionFields.CREATED_AT, chunk.createdAtEpochMillis());
        row.add(KnowledgeBaseMilvusCollectionFields.EMBEDDING, gson.toJsonTree(toFloatList(chunk.embedding())));
        return row;
    }

    private List<String> outputFields() {
        return List.of(
                KnowledgeBaseMilvusCollectionFields.CHUNK_ID,
                KnowledgeBaseMilvusCollectionFields.KNOWLEDGE_BASE_ID,
                KnowledgeBaseMilvusCollectionFields.DOCUMENT_ID,
                KnowledgeBaseMilvusCollectionFields.CHUNK_INDEX,
                KnowledgeBaseMilvusCollectionFields.DOCUMENT_NAME,
                KnowledgeBaseMilvusCollectionFields.SECTION_PATH,
                KnowledgeBaseMilvusCollectionFields.CONTENT,
                KnowledgeBaseMilvusCollectionFields.CONTEXT_TEXT,
                KnowledgeBaseMilvusCollectionFields.RETRIEVAL_TEXT
        );
    }

    private String knowledgeBaseIdsFilter(List<String> knowledgeBaseIds) {
        return KnowledgeBaseMilvusCollectionFields.KNOWLEDGE_BASE_ID + " in [" + joinQuotedValues(knowledgeBaseIds) + "] and "
                + KnowledgeBaseMilvusCollectionFields.ENABLED + " == true";
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
            hits.add(new MilvusSearchHit(
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.CHUNK_ID),
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.KNOWLEDGE_BASE_ID),
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.DOCUMENT_ID),
                    intField(entity, KnowledgeBaseMilvusCollectionFields.CHUNK_INDEX),
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.DOCUMENT_NAME),
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.SECTION_PATH),
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.CONTENT),
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.CONTEXT_TEXT),
                    stringifyField(entity, KnowledgeBaseMilvusCollectionFields.RETRIEVAL_TEXT),
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
