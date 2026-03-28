package com.yulong.chatagent.rag.vector.milvus;

/**
 * Field names for the knowledge-base chunk collection stored in Milvus.
 */
final class KnowledgeBaseMilvusCollectionFields {

    static final String CHUNK_ID = "chunk_id";
    static final String KNOWLEDGE_BASE_ID = "kb_id";
    static final String DOCUMENT_ID = "document_id";
    static final String CHUNK_INDEX = "chunk_index";
    static final String DOCUMENT_NAME = "document_name";
    static final String SECTION_PATH = "section_path";
    static final String CONTENT = "content";
    static final String CONTEXT_TEXT = "context_text";
    static final String RETRIEVAL_TEXT = "retrieval_text";
    static final String BM25_TEXT = "bm25_text";
    static final String BM25_SPARSE = "bm25_sparse";
    static final String ENABLED = "enabled";
    static final String CREATED_AT = "created_at";
    static final String EMBEDDING = "embedding";

    private KnowledgeBaseMilvusCollectionFields() {
    }
}
