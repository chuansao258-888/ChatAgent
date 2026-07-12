package com.yulong.chatagent.memory.application;

/**
 * A single hit from a user-memory vector search in Milvus.
 *
 * @param memoryId the Postgres {@code memory_item} primary key
 * @param type     memory type ({@code "preference"} or {@code "fact"})
 * @param content  the memory content text
 * @param score    similarity score from Milvus
 */
public record UserMemorySearchHit(
        String memoryId,
        String type,
        String content,
        double score,
        java.time.LocalDateTime updatedAt
) {
    public UserMemorySearchHit(String memoryId, String type, String content, double score) {
        this(memoryId, type, content, score, null);
    }
}
