package com.yulong.chatagent.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;
import com.yulong.chatagent.memory.port.MemoryExtractionLogRepository;
import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles L3 long-term memory promotion after L2 summarization.
 *
 * <p>For each completed L2 batch, this service:
 * <ol>
 *     <li>Resolves the user from the session (skips if no user).</li>
 *     <li>Checks extraction log idempotency — duplicate ranges are skipped.</li>
 *     <li>Inserts an extraction log with status "processing".</li>
 *     <li>Calls the LLM extractor to get memory candidates.</li>
 *     <li>For each valid extracted memory: normalizes content, computes hash, upserts into {@code memory_item}.</li>
 *     <li>Embeds each memory and indexes it into Milvus.</li>
 *     <li>Updates {@code index_status} based on Milvus upsert result.</li>
 *     <li>Updates the extraction log to "completed" or "failed".</li>
 * </ol>
 *
 * <p>L3 failures are fully isolated from L2: any exception is caught and logged
 * without rethrowing, so L2 summary success is never affected.
 */
@Service
@Slf4j
public class LongTermMemoryPromotionService {

    private final ChatSessionRepository chatSessionRepository;
    private final MemoryExtractionLogRepository extractionLogRepository;
    private final MemoryItemRepository memoryItemRepository;
    private final LongTermMemoryExtractor extractor;
    private final OllamaEmbeddingClient embeddingClient;
    private final UserMemoryIndexService indexService;
    private final ObjectMapper objectMapper;

    public LongTermMemoryPromotionService(ChatSessionRepository chatSessionRepository,
                                          MemoryExtractionLogRepository extractionLogRepository,
                                          MemoryItemRepository memoryItemRepository,
                                          LongTermMemoryExtractor extractor,
                                          OllamaEmbeddingClient embeddingClient,
                                          UserMemoryIndexService indexService,
                                          ObjectMapper objectMapper) {
        this.chatSessionRepository = chatSessionRepository;
        this.extractionLogRepository = extractionLogRepository;
        this.memoryItemRepository = memoryItemRepository;
        this.extractor = extractor;
        this.embeddingClient = embeddingClient;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
    }

    /**
     * Promotes long-term memories from the raw turns of a completed L2 batch.
     * Exceptions are caught internally so L2 summarization is never affected.
     *
     * @param sessionId the session that triggered L2 compression
     * @param range     the seq_no range that was summarized
     * @param turns     the raw turns that L2 compressed
     */
    public void promote(String sessionId, SummaryWatermarkRange range, List<AtomicConversationTurn> turns) {
        try {
            doPromote(sessionId, range, turns);
        } catch (Exception e) {
            log.warn("L3 memory promotion failed, L2 summary is unaffected: sessionId={}, range={}:{}, errorClass={}",
                    sessionId, range.startExclusiveSeqNo(), range.endInclusiveSeqNo(), e.getClass().getSimpleName());
        }
    }

    private void doPromote(String sessionId, SummaryWatermarkRange range, List<AtomicConversationTurn> turns) {
        ChatSessionDTO session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getUserId() == null) {
            log.debug("L3 promotion skipped: session has no user: sessionId={}", sessionId);
            return;
        }
        String userId = session.getUserId();

        long seqStart = range.startExclusiveSeqNo() + 1;
        long seqEnd = range.endInclusiveSeqNo();

        // Idempotency: skip if this range was already processed (any status, including failed).
        // v1 treats failed logs as terminal. Future iterations may retry failed ranges.
        MemoryExtractionLogDTO existingLog = extractionLogRepository.findByRange(sessionId, seqStart, seqEnd);
        if (existingLog != null) {
            log.debug("L3 promotion skipped: range already processed: sessionId={}, logStatus={}",
                    sessionId, existingLog.getStatus());
            return;
        }

        // Insert extraction log as "processing".
        MemoryExtractionLogDTO extractionLog = MemoryExtractionLogDTO.builder()
                .userId(userId)
                .sessionId(sessionId)
                .seqStartNo(seqStart)
                .seqEndNo(seqEnd)
                .status("processing")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        MemoryExtractionLogDTO savedLog = extractionLogRepository.insert(extractionLog);

        try {
            ExtractionResult extractionResult = extractor.extract(turns);
            if (!extractionResult.success()) {
                extractionLogRepository.updateStatus(savedLog.getId(), "failed", "extractor returned failure");
                log.info("L3 extraction returned failure: sessionId={}, userId={}", sessionId, userId);
                return;
            }

            List<ExtractedMemory> memories = extractionResult.memories();
            Map<String, Object> source = buildSource(sessionId, seqStart, seqEnd, turns);
            int upserted = upsertAndIndexMemories(userId, memories, source);

            extractionLogRepository.updateStatus(savedLog.getId(), "completed", null);
            log.info("L3 extraction completed: sessionId={}, userId={}, candidates={}, upserted={}",
                    sessionId, userId, memories.size(), upserted);
        } catch (Exception e) {
            extractionLogRepository.updateStatus(savedLog.getId(), "failed",
                    truncate(e.getMessage(), 500));
            log.warn("L3 extraction error: sessionId={}, userId={}, errorClass={}",
                    sessionId, userId, e.getClass().getSimpleName());
        }
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
            String indexStatus = indexed ? "indexed" : "failed";
            memoryItemRepository.updateIndexStatus(item.getId(), indexStatus);
            if (!indexed) {
                log.debug("L3 memory Milvus indexing unavailable: memoryId={}", item.getId());
            }
        } catch (Exception e) {
            log.warn("L3 memory Milvus indexing failed: memoryId={}, errorClass={}", item.getId(), e.getClass().getSimpleName());
            try {
                memoryItemRepository.updateIndexStatus(item.getId(), "failed");
            } catch (Exception ex) {
                log.warn("L3 memory index_status update failed: memoryId={}, errorClass={}", item.getId(), ex.getClass().getSimpleName());
            }
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

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
