package com.yulong.chatagent.rag.vector.milvus;

/**
 * Field names for the session-scoped chunk collection stored in Milvus.
 *
 * <p>The dense and sparse retrieval paths intentionally share {@code retrieval_text}/{@code
 * bm25_text} as their common source text.</p>
 */
final class MilvusCollectionFields {

    static final String CHUNK_ID = "chunk_id";
    static final String SESSION_ID = "session_id";
    static final String SESSION_FILE_ID = "session_file_id";
    static final String CHUNK_INDEX = "chunk_index";
    static final String FILE_NAME = "file_name";
    static final String CONTENT = "content";
    static final String CONTEXT_TEXT = "context_text";
    static final String RETRIEVAL_TEXT = "retrieval_text";
    static final String BM25_TEXT = "bm25_text";
    static final String BM25_SPARSE = "bm25_sparse";
    static final String ENABLED = "enabled";
    static final String CREATED_AT = "created_at";
    static final String EMBEDDING = "embedding";

    private MilvusCollectionFields() {
    }
}
