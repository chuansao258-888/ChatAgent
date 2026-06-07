package com.yulong.chatagent.eval.v2.retrieval;

import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexService;
import com.yulong.chatagent.rag.vector.milvus.model.KnowledgeBaseMilvusChunkDocument;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Eval-only in-memory index with a strict ownership boundary for smoke runs.
 *
 * <p>Supports two embedding modes:
 * <ul>
 *   <li>{@code hash} (default): deterministic bag-of-tokens hash embedding, 128 dimensions.
 *       Use for CI regression tests with no external dependencies.</li>
 *   <li>{@code ollama}: real Ollama bge-m3 embedding, 1024 dimensions.
 *       Use for genuine retrieval quality measurement; requires a running Ollama instance.</li>
 * </ul>
 */
final class EvalOwnedKnowledgeBaseFixture implements KnowledgeBaseMilvusIndexService, AutoCloseable {

    static final String OWNED_PREFIX = "eval-v2-";

    static final int HASH_EMBEDDING_DIMENSION = 128;
    static final int OLLAMA_EMBEDDING_DIMENSION = 1024;

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final int embeddingDimension;
    private final EmbeddingFunction embeddingFunction;

    EvalOwnedKnowledgeBaseFixture() {
        this(EvalOwnedKnowledgeBaseFixture::embedText, HASH_EMBEDDING_DIMENSION);
    }

    EvalOwnedKnowledgeBaseFixture(EmbeddingFunction embeddingFunction, int embeddingDimension) {
        if (embeddingDimension <= 0) {
            throw new IllegalArgumentException("Evaluation embedding dimension must be positive");
        }
        this.embeddingFunction = embeddingFunction;
        this.embeddingDimension = embeddingDimension;
    }

    int embeddingDimension() {
        return embeddingDimension;
    }

    float[] embed(String text) {
        float[] embedding = embeddingFunction.apply(text);
        validateEmbeddingDimension(embedding);
        return embedding;
    }

    private final Map<String, KnowledgeBaseMilvusChunkDocument> chunksById = new LinkedHashMap<>();
    private final Set<String> ownedKnowledgeBaseIds = new LinkedHashSet<>();

