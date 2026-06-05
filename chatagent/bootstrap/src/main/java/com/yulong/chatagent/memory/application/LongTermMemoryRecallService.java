package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Recalls relevant L3 long-term memories for a user at query time.
 *
 * <p>The recall flow:
 * <ol>
 *     <li>Resolve the user from the session (skip if no user).</li>
 *     <li>Embed the query text via {@link OllamaEmbeddingClient}.</li>
 *     <li>Search the user-memory Milvus index via {@link UserMemoryIndexService}.</li>
 *     <li>Format the results as a multi-line string for prompt injection.</li>
 * </ol>
 *
 * <p>If Milvus is unavailable, the user has no memories, or the session has no user,
 * this service returns an empty string — the caller simply omits the L3 section.
 */
@Service
@Slf4j
public class LongTermMemoryRecallService {

    private final ChatSessionRepository chatSessionRepository;
    private final UserMemoryIndexService indexService;
    private final OllamaEmbeddingClient embeddingClient;
    private final boolean l3Enabled;
    private final int recallTopK;

    public LongTermMemoryRecallService(ChatSessionRepository chatSessionRepository,
                                       UserMemoryIndexService indexService,
                                       OllamaEmbeddingClient embeddingClient,
                                       @Value("${chatagent.memory.l3.enabled:true}") boolean l3Enabled,
                                       @Value("${chatagent.memory.l3.recall-top-k:3}") int recallTopK) {
        this.chatSessionRepository = chatSessionRepository;
        this.indexService = indexService;
        this.embeddingClient = embeddingClient;
        this.l3Enabled = l3Enabled;
        this.recallTopK = recallTopK;
    }

    /**
     * Recalls relevant long-term memories for the user in the given session.
     *
     * @param sessionId the current chat session
     * @param query     the query text (typically rewrittenInput, falling back to latest user message)
     * @return formatted memory text, or empty string if no memories found or recall is unavailable
     */
    public String recall(String sessionId, String query) {
        if (!l3Enabled) {
            return "";
        }
        if (!StringUtils.hasText(query)) {
            return "";
        }

        ChatSessionDTO session = chatSessionRepository.findById(sessionId);
        if (session == null || session.getUserId() == null) {
            log.debug("L3 recall skipped: session has no user: sessionId={}", sessionId);
            return "";
        }
        String userId = session.getUserId();

        try {
            float[] embedding = embeddingClient.embed(query);
            List<UserMemorySearchHit> hits = indexService.search(userId, embedding, recallTopK);
            if (hits.isEmpty()) {
                return "";
            }
            log.debug("L3 recall returned {} memories for user={}", hits.size(), userId);
            return formatMemories(hits);
        } catch (Exception e) {
            log.warn("L3 recall failed, continuing without memories: sessionId={}, errorClass={}",
                    sessionId, e.getClass().getSimpleName());
            return "";
        }
    }

    private String formatMemories(List<UserMemorySearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (UserMemorySearchHit hit : hits) {
            sb.append("- ").append(hit.type()).append(": ").append(hit.content()).append('\n');
        }
        return sb.toString().trim();
    }
}
