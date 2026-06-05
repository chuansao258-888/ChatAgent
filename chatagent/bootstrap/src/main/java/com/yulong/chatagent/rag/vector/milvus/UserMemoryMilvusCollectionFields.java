package com.yulong.chatagent.rag.vector.milvus;

/**
 * Field name constants for the user-memory Milvus collection.
 */
public final class UserMemoryMilvusCollectionFields {

    public static final String MEMORY_ID = "memory_id";
    public static final String USER_ID = "user_id";
    public static final String TYPE = "type";
    public static final String STATUS = "status";
    public static final String CONTENT = "content";
    public static final String TAGS = "tags";
    public static final String UPDATED_AT = "updated_at";
    public static final String EMBEDDING = "embedding";

    private UserMemoryMilvusCollectionFields() {
    }
}