    void createKnowledgeBase(String knowledgeBaseId, List<KnowledgeBaseMilvusChunkDocument> chunks) {
        requireOwnedKnowledgeBase(knowledgeBaseId);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("Evaluation knowledge base must contain chunks");
        }
        for (KnowledgeBaseMilvusChunkDocument chunk : chunks) {
            if (!knowledgeBaseId.equals(chunk.knowledgeBaseId())) {
                throw new IllegalArgumentException("Chunk belongs to a different knowledge base: " + chunk.chunkId());
            }
        }
        ownedKnowledgeBaseIds.add(knowledgeBaseId);
        upsertChunks(chunks);
    }

    static float[] embedText(String text) {
        float[] embedding = new float[HASH_EMBEDDING_DIMENSION];
        for (String token : tokens(text)) {
            embedding[Math.floorMod(token.hashCode(), HASH_EMBEDDING_DIMENSION)] += 1.0f;
        }
        double norm = 0.0d;
        for (float value : embedding) {
            norm += value * value;
        }
        if (norm == 0.0d) {
            return embedding;
        }
        float divisor = (float) Math.sqrt(norm);
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] /= divisor;
        }
        return embedding;
    }

    int chunkCount() {
        return chunksById.size();
    }

    @Override
    public void ensureCollection() {
        // No external collection is needed for the deterministic smoke fixture.
    }

    @Override
    public void upsertChunks(List<KnowledgeBaseMilvusChunkDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (KnowledgeBaseMilvusChunkDocument chunk : chunks) {
            requireOwnedKnowledgeBase(chunk.knowledgeBaseId());
            if (!ownedKnowledgeBaseIds.contains(chunk.knowledgeBaseId())) {
                throw new IllegalStateException("Evaluation knowledge base was not created: " + chunk.knowledgeBaseId());
            }
            validateEmbeddingDimension(chunk.embedding());
            chunksById.put(chunk.chunkId(), chunk);
        }
    }

    @Override
    public void deleteByKnowledgeDocumentId(String knowledgeDocumentId) {
        List<String> matchingChunkIds = chunksById.values().stream()
                .filter(chunk -> knowledgeDocumentId != null && knowledgeDocumentId.equals(chunk.documentId()))
                .map(KnowledgeBaseMilvusChunkDocument::chunkId)
                .toList();
        matchingChunkIds.forEach(chunksById::remove);
    }

    @Override
    public void deleteByKnowledgeBaseId(String knowledgeBaseId) {
        requireOwnedKnowledgeBase(knowledgeBaseId);
        chunksById.entrySet().removeIf(entry -> knowledgeBaseId.equals(entry.getValue().knowledgeBaseId()));
        ownedKnowledgeBaseIds.remove(knowledgeBaseId);
    }

    @Override
    public List<MilvusSearchHit> searchByKnowledgeBaseIds(
            List<String> knowledgeBaseIds,
            float[] queryEmbedding,
            int topK
    ) {
        validateSearch(knowledgeBaseIds, topK);
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        validateEmbeddingDimension(queryEmbedding);
        return rankedHits(knowledgeBaseIds, topK, chunk -> cosine(queryEmbedding, chunk.embedding()), "dense");
    }

    @Override
    public List<MilvusSearchHit> searchByKnowledgeBaseIdsBm25(
            List<String> knowledgeBaseIds,
            String queryText,
            int topK
    ) {
        validateSearch(knowledgeBaseIds, topK);
        Set<String> queryTokens = new LinkedHashSet<>(tokens(queryText));
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        return rankedHits(knowledgeBaseIds, topK, chunk -> lexicalScore(queryTokens, chunk.bm25Text()), "bm25");
    }

    @Override
    public void close() {
        new ArrayList<>(ownedKnowledgeBaseIds).forEach(this::deleteByKnowledgeBaseId);
    }

    private List<MilvusSearchHit> rankedHits(
            List<String> knowledgeBaseIds,
            int topK,
            ScoreFunction scoreFunction,
            String scoreType
    ) {
        Set<String> allowedIds = new LinkedHashSet<>(knowledgeBaseIds);
        return chunksById.values().stream()
                .filter(KnowledgeBaseMilvusChunkDocument::enabled)
                .filter(chunk -> allowedIds.contains(chunk.knowledgeBaseId()))
                .map(chunk -> new ScoredChunk(chunk, scoreFunction.score(chunk)))
                .filter(scored -> scored.score() > 0.0d)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(scored -> scored.chunk().chunkId()))
                .limit(topK)
                .map(scored -> toHit(scored.chunk(), scored.score(), scoreType))
                .toList();
    }

    private MilvusSearchHit toHit(KnowledgeBaseMilvusChunkDocument chunk, double score, String scoreType) {
        return MilvusSearchHit.builder()
                .chunkId(chunk.chunkId())
                .sourceId(chunk.knowledgeBaseId())
                .documentId(chunk.documentId())
                .chunkIndex(chunk.chunkIndex())
                .documentName(chunk.documentName())
                .sectionPath(chunk.sectionPath())
                .content(chunk.content())
                .contextText(chunk.contextText())
                .retrievalText(chunk.retrievalText())
                .score(score)
                .scoreType(scoreType)
                .build();
    }

    private void validateSearch(List<String> knowledgeBaseIds, int topK) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("Evaluation search requires a knowledge base ID");
        }
        knowledgeBaseIds.forEach(this::requireOwnedKnowledgeBase);
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
    }

    private void requireOwnedKnowledgeBase(String knowledgeBaseId) {
        if (knowledgeBaseId == null || !knowledgeBaseId.startsWith(OWNED_PREFIX)) {
            throw new IllegalArgumentException("Refusing non-eval knowledge base operation: " + knowledgeBaseId);
        }
    }

    private void validateEmbeddingDimension(float[] embedding) {
        if (embedding == null || embedding.length != embeddingDimension) {
            throw new IllegalArgumentException(
                    "Evaluation embedding dimension mismatch: expected " + embeddingDimension
                            + " but was " + (embedding == null ? "null" : embedding.length)
            );
        }
    }

    private static double cosine(float[] left, float[] right) {
        if (right == null || left.length != right.length) {
            return 0.0d;
        }
        double score = 0.0d;
        for (int index = 0; index < left.length; index++) {
            score += left[index] * right[index];
        }
        return score;
    }

    private static double lexicalScore(Set<String> queryTokens, String text) {
        List<String> documentTokens = tokens(text);
        if (documentTokens.isEmpty()) {
            return 0.0d;
        }
        long matches = documentTokens.stream().filter(queryTokens::contains).count();
        return matches / Math.sqrt(documentTokens.size());
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return TOKEN_SPLITTER.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private interface ScoreFunction {
        double score(KnowledgeBaseMilvusChunkDocument chunk);
    }

    private record ScoredChunk(KnowledgeBaseMilvusChunkDocument chunk, double score) {
    }

    @FunctionalInterface
    interface EmbeddingFunction {
        float[] apply(String text);
    }
}
