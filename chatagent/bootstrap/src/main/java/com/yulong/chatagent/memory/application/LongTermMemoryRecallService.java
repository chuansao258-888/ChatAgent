package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{Alnum}]+");
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "what", "when", "where", "which", "who", "why", "how",
            "did", "does", "was", "were", "are", "is", "am", "be", "been", "being", "give", "gave", "have",
            "has", "had", "can", "could", "would", "should", "usually", "prefer", "preferred", "please", "tell",
            "remind", "earlier", "today", "next", "my", "me", "i", "you", "your", "our", "we", "it"
    );

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
            return formatMemories(query, hits);
        } catch (Exception e) {
            log.warn("L3 recall failed, continuing without memories: sessionId={}, errorClass={}",
                    sessionId, e.getClass().getSimpleName());
            return "";
        }
    }

    private String formatMemories(String query, List<UserMemorySearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("Use only memories that answer the latest user message. ")
                .append("When multiple memories are listed, choose the one whose content matches the requested entity; ")
                .append("do not reuse an answer to an earlier question when the latest message asks for a different fact. ")
                .append("Memories with stronger entity-word matches to the latest request are listed first.\n");
        for (UserMemorySearchHit hit : rankByQueryTerms(query, hits)) {
            sb.append("- ").append(hit.type()).append(": ").append(hit.content()).append('\n');
        }
        return sb.toString().trim();
    }

    private List<UserMemorySearchHit> rankByQueryTerms(String query, List<UserMemorySearchHit> hits) {
        Set<String> queryTerms = significantTerms(query);
        if (queryTerms.isEmpty() || hits.size() <= 1) {
            return hits;
        }

        List<ScoredMemoryHit> scored = new ArrayList<>(hits.size());
        for (UserMemorySearchHit hit : hits) {
            scored.add(new ScoredMemoryHit(hit, lexicalScore(queryTerms, hit)));
        }
        scored.sort(Comparator
                .comparingInt(ScoredMemoryHit::lexicalScore).reversed()
                .thenComparing((ScoredMemoryHit scoredHit) -> scoredHit.hit().score(), Comparator.reverseOrder()));
        return scored.stream().map(ScoredMemoryHit::hit).toList();
    }

    private int lexicalScore(Set<String> queryTerms, UserMemorySearchHit hit) {
        Set<String> contentTerms = significantTerms((hit.type() == null ? "" : hit.type()) + " " + hit.content());
        int score = 0;
        for (String queryTerm : queryTerms) {
            if (contentTerms.contains(queryTerm)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> significantTerms(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        Set<String> terms = new HashSet<>();
        for (String raw : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (raw.length() >= 3 && !QUERY_STOP_WORDS.contains(raw)) {
                terms.add(raw);
            }
        }
        return terms;
    }

    private record ScoredMemoryHit(UserMemorySearchHit hit, int lexicalScore) {
    }

}
