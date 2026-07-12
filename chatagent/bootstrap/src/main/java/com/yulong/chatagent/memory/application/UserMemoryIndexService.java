package com.yulong.chatagent.memory.application;

import java.util.List;

/**
 * Interface for indexing and recalling L3 user memories via Milvus.
 *
 * <p>Two implementations exist:
 * <ul>
 *     <li>{@code DefaultUserMemoryMilvusIndexService} — active when both
 *         {@code milvus.enabled=true} and {@code chatagent.memory.l3.enabled=true}.</li>
 *     <li>{@code NoOpUserMemoryIndexService} — fallback when either flag is off.
 *         Returns empty recall hits and signals index failure.</li>
 * </ul>
 */
public interface UserMemoryIndexService {

    /**
     * Creates the user-memory collection if it does not already exist.
     */
    void ensureCollection();

    /**
     * Upserts a single memory document into Milvus.
     *
     * @param memoryId  the Postgres {@code memory_item} primary key (UUID string)
     * @param userId    the user who owns this memory
     * @param type      memory type ({@code "preference"} or {@code "fact"})
     * @param status    memory status ({@code "active"} or {@code "archived"})
     * @param content   the memory content text
     * @param tagsJson  JSON-encoded tag list
     * @param embedding pre-computed embedding vector for the content
     * @return {@code true} if the upsert succeeded, {@code false} if indexing failed or is unavailable
     */
    boolean upsertMemory(String memoryId, String userId, String type, String status,
                         String content, String tagsJson, float[] embedding);

    boolean deleteMemory(String memoryId);

    /**
     * Searches for the most relevant memories for a given user.
     *
     * @param userId         filter by this user
     * @param queryEmbedding embedding of the query text
     * @param topK           maximum number of results
     * @return ordered list of matching memories, closest first
     */
    List<UserMemorySearchHit> search(String userId, float[] queryEmbedding, int topK);
}
