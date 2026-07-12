package com.yulong.chatagent.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles L3 long-term memory promotion after L2 summarization.
 *
 * <p>For each claimed durable promotion job, this service:
 * <ol>
 *     <li>Uses the trusted user/session/range stored with the job.</li>
 *     <li>Calls the LLM extractor with the reloaded visible raw turns.</li>
 *     <li>For each valid extracted memory: normalizes content, computes hash, upserts into {@code memory_item}.</li>
 *     <li>Embeds each memory and indexes it into Milvus.</li>
 *     <li>Updates {@code index_status} based on Milvus upsert result.</li>
 * </ol>
 *
 * <p>Failures propagate to {@link MemoryPromotionJobWorker}, which owns bounded
 * durable retry. L2 is already committed and is therefore unaffected.
 */
@Service
@Slf4j
public class LongTermMemoryPromotionService {

    private final MemoryItemRepository memoryItemRepository;
    private final LongTermMemoryExtractor extractor;
    private final OllamaEmbeddingClient embeddingClient;
    private final UserMemoryIndexService indexService;
    private final ObjectMapper objectMapper;

    public LongTermMemoryPromotionService(MemoryItemRepository memoryItemRepository,
                                          LongTermMemoryExtractor extractor,
                                          OllamaEmbeddingClient embeddingClient,
                                          UserMemoryIndexService indexService,
                                          ObjectMapper objectMapper) {
        this.memoryItemRepository = memoryItemRepository;
        this.extractor = extractor;
        this.embeddingClient = embeddingClient;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
    }

    /**
     * Promotes long-term memories from the raw turns of a completed L2 batch.
     * Exceptions propagate so the durable worker can retry the claimed job.
     *
     * @param job durable user/session/range authority
     * @param turns     the raw turns that L2 compressed
     * @return number of candidate rows upserted and indexed
     */
    public int promote(MemoryPromotionJobDTO job, List<AtomicConversationTurn> turns) {
        ExtractionResult extractionResult = extractor.extract(turns);
        if (!extractionResult.success()) {
            throw new IllegalStateException("memory extractor returned failure");
        }
        List<ExtractedMemory> memories = extractionResult.memories();
        Map<String, Object> source = buildSource(
                job.getSessionId(), job.getSeqStartNo(), job.getSeqEndNo(), turns);
        int upserted = upsertAndIndexMemories(job.getUserId(), memories, source);
        log.info("L3 extraction completed: sessionId={}, candidates={}, upserted={}",
                job.getSessionId(), memories.size(), upserted);
        return upserted;
    }

    private int upsertAndIndexMemories(String userId, List<ExtractedMemory> memories,
                                       Map<String, Object> source) {
        int count = 0;
        for (ExtractedMemory memory : memories) {
            String normalized = MemoryHashNormalizer.normalize(memory.content());
            String hash = MemoryHashNormalizer.hash(userId, memory.type(), normalized);
            String tagsJson = toJson(memory.tags());

            MemoryItemDTO item = MemoryItemDTO.builder()
                    .userId(userId)
                    .type(memory.type())
                    .content(memory.content().trim())
                    .tags(memory.tags())
                    .source(source)
                    .contentHash(hash)
                    .status("active")
                    .indexStatus("pending")
                    .build();

            MemoryItemDTO saved = memoryItemRepository.upsert(item);
            count++;

            // Embed and index into Milvus. Update index_status based on result.
            indexSingleMemory(saved, userId, memory.type(), tagsJson);
        }
        return count;
    }

    private void indexSingleMemory(MemoryItemDTO item, String userId, String type, String tagsJson) {
        try {
            float[] embedding = embeddingClient.embed(item.getContent());
            boolean indexed = indexService.upsertMemory(
                    item.getId(), userId, type, item.getStatus(),
                    item.getContent(), tagsJson, embedding);
            if (!indexed) {
                throw new IllegalStateException("memory index unavailable");
            }
            memoryItemRepository.updateIndexStatus(item.getId(), "indexed");
        } catch (Exception e) {
            log.warn("L3 memory Milvus indexing failed: memoryId={}, errorClass={}", item.getId(), e.getClass().getSimpleName());
            try {
                memoryItemRepository.updateIndexStatus(item.getId(), "failed");
            } catch (Exception ex) {
                log.warn("L3 memory index_status update failed: memoryId={}, errorClass={}", item.getId(), ex.getClass().getSimpleName());
            }
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException : new IllegalStateException("memory indexing failed", e);
        }
    }

    private Map<String, Object> buildSource(String sessionId, long seqStart, long seqEnd,
                                            List<AtomicConversationTurn> turns) {
        List<String> turnIds = new ArrayList<>();
        for (AtomicConversationTurn turn : turns) {
            if (turn.turnId() != null) {
                turnIds.add(turn.turnId());
            }
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("session_id", sessionId);
        source.put("seq_start_no", seqStart);
        source.put("seq_end_no", seqEnd);
        source.put("turn_ids", turnIds);
        return source;
    }

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            return "[]";
        }
    }

}
