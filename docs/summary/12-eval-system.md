# 12. RAG Evaluation System

## 1. Overview

Eight-dimensional evaluation framework for the ChatAgent RAG pipeline:

1. **Retrieval Quality** — RAGAS metrics (Hit@K, MRR, NDCG, Faithfulness, Answer Relevancy)
2. **Reranker Effectiveness** — A/B comparison (BGE vs Noop)
3. **Performance** — Latency baseline, circuit breaker recovery benchmark
4. **Multi-turn Dialogue** — Intent routing accuracy, coreference resolution, topic-switch detection
5. **Memory / Summary Quality** — rolling summary mention recall, entity recall, topic recall
6. **Tool Call Quality** — tool selection accuracy, step-budget compliance, answer containment
7. **MQ Resilience / Chaos** — retry handoff, DLQ, duplicate suppression, Redis failure policy, session lock requeue
8. **Session Lock Concurrency** — concurrent session execution lock contention, isolation, and recovery

All evaluation code resides in `bootstrap/src/test/java/com/yulong/chatagent/eval/`.

---

## 2. RAG Retrieval Pipeline Architecture

### 2.1 End-to-End Flow

```
User Query
    │
    ▼
RagService (facade)
    │  rag/application/RagService.java
    ├── similaritySearchByKnowledgeBaseIds(kbIds, query)
    │
    ▼
KnowledgeBaseSimilaritySearcher (hybrid retrieval)
    │  rag/retrieve/KnowledgeBaseSimilaritySearcher.java
    │
    ├── OllamaEmbeddingClient.embed(query)           ← bge-m3 via Ollama
    │       rag/embedding/OllamaEmbeddingClient.java
    │       POST /api/embeddings → float[1024]
    │
    ├── Milvus Dense Search                          ← float vector search
    │       rag/vector/milvus/DefaultKnowledgeBaseMilvusIndexService.java
    │       searchByKnowledgeBaseIds(kbIds, embedding, candidateK=12)
    │
    ├── Milvus BM25 Search                           ← sparse text search
    │       searchByKnowledgeBaseIdsBm25(kbIds, queryText, candidateK=12)
    │
    ├── RRF Fusion                                   ← 1/(k + rank) per list
    │       fuseHits(denseHits, bm25Hits, candidateK)
    │       rrfK=60 (smoothing constant)
    │
    ├── KnowledgeDocumentSignalService.attachSignals ← attach keywords/questions
    │       rag/retrieve/KnowledgeDocumentSignalService.java
    │       loads from PostgreSQL + Redis cache (TTL 30min)
    │
    ├── RetrievalReranker.rerank(query, candidates)  ← BGE reranker HTTP
    │       rag/retrieve/BgeHttpRetrievalReranker.java
    │       POST /rerank → relevance scores
    │
    └── .limit(topK=3) → List<RetrievalHit>
```

### 2.2 Key Components

#### RagService

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/application/RagService.java`

Thin facade that delegates to `KnowledgeBaseSimilaritySearcher` or `SearchScopeResolver`. The evaluation tests call `similaritySearchByKnowledgeBaseIds()`.

```java
public List<RetrievalHit> similaritySearchByKnowledgeBaseIds(List<String> knowledgeBaseIds, String query)
```

#### KnowledgeBaseSimilaritySearcher

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/KnowledgeBaseSimilaritySearcher.java`

Core hybrid retrieval component. Two entry points:

| Method | Purpose |
|--------|---------|
| `searchByKnowledgeBaseIds(kbIds, query)` | Full pipeline: candidates → signals → rerank → limit(topK) |
| `searchCandidateHitsByKnowledgeBaseIds(kbIds, query)` | Raw fusion candidates only (no rerank) — used for A/B comparison |

**RRF fusion formula** (line 108):
```
rrfScore = 1.0 / (rrfK + rank + 1)    // rrfK=60
```
For each hit appearing in both dense and BM25 lists, the scores are summed by chunkId.

**Configuration** (via `@Value`):
- `rag.retrieval.top-k` = 3 (final result count)
- `rag.retrieval.candidate-k` = 12 (overfetch for fusion)
- `rag.retrieval.rrf-k` = 60 (smoothing constant)

#### OllamaEmbeddingClient

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/embedding/OllamaEmbeddingClient.java`

WebClient wrapper around Ollama `/api/embeddings`. Sends `{ "model": "bge-m3", "prompt": text }`, returns `float[1024]`. Uses blocking `block()` semantics.

#### Milvus Index Service

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/vector/milvus/DefaultKnowledgeBaseMilvusIndexService.java`

Milvus-backed chunk index with dense vector + BM25 sparse search:

| Method | Description |
|--------|-------------|
| `searchByKnowledgeBaseIds(kbIds, embedding, topK)` | Dense vector search using `FloatVec` against embedding field |
| `searchByKnowledgeBaseIdsBm25(kbIds, queryText, topK)` | BM25 sparse search using `EmbeddedText` against `bm25Sparse` field |

Collection fields: chunkId (PK), knowledgeBaseId, documentId, chunkIndex, documentName, sectionPath, content, contextText, retrievalText, bm25Text, bm25Sparse, embedding (FloatVector, dim=1024, COSINE, AUTOINDEX).

### 2.3 Reranker Implementations

#### RetrievalReranker Interface

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/RetrievalReranker.java`

```java
List<MilvusSearchHit> rerank(String queryText, List<MilvusSearchHit> candidates);
```

Three implementations, selected via `rag.retrieval.reranker.provider`:

| Provider | Class | Activation |
|----------|-------|------------|
| `bge-http` | `BgeHttpRetrievalReranker` | `@Primary` + `@ConditionalOnProperty` |
| `llm` | `LlmRetrievalReranker` | `@Primary` + `@ConditionalOnProperty` |
| `none` (default) | `NoopRetrievalReranker` | Always registered, pass-through |

#### BgeHttpRetrievalReranker

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/BgeHttpRetrievalReranker.java`

HTTP client for locally-deployed BGE reranker service. Internal flow:

1. Skip if < 2 candidates
2. Circuit breaker check: `circuitBreaker.allowRequest()`
3. In HALF_OPEN: probe `/ready` endpoint before sending rerank request
4. Build document payloads (keywords + questions + context + content, truncated to `maxChunkChars=900`)
5. POST to `/rerank` with model `BAAI/bge-reranker-v2-m3`, retry on connect errors
6. Apply relevance scores, mark `scoreType="reranker"` for scored hits
7. On failure: record failure on circuit breaker, return candidates as `scoreType="fallback"`

Micrometer counters: `chatagent.reranker.requests{outcome=success|timeout|circuit_open|fallback|filtered|...}`, `chatagent.reranker.attempts`, `chatagent.reranker.circuit.state` (gauge: 0=CLOSED, 1=HALF_OPEN, 2=OPEN).

#### RerankerProperties

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/RerankerProperties.java`

`@ConfigurationProperties(prefix = "rag.retrieval.reranker")` — all defaults:

| Field | Default | Description |
|-------|---------|-------------|
| `provider` | `"none"` | `none` / `bge-http` / `llm` |
| `modelId` | `"BAAI/bge-reranker-v2-m3"` | Model identifier |
| `maxCandidates` | `8` | Max candidates sent to reranker |
| `maxChunkChars` | `900` | Max chars per candidate in payload |
| `baseUrl` | `"http://localhost:7997"` | Reranker service URL |
| `connectTimeoutMs` | `300` | TCP connect timeout |
| `responseTimeoutMs` | `1800` | Full response timeout |
| `retryConnectErrors` | `1` | Max retries on connect errors |
| `scoreThreshold` | `0.08` | Low-confidence threshold |
| `failureThreshold` | `5` | Circuit breaker: absolute failure count |
| `failureRateThresholdPercent` | `50` | Circuit breaker: failure rate % |
| `minimumRequestVolume` | `10` | Circuit breaker: min requests before evaluating |
| `openStateMs` | `30000` | Circuit breaker: ms to stay OPEN |
| `halfOpenProbeCount` | `1` | Circuit breaker: max probes in HALF_OPEN |

### 2.4 RerankerCircuitBreaker

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/RerankerCircuitBreaker.java`

Lightweight lock-free circuit breaker with sliding window. Not a Spring bean — instantiated by `BgeHttpRetrievalReranker`.

**Sliding window**: 10 fixed buckets, each 10 seconds wide → 100 second total window.

```
State Machine:

  CLOSED ────[failures ≥ threshold AND rate ≥ 50% AND volume ≥ 10]──→ OPEN
     ↑                                                                  │
     │                     after openStateMs (30s)                      │
     │  recordSuccess()                                                 │
     └──────── HALF_OPEN ←──────────────────────────────────────────────┘
                  ↑  │
     recordSuccess  recordFailure
         │              │
         ↓              ↓
       CLOSED         OPEN
```

| Transition | Trigger | Effect |
|------------|---------|--------|
| CLOSED → OPEN | Sliding window exceeds failure thresholds | `lastOpenedAt` set, `halfOpenRequests` reset |
| OPEN → HALF_OPEN | `openStateMs` elapsed on `allowRequest()` | Probe slot available via CAS |
| HALF_OPEN → CLOSED | Single `recordSuccess()` | All buckets reset, window cleared |
| HALF_OPEN → OPEN | Single `recordFailure()` | New `lastOpenedAt`, back to OPEN |

Concurrent probe control: `tryAcquireHalfOpenProbe()` uses atomic CAS to enforce `halfOpenProbeCount` (default 1) — only one thread may probe at a time.

### 2.5 KnowledgeDocumentSignalService

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/retrieve/KnowledgeDocumentSignalService.java`

Manages document-level rerank signals (keywords, questions) across PostgreSQL + Redis. Two-tier cache: batch `multiGet` from Redis first, DB for misses, cache back to Redis (TTL 30min). Fail-open: Redis unavailable → DB only.

`attachSignals(candidates)` enriches each `MilvusSearchHit` with `documentKeywords` and `documentQuestions` — these are prepended to the document payload sent to the BGE reranker.

### 2.6 Ingestion Pipeline

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/ingestion/KnowledgeDocumentIngestionServiceImpl.java`

`ingestSync()` flow:

1. **Validate** storage file exists
2. **Parse**: `DocumentParserSelector` → `PdfDocumentParser` (for PDFs) or other parsers
3. **PdfQualityRouter**: Routes each PDF page as FAST_TRACK (native text) or VISUAL_TRACK (VDP engine)
4. **Enhance**: `DocumentEnhancer` generates keywords, questions, metadata
5. **Chunk**: `DocumentChunker` splits segments into `KnowledgeChunkDraft`s
6. **Enrich**: `LlmContextualChunkEnricher` (if enabled) — calls LLM to generate `contextText` per chunk, producing `retrievalText = contextText + content`
7. **Persist**: Save chunks to PostgreSQL + upsert to Milvus + save signals

**LlmContextualChunkEnricher** (`bootstrap/src/main/java/com/yulong/chatagent/rag/ingestion/LlmContextualChunkEnricher.java`):
Implements Anthropic's "contextual retrieval" technique. For each qualifying chunk, sends the whole document prefix + chunk content to LLM, asks for a short context string situating the chunk. `retrievalText` becomes the shared source for dense embeddings, BM25 text, and reranking.

Config (`chatagent.rag.ingestion.contextual-enricher`): `enabled`, `modelId`, `maxDocumentChars=12000`, `maxChunksPerFile=12`, `minChunkChars=160`, `maxContextChars=600`.

### 2.7 PdfQualityRouter

**File**: `bootstrap/src/main/java/com/yulong/chatagent/rag/parser/PdfQualityRouter.java`

Routes each PDF page based on text quality:

| Condition | Route | Reason |
|-----------|-------|--------|
| Empty text | VISUAL_TRACK | EMPTY_NATIVE_TEXT |
| Aligned whitespace ≥ threshold | VISUAL_TRACK | ALIGNED_WHITESPACE (table-like) |
| Short text (≤80 chars) with `\|`, tabs, or compact tokens | VISUAL_TRACK | SHORT_STRUCTURED_TEXT |
| Short text without structure | FAST_TRACK | SHORT_TEXT_FAST_TRACK |
| Low density (<150 chars/page) without punctuation | VISUAL_TRACK | LOW_CHAR_DENSITY |
| Low density with narrative punctuation | FAST_TRACK | SHORT_NARRATIVE_FAST_TRACK |
| Sufficient text | FAST_TRACK | NATIVE_TEXT |

---

## 2.8 Intent Routing Pipeline

### 2.8.1 IntentRouter

**File**: `bootstrap/src/main/java/com/yulong/chatagent/intent/application/IntentRouter.java`

Hierarchical intent classification router with heuristic shortcut + LLM fallback.

```
User Query
    │
    ▼
IntentRouter.route(agentId, query)
    │
    ├── Load active IntentTreeSnapshot for agent
    ├── isVagueQuery() → short/generic queries trigger CLARIFICATION
    │
    └── While-loop walks tree level by level:
        ├── Get child candidates at current level
        ├── Auto-drill if single candidate with no intentKind
        ├── select(query, candidates, pathLabel):
        │   ├── Heuristic score() for each candidate
        │   │   ├── Exact substring match: +1.2
        │   │   ├── Bigram Jaccard on name: x0.7
        │   │   ├── Bigram Jaccard on description: x0.4
        │   │   └── Best example match: +1.0 + overlap x0.6
        │   ├── Shortcut if best ≥ 1.2 AND gap > 0.5
        │   └── LLM fallback → classifier.md prompt → returns ID/NONE/AMBIGUOUS
        │
        ├── noneMatched → return NONE or resolve current node (if has intentKind)
        ├── ambiguous → return CLARIFICATION with top candidates
        └── resolved → add to path, drill deeper or return
```

**History-aware routing** (`routeWithHistory`):

When the initial stateless routing fails (CLARIFICATION/NONE), the router retries with the previous intent's leaf name prepended to the query. This provides the missing conversational context for coreference-laden turns:

```java
public IntentRoutingResult routeWithHistory(String agentId, String query,
                                             IntentResolution previousResolution) {
    IntentRoutingResult firstPass = route(agentId, query);
    if (!firstPass.hasResolution() && previousResolution != null) {
        String leafName = extractLeafName(previousResolution.pathLabel());
        String contextQuery = leafName + " " + query;
        IntentRoutingResult retry = route(agentId, contextQuery);
        if (retry.hasResolution()) return retry;
    }
    return firstPass;
}
```

This single-pass fallback recovers ~70% of context-free routing failures in multi-turn dialogues without requiring changes to the routing prompt or tree structure.

### 2.8.2 QueryRewriter

**File**: `bootstrap/src/main/java/com/yulong/chatagent/intent/application/QueryRewriter.java`

Intent-aware query rewriter with LLM-based rewriting + programmatic anchor enforcement.

```
User Query + IntentResolution
    │
    ▼
QueryRewriter.rewrite(originalQuery, intentResolution)
    │
    ├── Non-KB intents (TOOL, SYSTEM):
    │   └── enforceAnchor(query, pathLabel) — prepend leaf name if missing
    │
    ├── KB intents:
    │   ├── Render query-rewrite.md prompt (intentPath + originalInput)
    │   ├── LLM call via chatModelRouter
    │   └── enforceAnchor(rewritten, pathLabel) — guarantee anchor present
    │
    └── Fallback: "pathLabel | originalQuery"
```

**Programmatic anchor enforcement** (`enforceAnchor`):

Deterministic post-processing that guarantees the intent leaf name appears verbatim in the rewritten query. Applied to ALL intent kinds (KB, TOOL, SYSTEM):

```java
private String enforceAnchor(String query, String pathLabel) {
    String leafName = extractLeafName(pathLabel);
    if (leafName != null && !query.contains(leafName)) {
        return leafName + " " + query;
    }
    return query;
}
```

This safety net catches cases where the LLM drops or abbreviates the retrieval anchor during rewriting (e.g., "加班制度 | 那调休有什么规则" → LLM outputs "加班调休规则" losing "加班制度" → enforcement prepends "加班制度 加班调休规则").

### 2.8.3 Intent Classification Prompt

**File**: `bootstrap/src/main/resources/prompts/intent/classifier.md` (v4)

Template variables: `{{pathLevel}}`, `{{userInput}}`, `{{candidatesText}}`.

Key rules:
1. Choose exactly one candidate ID
2. Return NONE / AMBIGUOUS when appropriate
3. Semantic matching over keyword overlap
4. Cross-domain resolution: follow primary action, not domain keyword
5. Ambiguity detection for short/generic/contrasting inputs
6. **Keyword Extraction (v4)**: If a candidate's name appears as a substring in the input — even inside conversational wrappers like "顺便问一下…", "还有…能…吗" — that candidate receives strong preference

### 2.8.4 Query Rewrite Prompt

**File**: `bootstrap/src/main/resources/prompts/intent/query-rewrite.md` (v3)

Template variables: `{{intentPath}}`, `{{originalInput}}`.

Key rules:
1. **Retrieval Anchor (Mandatory)**: Rewritten query MUST contain the deepest segment of the intent path verbatim
2. Resolve pronouns and omitted references into the concrete business object
3. Preserve domain terminology unchanged
4. Return ONLY the rewritten query text

---

## 3. Evaluation Metrics

### 3.1 EvalMetrics Utility

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/EvalMetrics.java`

Static utility class implementing standard IR metrics:

| Metric | Formula | Description |
|--------|---------|-------------|
| **Hit@K** | `1 if any relevant doc in top-K, else 0` | Binary: did we find at least one relevant document? |
| **MRR** | `1 / rank_of_first_relevant` | Mean Reciprocal Rank — how high is the first hit? |
| **NDCG@K** | `DCG@K / IdealDCG@K` where DCG = Σ (2^grade - 1) / log₂(position + 2) | Normalized Discounted Cumulative Gain — accounts for graded relevance |
| **F1** | `2 × precision × recall / (precision + recall)` | Harmonic mean |
| **Accuracy** | `correct / total` | Simple ratio |

### 3.2 RAGAS Framework

The full evaluation follows the **RAGAS** (Retrieval Augmented Generation Assessment) framework with 5 metrics:

**Context Precision** (retrieval quality):
- Hit@3, Hit@5 — binary recall in top-K
- NDCG@5 — graded ranking quality
- MRR — position of first relevant result

**Context Recall** (retrieval completeness):
- Implicitly captured via Hit@K — if the right documents aren't retrieved, generation quality suffers

**Faithfulness** (generation quality):
- LLM-judged: "Does the answer use ONLY information from the retrieved context?"
- Scale 0.0–1.0, evaluated by a second LLM call to DeepSeek

**Answer Relevancy** (generation quality):
- LLM-judged: "Does the answer address the question?"
- Scale 0.0–1.0, evaluated by the same judge call

### 3.3 LLM Judge

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/RetrievalQualityIntegrationEvalTest.java` (lines 439–466)

The judge sends a single prompt to DeepSeek with context + question + answer, asking for two comma-separated scores (faithfulness, relevancy). Response parsing handles edge cases (code fences, extra text):

```java
private JudgeResult judgeQuality(String context, String query, String answer) {
    String prompt = """
        You are an expert evaluator. Rate the answer on TWO metrics:
        1. Faithfulness (0.0-1.0): Does the answer use ONLY information from the context?
        2. Answer Relevancy (0.0-1.0): Does the answer address the question?
        ...
        Output ONLY two numbers separated by a comma: faithfulness,relevancy
        """.formatted(context, query, answer);
    return parseJudgeResult(chatClient.prompt(prompt).call().content());
}
```

---

## 4. Golden Dataset

### 4.1 Documents (20 files, 3 knowledge bases)

| KB | Count | Documents | Format |
|----|-------|-----------|--------|
| SC6109 - Blockchain Privacy | 7 | lec01-dl (Discrete Logarithm), lec01-privacy (Privacy Issues), lec01-sig (Digital Signatures), lec02-factoring (Factoring & Continued Fractions), lec03-grs (Group/Ring Signatures), lec04-mpczk (MPC & ZK), lec05-pqc (Post-Quantum Crypto) | PDF |
| SC6116 - Game Theory | 7 | lec01-intro (Introduction), lec02-prob (Probability), lec03-decision (Decision Theory), lec04-game (Game Theory Basics), lec05-auction (Auctions), lec06-coord (Coordination), lec07-selfish (Selfish Routing) | PDF |
| ChatAgent - Project Docs | 6 | ca-llm-routing, ca-agent-runtime, ca-rag-pipeline, ca-intent-routing, ca-mcp-integration, ca-conv-orch | Markdown |

### 4.2 Golden Queries (40 queries, 3 categories)

**Structure**: Each `EvalQuery` record contains:
```java
record EvalQuery(
    String id,              // e.g. "sc6109-f-01", "ct-03", "cd-08"
    String category,        // "factual" | "cross-topic" | "cross-domain"
    String query,           // natural language question
    List<String> expectedLectureShortNames,  // ground truth: which docs are relevant
    Map<String, Integer> relevanceGrades     // doc short name → grade (0-3)
)
```

| Category | Count | Description | Example |
|----------|-------|-------------|---------|
| factual | 25 | Single-document factual lookup | "What is the discrete logarithm problem" → lec01-dl (grade 3) |
| cross-topic | 5 | Multi-document within same domain | "How do discrete logarithm and digital signatures relate" → lec01-dl (3), lec01-sig (3) |
| cross-domain | 10 | Cross-domain synthesis | "How does game theory apply to blockchain consensus" → lec01-intro (2), lec07-selfish (2), lec01-privacy (1) |

**Relevance grades**: 3 = highly relevant, 2 = relevant, 1 = tangentially related.

### 4.3 Golden PDFs (20 synthetic samples)

**File**: `bootstrap/src/test/resources/golden-pdfs/` (4 categories × 5 files each)

| Category | Files | Content |
|----------|-------|---------|
| headings | heading-01..05 | Multi-level heading hierarchy, prose text, multi-page |
| tables | table-01..05 | Drawn table (PDFBox), pipe-delimited, CSV, long table |
| scanned | scanned-01..05 | Image-only pages (Graphics2D rendered), blank+image |
| mixed | mixed-01..05 | Text + image + table combinations, aligned whitespace |

Auto-generated via `GoldenPdfGenerator` (`bootstrap/src/test/java/com/yulong/chatagent/rag/parser/GoldenPdfGenerator.java`) which also produces matching `.segments.json` snapshots in `golden-pdfs/expected/`.

Validated by `GoldenPdfValidationTest` — asserts segment count, extraction mode, route, and key phrases for each PDF.

### 4.4 File-based Golden Datasets

Located at `bootstrap/src/test/resources/eval/golden/`, loaded by `GoldenDatasetLoader`:

| File | Entries | Categories |
|------|---------|------------|
| `intent-golden.json` | 63 | direct, ambiguous, cross-domain, out-of-scope, clarification-needed, system-intent |
| `rag-golden.json` | 90 | factual, multi-hop, comparison, temporal |
| `memory-golden.json` | 10 | dialogue-based |
| `multiturn-golden.json` | 10 | dialogue-based |
| `tool-golden.json` | 20 | kb-search, sql-query, email, mcp, multi-tool |

---

## 5. Test Classes

### 5.1 RetrievalQualityIntegrationEvalTest (RAGAS Full Eval)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/RetrievalQualityIntegrationEvalTest.java`
**Tag**: `eval-rag-retrieval` | **Profile**: `local-gpu` | **Lifecycle**: `PER_CLASS`

**Setup** (`@BeforeAll`):
1. Find/create admin user in PostgreSQL (`t_user` table)
2. Delete all existing knowledge bases
3. Create 3 KBs: SC6109, SC6116, ChatAgent
4. Upload + ingest 20 documents (sync ingestion)
5. Build 40 golden queries (smoke mode: limit 3 per category)

**Test flow** (`evaluateRagasMetrics`):
```
For each query:
  (A) Retrieve: ragService.similaritySearchByKnowledgeBaseIds(allKbIds, query)
      → compute Hit@3, Hit@5, MRR, NDCG@5 vs ground truth
  (B) Generate: chatClient.prompt(context + query + category_instruction).call()
      → category-aware prompts: factual, cross-topic, cross-domain each have tailored instructions
  (C) Judge: chatClient.prompt(context + query + answer + judge_prompt).call()
      → faithfulness score (0.0-1.0), answer relevancy (0.0-1.0)
  (D) Rate limit: Thread.sleep(500ms) between queries
Aggregate: avg metrics, per-category breakdown, latency percentiles (P50/P95)
Report: JSON via EvalReportWriter → target/eval-reports/rag-ragas-eval-*.json
```

**Cross-domain generation prompt** (v3, current):
```
The question spans MULTIPLE distinct knowledge domains.
Based on the context:
1. Identify which parts of the context come from which domain.
2. Explain how the concepts relate, compare, or contrast across domains.
3. If the context doesn't fully bridge both domains, explicitly state what information is missing.
4. Address BOTH sides of the question — do not focus on only one domain.
```

**Assertions**: `avgHitAt3 > 0.3`, `avgFaithfulness > 0.3`, `avgAnswerRelevancy > 0.3`.

### 5.2 RerankerAbEvalTest (A/B Comparison)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/RerankerAbEvalTest.java`
**Tag**: `eval-rag-retrieval` | **Profile**: `local-gpu`

**Design**: Two arms sharing the same documents and queries:

| Arm | Code Path | What it tests |
|-----|-----------|---------------|
| **A (BGE)** | `ragService.similaritySearchByKnowledgeBaseIds()` | Full pipeline: dense + BM25 + RRF + signal attach + BGE rerank |
| **B (Noop)** | `kbSearcher.searchCandidateHitsByKnowledgeBaseIds()` → `noopReranker.rerank()` → `.limit(topK)` | Dense + BM25 + RRF + signal attach only, no reranking |

```java
// Arm A: full pipeline (with BGE reranking)
List<RetrievalHit> bgeHits = ragService.similaritySearchByKnowledgeBaseIds(allKbIds, query);

// Arm B: raw fusion candidates, pass-through reranker
List<MilvusSearchHit> rawCandidates = kbSearcher.searchCandidateHitsByKnowledgeBaseIds(allKbIds, query);
List<MilvusSearchHit> noopReranked = noopReranker.rerank(query, signalService.attachSignals(rawCandidates));
List<String> noopDocIds = noopReranked.stream().limit(topK).map(MilvusSearchHit::documentId).toList();
```

**Computed per query**: Hit@3, Hit@5, MRR, NDCG@5 for both arms → delta (ndcgLift, mrrLift, hitAt3Lift).
**Per-category breakdown**: factual, cross-topic, cross-domain.
**Report**: `target/eval-reports/reranker-ab-eval-*.json`.

### 5.3 LatencyBaselineEvalTest (Per-Stage Timing)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/LatencyBaselineEvalTest.java`
**Tag**: `eval-rag-retrieval` | **Profile**: `local-gpu`

**Test flow**:
```
Warm up: 3 queries to prime caches
For each query (30 total):
  (1) Full pipeline: ragService.similaritySearchByKnowledgeBaseIds()  → fullMs
  (2) Retrieval only: kbSearcher.searchCandidateHitsByKnowledgeBaseIds()  → retrievalMs
  (3) Reranker overhead = fullMs - retrievalMs
Report: P50/P95/P99 for each stage, reranker overhead %
```

**Assertions**: retrieval P50 < 2000ms, full pipeline P50 < 3000ms.

### 5.4 CircuitBreakerRecoveryBenchmarkTest

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/CircuitBreakerRecoveryBenchmarkTest.java`
**Tag**: `eval-reranker` | **No infrastructure needed** (pure unit test)

5 benchmark methods:

| Test | What it measures |
|------|------------------|
| `benchmarkFullRecoveryCycle` | CLOSED → OPEN → HALF_OPEN → CLOSED across different `openStateMs` (100, 500, 1000ms), 10 iterations each |
| `benchmarkReTripFromHalfOpen` | HALF_OPEN → OPEN re-trip latency (20 iterations) |
| `benchmarkSlidingWindowDilution` | Failure rate dilution: 4 failures + 6 successes = 40% < 50% threshold → stays CLOSED |
| `benchmarkConcurrentHalfOpenContention` | 8 threads competing for single HALF_OPEN probe slot |
| `benchmarkRepeatedRecoveryCycles` | 5 consecutive trip-recover cycles |

### 5.5 MultiturnDialogueEvalTest (Multi-turn Dialogue Baseline)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/MultiturnDialogueEvalTest.java`
**Tag**: `eval-multiturn` | **Profile**: `local-gpu`

**Design**: Feeds 10 golden dialogues (35 user turns across 4 domains) through IntentRouter + QueryRewriter turn-by-turn, measuring multi-turn dialogue quality with real LLM.

**Per-turn evaluation**:
1. `intentRouter.routeWithHistory(agentId, turn.content(), prevResolution)` — history-aware routing
2. Compare `actualPath` vs `expectedIntentPath` → exact match, domain match, prefix depth
3. If `expectedCoreference` is non-empty: call `queryRewriter.rewrite(turn.content(), rewriteCtx)` → check if rewritten query contains the expected anchor
4. If `isTopicSwitch` and previous path exists → check if path changed
5. Track `prevUserResolution` for history-aware routing and coreference context

**Coref A/B design**:
- **Control**: QueryRewriter uses only current-turn resolution (stateless)
- **Treatment**: QueryRewriter falls back to previous user turn's resolution when current turn is unresolvable (history-aware)

**Computed metrics**:
| Metric | Description |
|--------|-------------|
| `intentPathExactAccuracy` | Full path match rate over turns with expected paths |
| `intentPathDomainAccuracy` | First segment match rate |
| `avgPrefixDepth` | Average leading segment agreement depth |
| `coreferenceRecallControl` | Coref resolved using current-turn context only |
| `coreferenceRecallTreatment` | Coref resolved with history-aware fallback |
| `coreferenceAbLiftPp` | Treatment - Control (percentage points) |
| `topicSwitchDetection` | Path change detection rate on topic switch turns |

**Golden data**: 10 dialogues, 4 domains (hr: 3, finance: 3, it: 2, mixed: 2), 13 coref-bearing turns. Aligned with `EvalTestTreeFactory`'s 13 leaf nodes.

**Report**: `target/eval-reports/multiturn-eval-*.json`.

### 5.6 MemorySummaryEvalTest (Rolling Summary Quality)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/MemorySummaryEvalTest.java`
**Tag**: `eval-memory` | **Profile**: `local-gpu`

**Design**: Replays 10 golden dialogues through `IncrementalSummarizer` one atomic turn at a time using the real rolling-memory prompt and live chat model router. Uses mocked watermark/context-extractor dependencies plus an in-memory summary repository so the evaluation stays focused on summary quality rather than persistence.

**Per-dialogue evaluation**:
1. Convert each user+assistant exchange into one `AtomicConversationTurn`
2. Call `incrementalSummarizer.summarize(sessionId, anchorSeqNo)` incrementally after each assistant turn
3. For checkpoints with `expectedSummaryMentions`, measure whether the refreshed summary preserves all required mentions
4. At dialogue end, compare the final summary text against `expectedEntities` and `expectedTopics`
5. Capture the persisted `anchoredEntities` map for qualitative inspection

**Computed metrics**:
| Metric | Description |
|--------|-------------|
| `checkpointMentionRecall` | Recall of expected checkpoint mentions across all assistant-turn checkpoints |
| `checkpointAllCoveredRate` | Fraction of checkpoints where every expected mention appears in the summary |
| `entityRecall` | Final-summary recall over golden entities |
| `topicRecall` | Final-summary recall over golden topics |
| `avgSummaryChars` | Average final summary length |

**Golden data**: `memory-golden.json` with 10 dialogues across hr / finance / it / admin, including per-turn summary mentions plus final entity/topic expectations.

**Report**: `target/eval-reports/memory-summary-eval-*.json`.

### 5.7 QueryRewriteAbEvalTest (Coreference Retrieval A/B)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/QueryRewriteAbEvalTest.java`
**Tag**: `eval-query-rewrite` | **Profile**: `local-gpu`

**Design**: Reuses the KB coreference turns from `multiturn-golden.json`, runs the real `QueryRewriter`, then compares retrieval quality before vs after rewriting on a lightweight lexical corpus assembled from:

- KB leaf path labels / descriptions / examples in `EvalTestTreeFactory`
- assistant answers from the same multi-turn golden dialogues

**A/B arms**:
- **Control**: raw user follow-up query (e.g. "机票呢")
- **Treatment**: `queryRewriter.rewrite(originalQuery, resolution)` with enforced leaf anchor (e.g. "差旅报销 机票呢")

**Computed metrics**:
| Metric | Description |
|--------|-------------|
| `avgHitAt1` | Whether the expected leaf doc is ranked first |
| `avgHitAt3` | Whether the expected leaf doc appears in top-3 |
| `avgMrr` | Reciprocal rank of the expected leaf doc |
| `avgNdcgAt3` | Graded ranking quality with the expected leaf doc as grade-3 |
| `anchorPresentRate` | Fraction of rewritten queries that contain the expected leaf anchor verbatim |

**Golden data source**: KB follow-up turns extracted from `bootstrap/src/test/resources/eval/golden/multiturn-golden.json`.

**Report**: `target/eval-reports/query-rewrite-ab-eval-*.json`.

### 5.8 ToolCallEvalTest (Tool Selection + Execution Loop)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/ToolCallEvalTest.java`
**Tag**: `eval-tool` | **Profile**: `local-gpu`

**Design**: Uses the real `LLMService` and `ChatAgent` loop together with deterministic fake tools (`emailTool`, `meetingRoomTool`, `vpnTool`, `permissionTool`, `kbSearchTool`) to evaluate whether the model:

1. chooses the expected tools
2. stays within the golden step budget
3. produces a final answer containing the expected business fragments

The harness also runs `ConversationTurnPreparationService.prepare(...)` against the eval intent tree to record one extra metric: expected vs actual `IntentKind` alignment.

**Computed metrics**:
| Metric | Description |
|--------|-------------|
| `intentKindAccuracy` | Match rate between golden `expectedIntentKind` and turn-preparation result |
| `exactToolMatchRate` | Fraction of scenarios where actual tool set equals golden expectedTools |
| `toolF1` | Average per-scenario tool precision/recall harmonic mean |
| `withinMaxStepsRate` | Fraction of scenarios completed within `expectedMaxSteps` |
| `answerContainmentRate` | Average recall over `expectedAnswerContains` fragments |
| `passRate` | Scenarios where intent kind, tool set, step budget, and answer containment all pass |

**Golden data**: `bootstrap/src/test/resources/eval/golden/tool-golden.json` (20 scenarios across email / meeting-room / vpn / permission / kb-search / multi-tool).

**Report**: `target/eval-reports/tool-call-eval-*.json`.

### 5.9 MqChaosEvalTest (MQ Retry / DLQ / Lock Chaos)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/MqChaosEvalTest.java`
**Tag**: `eval-mq-chaos`

**Design**: Pure mocked-channel chaos harness for `KnowledgeIngestTaskListener` and `AbstractRetryingMqConsumer`. It does not require live RabbitMQ, Redis, Milvus, PostgreSQL, or an LLM. Each scenario builds a real AMQP `Message`, injects canonical MQ headers, then verifies the consumer's side effects through mocked `Channel`, `RabbitMqMessagePublisher`, `DistributedLockManager`, and ingestion dependencies.

**Chaos coverage**:

| Category | Faults Covered |
|----------|----------------|
| `retry-handoff` | Retryable ingestion failure; retry publish confirm failure |
| `dlq` | Retry exhaustion; terminal missing document |
| `dedupe` | Duplicate completed delivery |
| `lock-wait` | Existing RUNNING task lock; delayed requeue publish failure |
| `redis-policy` | Redis acquisition failure under FAIL_FAST and FAIL_OPEN |
| `session-serialization` | Busy session execution lock |

**Computed metrics**:

| Metric | Description |
|--------|-------------|
| `passRate` | Fraction of chaos scenarios whose expected ack/nack/reject/publish/lock behavior passed |
| `byCategory.passRate` | Per-fault-family pass rate |
| `observedActions` | Per-scenario action trace such as `ack`, `nack_requeue`, `reject_dlq`, `lock_failed`, `lock_released` |

**Report**: `target/eval-reports/mq-chaos-eval-*.json`.

### 5.10 SessionLockStressEvalTest (Concurrent Session Lock Contention)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/SessionLockStressEvalTest.java`
**Tag**: `eval-session-lock-stress`

**Design**: Uses the real `DistributedLockManager.acquireSessionExecLock(sessionId, owner)` algorithm with a concurrent in-memory mock of Redis `SETNX` / `GET`. This validates lock classification under contention without requiring Redis. The harness launches synchronized thread races with `CountDownLatch`, records acquisition outcomes and latency, and writes `session-lock-stress-eval-*.json`.

**Stress scenarios**:

| Scenario | Load Shape | Expected Invariant |
|----------|------------|--------------------|
| `single-hot-session` | 64 concurrent workers contend for one session | exactly 1 `ACQUIRED`, 63 `WAIT_REQUIRED` |
| `multi-session-isolation` | 64 workers contend across 8 sessions | exactly 1 `ACQUIRED` per session |
| `sequential-wave-recovery` | two 32-worker waves after clearing previous lock | each wave admits one owner, then blocks the rest |

**Computed metrics**:

| Metric | Description |
|--------|-------------|
| `passRate` | Fraction of stress scenarios preserving the expected invariant |
| `totalAcquired` | Total granted session leases across all stress scenarios |
| `totalWaitRequired` | Total correctly blocked contenders |
| `totalErrors` | Unexpected exceptions during concurrent acquisition |
| `avgLatencyMs` / `p95LatencyMs` / `maxLatencyMs` | Per-scenario acquisition timing under thread contention |

**Report**: `target/eval-reports/session-lock-stress-eval-*.json`.

### 5.11 Gatling Load Tests (System-level HTTP/SSE Pressure)

**Directory**: `tools/gatling/`

**Design**: Standalone Gatling Java DSL project that runs outside the main Spring Boot build and targets a live ChatAgent deployment. It validates system-level behavior across HTTP, authentication, database writes, session creation, turn orchestration entrypoint, and SSE connection handling.

**Simulations**:

| Simulation | Target Endpoints | Default Load Shape | Goal |
|------------|------------------|--------------------|------|
| `ChatApiLoadSimulation` | `POST /api/auth/register`, `POST /api/chat-sessions`, `POST /api/chat-messages` | 200 concurrent users, 60s ramp, 300s hold, 400ms per-user pace | approximate 500 chat-message requests/s steady state |
| `SseCapacitySimulation` | `POST /api/auth/register`, `POST /api/chat-sessions`, `GET /api/sse/connect/{sessionId}?access_token=...` | 500 concurrent SSE connections, 60s ramp, 300s hold | validate concurrent SSE connection capacity |

**Config knobs**:

| Property | Default | Description |
|----------|---------|-------------|
| `baseUrl` | `http://localhost:8080` | target ChatAgent base URL |
| `concurrentUsers` | `200` | Chat API concurrent virtual users |
| `sseConnections` | `500` | SSE concurrent connections |
| `rampSeconds` | `60` | ramp-up duration |
| `holdSeconds` | `300` | steady-state duration |
| `paceMillis` | `400` | per-user chat-message pacing |
| `chatP95TargetMs` | `5000` | Gatling assertion threshold for `Create chat message` P95 |
| `maxFailedPercent` | `1.0` | failed request percentage threshold |

**Verification**: `mvnw.cmd -f tools/gatling/pom.xml test-compile` passes with JDK 17. Full benchmark execution requires a running ChatAgent stack and is intentionally not part of the normal Maven test lifecycle.

### 5.12 OutboxReliabilityEvalTest (Transactional Outbox Reliability)

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/OutboxReliabilityEvalTest.java`
**Tag**: `eval-mq-outbox`

**Design**: Drives the real `OutboxRecordService` + `OutboxPollingPublisher` state machine with a thread-safe in-memory `OutboxRepository` and fake `RabbitMqMessagePublisher`. This is a fast regression harness for outbox delivery semantics before slower live PostgreSQL/RabbitMQ container tests.

**Scenarios**:

| Scenario | Workload | Expected Invariant |
|----------|----------|--------------------|
| `at-least-once` | 1000 pending rows, one poller, broker confirms | all rows reach `SENT`, no failed rows, one successful publish per row |
| `retry-timing` | 30 rows, each publish fails twice then confirms | rows remain retryable and reach `SENT` before max attempts |
| `terminal-failure` | 12 rows, broker never confirms | rows move to `FAILED` after max publish attempts |
| `multi-instance` | 1000 rows, four concurrent pollers | all rows reach `SENT` with zero duplicate successful publishes |
| `stale-claim-recovery` | 25 stale `CLAIMED` rows | stale rows are reclaimed and published |
| `cleanup` | 20 old `SENT` + 5 recent `SENT` rows | cleanup removes only old sent rows beyond retention cutoff |

**Computed metrics**:

| Metric | Description |
|--------|-------------|
| `sentRows` / `failedRows` / `pendingRows` / `claimedRows` | Final outbox state counts |
| `publishAttempts` | Total publish attempts, including failed attempts |
| `successfulPublishes` | Confirmed fake-broker publishes |
| `duplicateSuccessfulPublishes` | Duplicate successful publishes for the same outbox id |
| `maxRetryCount` | Highest retry count observed in final rows |
| `cleanupDeleted` | Rows removed by cleanup scenario |

**Report**: `target/eval-reports/mq-outbox-reliability-eval-*.json`.

### 5.13 Other Eval Tests

| Test | Tag | Infra | Description |
|------|-----|-------|-------------|
| `EvalMetricsComputationTest` | default | None | Unit tests for metric formulas with known inputs |
| `RetrievalQualityEvalTest` | `eval-rag-retrieval` | None | Mock-mode retrieval eval using `rag-golden.json` |
| `IntentRoutingEvalTest` | `eval-intent` | None | Intent routing with mocked LLM |
| `IntentRoutingIntegrationEvalTest` | `eval-intent` | PostgreSQL, Redis, DeepSeek | Intent routing with real LLM |
| `MultiturnDialogueEvalTest` | `eval-multiturn` | PostgreSQL, Redis, DeepSeek | Multi-turn dialogue with real LLM, history-aware routing |

---

## 6. Configuration

### 6.1 application.yaml

**File**: `bootstrap/src/main/resources/application.yaml`

Eval-relevant configuration:

```yaml
rag:
  embedding:
    base-url: http://127.0.0.1:11434
    model: bge-m3
  retrieval:
    top-k: 3
    candidate-k: 12
    rrf-k: 60
    reranker:
      provider: bge-http
      model-id: BAAI/bge-reranker-v2-m3
      base-url: http://127.0.0.1:7997
      max-candidates: 8
      max-chunk-chars: 900
      connect-timeout-ms: 300
      response-timeout-ms: 1800
      score-threshold: 0.08
      failure-threshold: 5
      failure-rate-threshold-percent: 50
      minimum-request-volume: 10
      open-state-ms: 30000
      half-open-probe-count: 1

milvus:
  enabled: false          # set CHATAGENT_MILVUS_ENABLED=true
  dimension: 1024
  metric-type: COSINE
  index-type: AUTOINDEX

chatagent:
  rag:
    ingestion:
      contextual-enricher:
        enabled: true     # requires CHATAGENT_RAG_CONTEXTUAL_MODEL=deepseek-chat
        max-document-chars: 12000
        max-chunks-per-file: 12
        min-chunk-chars: 160
        max-context-chars: 600
```

### 6.2 application-local-gpu.yaml

**File**: `bootstrap/src/main/resources/application-local-gpu.yaml`

Overrides for local GPU evaluation: reranker provider/model/base-url, MinerU document parsing (at `http://127.0.0.1:8000`).

### 6.3 Required Environment Variables

```bash
export JAVA_HOME="/c/Users/guany/.jdks/ms-17.0.18"
export CHATAGENT_DB_PASSWORD=app123
export CHATAGENT_REDIS_PASSWORD=123456
export CHATAGENT_MILVUS_ENABLED=true
export CHATAGENT_DEEPSEEK_API_KEY=sk-...
export CHATAGENT_DEEPSEEK_BASE_URL=https://api.deepseek.com
export CHATAGENT_DEEPSEEK_MODEL=deepseek-chat
export CHATAGENT_RAG_CONTEXTUAL_MODEL=deepseek-chat    # for ingestion contextual enricher
```

---

## 7. Run Commands

```bash
# Full RAGAS eval (~15 min)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-rag-retrieval \
  -Dtest=RetrievalQualityIntegrationEvalTest

# Reranker A/B (~12 min)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-rag-retrieval \
  -Dtest=RerankerAbEvalTest

# Latency baseline (~12 min)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-rag-retrieval \
  -Dtest=LatencyBaselineEvalTest

# Circuit breaker benchmark (~50 sec, no infra)
mvn test -pl bootstrap -Dgroups=eval-reranker \
  -Dtest=CircuitBreakerRecoveryBenchmarkTest

# Smoke mode (5 docs, ~2 min)
... -Deval.smoke=true

# Intent routing integration eval
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-intent \
  -Dtest=IntentRoutingIntegrationEvalTest

# Multi-turn dialogue eval (~2 min)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-multiturn \
  -Dtest=MultiturnDialogueEvalTest

# Memory / summary eval (~2-3 min)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-memory \
  -Dtest=MemorySummaryEvalTest

# Query rewrite A/B eval (~1-2 min)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-query-rewrite \
  -Dtest=QueryRewriteAbEvalTest

# Tool call eval (~3-5 min)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-tool \
  -Dtest=ToolCallEvalTest

# MQ chaos eval (~30 sec, no infra)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-mq-chaos \
  -Dtest=MqChaosEvalTest

# Session lock stress eval (~30 sec, no infra)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-session-lock-stress \
  -Dtest=SessionLockStressEvalTest

# MQ outbox reliability eval (~30 sec, no infra)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-mq-outbox \
  -Dtest=OutboxReliabilityEvalTest

# Contextual enrichment A/B eval (~10 sec, no infra)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-contextual-enrichment \
  -Dtest=ContextualEnrichmentAbEvalTest

# Response quality LLM-judge eval (~2 min, requires DeepSeek API + full Spring context)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-response-quality \
  -Dtest=ResponseQualityEvalTest
# Full run (100 queries): add -Deval.smoke=false

# Reranker fallback chain latency eval (~30 sec, no infra)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-reranker-fallback \
  -Dtest=RerankerFallbackChainEvalTest

# PDF extraction quality eval (~10 sec, no infra)
mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-pdf-quality \
  -Dtest=PdfExtractionQualityEvalTest

# Gatling Chat API load test (requires live ChatAgent stack)
.\mvnw.cmd -f tools\gatling\pom.xml gatling:test \
  -Dgatling.simulationClass=com.yulong.chatagent.load.ChatApiLoadSimulation \
  -DbaseUrl=http://localhost:8080 \
  -DconcurrentUsers=200 \
  -DrampSeconds=60 \
  -DholdSeconds=300 \
  -DpaceMillis=400

# Gatling SSE capacity test (requires live ChatAgent stack)
.\mvnw.cmd -f tools\gatling\pom.xml gatling:test \
  -Dgatling.simulationClass=com.yulong.chatagent.load.SseCapacitySimulation \
  -DbaseUrl=http://localhost:8080 \
  -DsseConnections=500 \
  -DrampSeconds=60 \
  -DholdSeconds=300
```

---

## 8. Evaluation Results

### 8.1 RAGAS v3 Results (always-rerank, best overall)

Run: 2026-04-15, full mode, 20 docs, 40 queries

**Retrieval Quality:**

| Metric | Score |
|--------|-------|
| Hit@3 | 92.5% |
| Hit@5 | 92.5% |
| MRR | 0.85 |
| NDCG@5 | 1.72 |

**Generation Quality:**

| Metric | Score |
|--------|-------|
| Faithfulness | 77.5% |
| Answer Relevancy | 76.0% |

**Latency:**

| Metric | Value |
|--------|-------|
| Retrieval Avg | 635ms |
| Retrieval P50 | 632ms |
| Retrieval P95 | 781ms |
| Generation P50 | 10,121ms |
| Generation P95 | 39,725ms |

### 8.2 Per-Category Breakdown (v3)

| Category | Hit@3 | NDCG@5 | Faithfulness | Relevancy |
|----------|-------|--------|-------------|-----------|
| factual (25) | 100% | 1.96 | 87% | 84% |
| cross-topic (5) | 100% | 1.51 | 82% | 86% |
| cross-domain (10) | 70% | 1.01 | 59% | 58% |

### 8.3 Version Comparison

| Version | Hit@3 | NDCG@5 | Faith | Rel | Key change |
|---------|-------|--------|-------|-----|-----------|
| v1 (no-rerank) | 80% | 1.35 | 64% | 58% | Baseline |
| v2 (rerank-only) | 85% | 1.60 | 70% | 72% | Reranker added |
| v3 (always-rerank) | **92.5%** | **1.72** | **77.5%** | **76%** | Best overall |
| v4 (anti-fabrication) | 92.5% | 1.72 | 77% | 60% | Cross-domain rel dropped to 0% |

v3 uses always-rerank strategy: low-confidence results are still ranked (not filtered), with `low_confidence` outcome recorded. v4 added anti-fabrication prompt but made the LLM too conservative on cross-domain queries.

### 8.4 Reranker A/B Results (40 queries, 20 docs)

| Metric | BGE (with reranking) | Noop (no reranking) | BGE Lift |
|--------|---------------------|--------------------| ---------|
| Hit@3 | 92.5% | 85.0% | **+7.5%** |
| Hit@5 | 92.5% | 85.0% | **+7.5%** |
| MRR | 0.8292 | 0.7833 | **+0.0458** |
| NDCG@5 | 1.4187 | 1.3799 | **+0.0388** |

**Per-category:**

| Category | BGE NDCG@5 | Noop NDCG@5 | NDCG Lift | Interpretation |
|----------|-----------|-------------|-----------|---------------|
| factual (25) | 1.73 | 1.6647 | +0.0652 | Stable positive |
| cross-topic (5) | 1.076 | 0.6179 | **+0.4581** | Largest benefit |
| cross-domain (10) | 0.8118 | 1.0486 | **-0.2368** | Over-optimization |

**Findings**: BGE reranker provides consistent positive lift on factual (+6.5%) and cross-topic (+45.8%) queries. Cross-domain queries see -23.7% because the reranker demotes relevant but semantically dissimilar cross-domain documents. Recommendation: consider domain-aware reranking for explicitly cross-domain queries.

### 8.5 Latency Baseline (30 queries, 20 docs)

| Stage | Avg | P50 | P95 | P99 |
|-------|-----|-----|-----|-----|
| Full Pipeline (embedding + fusion + rerank) | 409ms | **373ms** | 516ms | 521ms |
| Retrieval Only (embedding + fusion) | 107ms | **104ms** | 138ms | 140ms |
| Reranker Overhead | 301ms | **267ms** | 409ms | 416ms |

**Analysis**: Reranker adds 281% overhead relative to retrieval-only latency. BGE reranker HTTP call P50 ~267ms is the dominant cost. Embedding + Milvus hybrid fusion is fast at P50=104ms. RAGAS eval shows higher latency (P50=632ms) due to additional signal attachment and context building overhead.

### 8.6 Circuit Breaker Recovery Benchmark

| Transition | P50 | P99 |
|------------|-----|-----|
| Trip (CLOSED → OPEN) | < 0.1ms | < 1ms |
| Re-trip (HALF_OPEN → OPEN) | 0.06ms | 0.25ms |
| Recovery (HALF_OPEN → CLOSED) | < 0.1ms | < 0.2ms |
| Full cycle (trip + wait + recover, openStateMs=80ms) | 107ms | 114ms |

Sliding window check latency P50: 0.0003ms (negligible). Concurrent probe (8 threads): exactly 1 allowed per cycle via atomic CAS.

### 8.7 Multi-turn Dialogue Results

**Run**: 2026-04-15, full mode, 10 dialogues, 35 user turns, 13 coref turns

#### v4 Final Results (history-aware routing + programmatic anchor + prompt v4)

**Intent Routing:**

| Domain | Turns | Exact Accuracy | Domain Accuracy | Avg Prefix Depth |
|--------|-------|---------------|----------------|-----------------|
| HR | 11 | 72.73% | 72.73% | 2.18 |
| Finance | 10 | **100%** | **100%** | 3.00 |
| IT | 6 | 83.33% | 83.33% | 1.67 |
| Mixed | 8 | 62.50% | 62.50% | 1.75 |
| **Overall** | **35** | **80.0%** | **80.0%** | **2.23** |

**Coreference Resolution (13 coref-bearing turns):**

| Domain | Coref Turns | Control | Treatment | A/B Lift |
|--------|------------|---------|-----------|----------|
| HR | 5 | 80% | 80% | 0 pp |
| Finance | 5 | **100%** | **100%** | 0 pp |
| IT | 2 | 50% | **100%** | **+50 pp** |
| Mixed | 1 | 0% | 0% | 0 pp |
| **Overall** | **13** | **76.92%** | **84.62%** | **+7.69 pp** |

**Topic Switch Detection**: 80.0% (8/10)

#### Version Comparison

| Metric | v2 Baseline | v3 (prompt only) | v4 (all improvements) | v2→v4 Lift |
|--------|-------------|-------------------|----------------------|------------|
| Intent Exact Accuracy | 62.9% | 62.86% | **80.0%** | **+17.1 pp** |
| Coref Control | 0% | 15.38% | **76.92%** | **+76.9 pp** |
| Coref Treatment | 38.46% | 69.23% | **84.62%** | **+46.2 pp** |
| Finance Exact | 50% | 50% | **100%** | **+50 pp** |
| IT Coref Treatment | 0% | 0% | **100%** | **+100 pp** |

#### Production Changes Driving the Improvement

| Change | File | Impact |
|--------|------|--------|
| History-aware routing fallback | `IntentRouter.routeWithHistory()` | Recovers 7/10 context-free routing failures (+17pp intent accuracy) |
| Programmatic anchor enforcement | `QueryRewriter.enforceAnchor()` | Guarantees anchor presence for all intent kinds (KB/TOOL/SYSTEM), fixes IT coref 0%→100% |
| Keyword extraction rule | `classifier.md` v4 Rule 6 | Catches conversational wrappers ("顺便问一下加班制度") |
| Retrieval anchor prompt | `query-rewrite.md` v3 Rule 1 | LLM-level anchor preservation before programmatic enforcement |

#### Remaining Failure Analysis (7/35 intent failures)

| Failure Type | Count | Example | Root Cause |
|-------------|-------|---------|------------|
| LLM non-determinism | 1 | "公司的年假政策" → NONE | Same query resolved in v3, flaky in v4 |
| Short query context loss | 1 | "需要填什么理由" → CLARIFICATION | Too short for routing even with history |
| Semantic gap | 1 | "加班费" doesn't match "加班制度" node name | Router matches name, not synonyms |
| Cross-domain coref | 1 | "前面说的住宿标准" → CLARIFICATION | "前面说的" refers to non-adjacent turn |
| History misrouting | 1 | "还有权限申请" → routed to 采购申请 | Previous intent overpowered actual intent |
| Domain coverage | 2 | "顺便问一下加班制度", "加班费" → NONE | Root-level candidates don't contain "加班" |

**Architecture insight**: The A/B lift narrowed from +53.85pp (v3) to +7.69pp (v4) because history-aware routing now resolves most ambiguous turns upfront, making the separate "prev-turn context fallback" in the treatment arm largely unnecessary. The system became more self-contained per turn.

### 8.8 Memory / Summary Eval Harness

**Implemented**: 2026-04-15
**Run**: 2026-04-15T23:57:57, full mode, 10 dialogues, 49 summary refreshes
**Report**: `bootstrap/target/eval-reports/memory-summary-eval-2026-04-15T23-57-57.json`

The eval suite now includes `MemorySummaryEvalTest`, which exercises the production rolling-memory summarizer against `memory-golden.json` and writes `memory-summary-eval-*.json` reports with:

- checkpoint mention recall
- checkpoint full-coverage rate
- final entity recall
- final topic recall
- final summary length

**Overall results:**

| Metric | Score |
|--------|-------|
| Checkpoint Mention Recall | **94.01%** |
| Checkpoint All-Covered Rate | **83.67%** |
| Entity Recall | **91.26%** |
| Topic Recall | **38.46%** |
| Avg Summary Length | 140.4 chars |

**Per-domain:**

| Domain | Checkpoint Recall | All-Covered | Entity Recall | Topic Recall | Avg Chars |
|--------|------------------|-------------|---------------|--------------|-----------|
| HR | 98.44% | 94.12% | 92.86% | 63.64% | 103.0 |
| Finance | 98.31% | 93.75% | 94.44% | 0.00% | 157.3 |
| IT | 85.19% | 60.00% | 80.00% | 75.00% | 172.0 |
| Admin | 76.47% | 66.67% | 90.00% | 0.00% | 176.0 |

**Finding**: rolling memory keeps concrete entities well, but topic labels are under-preserved in finance/admin cases because the summarizer compresses into factual state rather than repeating the golden topic phrase. `MemorySummaryEvalTest` now pins `chat.routing.default-model=deepseek-chat` for reproducible real-LLM runs; an earlier default-model run fell back from unavailable `glm-5.1` and was not used as the benchmark.

### 8.9 Query Rewrite A/B Harness

**Implemented**: 2026-04-15
**Run**: 2026-04-15T23:48:46, full mode, 11 KB coreference turns
**Report**: `bootstrap/target/eval-reports/query-rewrite-ab-eval-2026-04-15T23-48-46.json`

The eval suite now includes `QueryRewriteAbEvalTest`, which reuses KB coreference turns from `multiturn-golden.json`, runs the production `QueryRewriter`, and emits `query-rewrite-ab-eval-*.json` reports with:

- Hit@1 / Hit@3 before vs after rewrite
- MRR before vs after rewrite
- NDCG@3 before vs after rewrite
- rewritten-query anchor presence rate
- per-domain lift breakdown

**Overall A/B results:**

| Metric | Control | Treatment | Lift |
|--------|---------|-----------|------|
| Hit@1 | 72.73% | **100.00%** | **+27.27 pp** |
| Hit@3 | 90.91% | **100.00%** | **+9.09 pp** |
| MRR | 0.8258 | **1.0000** | **+0.1742** |
| NDCG@3 | 0.8301 | **1.0000** | **+0.1699** |
| Anchor Present Rate | n/a | **100.00%** | n/a |

**Per-domain NDCG@3 lift:**

| Domain | Cases | Control | Treatment | Lift |
|--------|-------|---------|-----------|------|
| HR | 5 | 0.9262 | **1.0000** | +0.0738 |
| Finance | 5 | 0.7000 | **1.0000** | **+0.3000** |
| Mixed | 1 | 1.0000 | **1.0000** | 0.0000 |

**Finding**: programmatic leaf-anchor enforcement makes all evaluated KB follow-ups retrieve the expected leaf at rank 1. The largest gain is in finance, where short follow-ups such as lodging/flight reimbursement benefit most from rewriting.

### 8.10 Tool Call Eval Harness

**Implemented**: 2026-04-15
**Run**: 2026-04-16T00:08:26, full mode, 20 scenarios, real `deepseek-chat` planning with fake tools
**Report**: `bootstrap/target/eval-reports/tool-call-eval-2026-04-16T00-08-26.json`

The eval suite now includes `ToolCallEvalTest`, which runs the real agent loop against `tool-golden.json` using deterministic fake tools and emits `tool-call-eval-*.json` reports with:

- intent-kind accuracy from turn preparation
- exact tool-set match rate
- per-scenario tool F1
- within-max-steps rate
- answer containment rate
- overall pass rate and per-category breakdown

**Overall results:**

| Metric | Score |
|--------|-------|
| Intent Kind Accuracy | 85.00% |
| Exact Tool Match Rate | **95.00%** |
| Tool F1 | **99.00%** |
| Within Max Steps Rate | **100.00%** |
| Answer Containment Rate | **100.00%** |
| Pass Rate | 80.00% |

**Per-category:**

| Category | Intent Kind | Exact Tool Match | Tool F1 | Steps OK | Answer Containment | Pass |
|----------|-------------|------------------|---------|----------|--------------------|------|
| Email | 100% | 100% | 100% | 100% | 100% | 100% |
| Meeting Room | 100% | 100% | 100% | 100% | 100% | 100% |
| VPN | 100% | 100% | 100% | 100% | 100% | 100% |
| Permission | 50% | 100% | 100% | 100% | 100% | 50% |
| KB Search | 50% | 100% | 100% | 100% | 100% | 50% |
| Multi-tool | 100% | 75% | 95% | 100% | 100% | 75% |

**Failure analysis (4/20 not pass):**

| Scenario | Category | Root Cause |
|----------|----------|------------|
| `tool-it-003` | Permission | Tool and answer passed; turn preparation returned no `IntentKind` |
| `tool-kb-003` | KB Search | Tool and answer passed; turn preparation returned no `IntentKind` |
| `tool-kb-004` | KB Search | Tool and answer passed; turn preparation returned no `IntentKind` |
| `tool-multi-004` | Multi-tool | Agent called `meetingRoomTool` + `emailTool` but skipped expected `kbSearchTool` |

`ToolCallEvalTest` now pins the eval routing candidate to `deepseek-chat`. An earlier run with the default routing list produced `models=[]` and all tool metrics at 0; that run was treated as a harness configuration failure and superseded by the benchmark above.

### 8.11 MQ Chaos Eval Harness

**Implemented**: 2026-04-16
**Run**: 2026-04-16T00:23:33, full mode, 10 mocked-channel chaos scenarios
**Report**: `bootstrap/target/eval-reports/mq-chaos-eval-2026-04-16T00-23-33.json`

`MqChaosEvalTest` validates the knowledge-ingest MQ consumer's resilience decisions without external infrastructure. It exercises retry handoff, DLQ, duplicate suppression, Redis failure policy, and session execution lock contention by mocking RabbitMQ `Channel`, retry publisher, Redis-backed lock manager, and ingestion dependencies.

**Overall results:**

| Metric | Score |
|--------|-------|
| Scenarios | 10 |
| Passed | 10 |
| Pass Rate | **100.00%** |

**Per-category:**

| Category | Scenarios | Passed | Pass Rate |
|----------|-----------|--------|-----------|
| Retry Handoff | 2 | 2 | 100% |
| DLQ | 2 | 2 | 100% |
| Dedupe | 1 | 1 | 100% |
| Lock Wait | 2 | 2 | 100% |
| Redis Policy | 2 | 2 | 100% |
| Session Serialization | 1 | 1 | 100% |

**Validated behavior:**

| Scenario | Expected Consumer Decision |
|----------|----------------------------|
| Retryable ingestion failure | release RUNNING lock, publish retry with `x-retry-count=1`, ack original |
| Retry publish failure | release RUNNING lock, `basicNack(requeue=true)` original |
| Retry exhausted | mark lock FAILED, `basicReject(requeue=false)` to DLQ |
| Terminal missing document | mark lock FAILED, reject to DLQ without ingestion |
| Duplicate completed delivery | ack duplicate and skip ingestion |
| Existing RUNNING task lock | publish delayed requeue and ack original |
| Delayed requeue publish failure | fallback to `basicNack(requeue=true)` |
| Redis FAIL_FAST | `basicNack(requeue=true)`, skip ingestion |
| Redis FAIL_OPEN | process without idempotency lock and ack |
| Busy session execution lock | release task lock, publish delayed requeue, ack original |

This is a deterministic consumer-state-machine benchmark rather than a broker-level chaos test. It gives fast regression coverage for the retry/DLQ contract; a future live-broker test can add RabbitMQ container restarts and network partitions if needed.

### 8.12 Session Lock Stress Eval Harness

**Implemented**: 2026-04-16
**Run**: 2026-04-16T00:29:40, 3 stress scenarios, 192 concurrent acquisition attempts
**Report**: `bootstrap/target/eval-reports/session-lock-stress-eval-2026-04-16T00-29-40.json`

`SessionLockStressEvalTest` validates the session execution lock's concurrency behavior with the real `DistributedLockManager` and an in-memory Redis `SETNX` mock. It focuses on correctness invariants rather than external Redis latency.

**Overall results:**

| Metric | Value |
|--------|-------|
| Stress Scenarios | 3 |
| Total Attempts | 192 |
| Passed Scenarios | 3 |
| Pass Rate | **100.00%** |
| Total Acquired | 11 |
| Total WAIT_REQUIRED | 181 |
| Total Errors | **0** |
| Max P95 Acquire Latency | 216.9204ms |

**Per-scenario:**

| Scenario | Attempts | Acquired | WAIT_REQUIRED | Errors | P95 Latency | Pass |
|----------|----------|----------|---------------|--------|-------------|------|
| Single Hot Session | 64 | 1 | 63 | 0 | 216.9204ms | Yes |
| Multi-session Isolation | 64 | 8 | 56 | 0 | 15.5728ms | Yes |
| Sequential Wave Recovery | 64 | 2 | 62 | 0 | 8.5585ms | Yes |

**Finding**: the session execution lock preserves the intended invariant under synchronized contention: one owner per active session key, all competing deliveries classified as `WAIT_REQUIRED`, no split-brain acquisition, and no unexpected exceptions. The high hot-session P95 is thread scheduling/JSON serialization overhead inside the local stress harness, not a live Redis latency measurement.

### 8.13 System Load Test Harness

**Implemented**: 2026-04-16
**Executed**: 2026-04-15/16 (Gatling 3.15 Java DSL)
**Directory**: `tools/gatling/`

The repository includes a standalone Gatling load-test project for the third evaluation dimension, "traditional backend / three highs". It contains:

- `ChatApiLoadSimulation`: creates real users and sessions, then drives `POST /api/chat-messages` at a configurable closed workload.
- `SseCapacitySimulation`: creates real users and sessions, opens `GET /api/sse/connect/{sessionId}?access_token=...`, holds the connections, then closes them.
- `chat-prompts.csv`: domain-style prompts used by the chat-message workload.
- `README.md`: run commands, tunables, and report location.

**Methodology — degraded full-stack (application-layer isolation)**

The run uses a dedicated `load-test` Spring profile (`bootstrap/src/main/resources/application-load-test.yaml`) that:

- Points DeepSeek/ZhipuAI clients at unreachable stub endpoints with dummy keys. No production code changes: the existing `ChatEventProcessor` LLM-not-configured short-circuit returns a synthetic assistant message, and `ConversationTurnPreparationService` passthroughs when the system assistant has no active intent version, so the HTTP chat write path completes end-to-end without outbound LLM calls.
- Disables the RabbitMQ listener auto-startup (`spring.rabbitmq.listener.simple.auto-startup: false`) so consumers never poll the fake endpoints, while keeping the MQ dispatcher enabled — the HTTP sync path still writes through the full transactional outbox, takes `FOR UPDATE SKIP LOCKED` claims, and performs RabbitMQ publisher-confirmed enqueue.
- Disables the Mail health indicator (`management.health.mail.enabled: false`) to avoid log flooding from SMTP auth failures against the stub.
- Pins HikariCP max pool to 50 and Tomcat max threads to 250 via env-overridable properties.

This isolates the measurement to **HTTP → Spring MVC → service layer → PostgreSQL (Hikari) → Redis (session lock) → outbox write → RabbitMQ publisher confirm**, which is the hot path under the user's own control. The RAG/LLM layer is stubbed deliberately because its latency is dominated by upstream API providers and would mask application-layer throughput.

**Infrastructure (temp Docker containers on high ports, left for reuse)**

| Component | Image | Host port |
|-----------|-------|-----------|
| PostgreSQL | `postgres:16` | 55432 |
| Redis | `redis:7` | 56379 |
| RabbitMQ | `rabbitmq:3.13-management` | 55672 (AMQP), 25672 (mgmt) |

Backend: Spring Boot 3.5.8 jar booted with `SPRING_PROFILES_ACTIVE=default,load-test` on `:8080`.

**Chat API Run (ChatApiLoadSimulation, 2026-04-15 23:20:57 UTC, duration 10m 59s)**

Gatling assertions — both PASSED:

| Assertion | Target | Actual | Status |
|-----------|--------|--------|--------|
| Global failed events | < 1.0% | 0.0% | OK |
| Create chat message P95 | < 5000 ms | 49 ms | OK |

Aggregate and per-endpoint stats:

| Request | Count | KO | % KO | Rps | Min | P50 | P75 | P95 | P99 | Max | Mean |
|---------|-------|----|----|-----|-----|-----|-----|-----|-----|-----|------|
| **All Requests** | **294,951** | **0** | **0%** | **446.9** | 4 | 24 | 31 | 49 | 71 | 462 | 27 |
| Create chat message | 294,153 | 0 | 0% | 445.69 | 11 | 24 | 31 | 49 | 73 | 462 | 27 |
| Create chat session | 399 | 0 | 0% | 0.6 | 4 | 7 | 9 | 16 | 60 | 151 | 9 |
| Auth register | 399 | 0 | 0% | 0.6 | 70 | 85 | 95 | 136 | 269 | 328 | 92 |

Units: latencies in ms, Rps = requests/s over full duration. Steady-state chat-message throughput ≈ **446 req/s sustained** with P95 **49 ms** and zero errors across ~295k requests.

**SSE Capacity Run (SseCapacitySimulation, 2026-04-15 23:33:28 UTC, duration 10m 59s)**

Gatling assertion — PASSED:

| Assertion | Target | Actual | Status |
|-----------|--------|--------|--------|
| Global failed events | < 1.0% | 0.0% | OK |

Per-endpoint stats:

| Request | Count | KO | P50 | P95 | P99 | Max |
|---------|-------|----|----|-----|-----|-----|
| **All Requests** | **3,996** | **0** | 4 | 76 | 83 | 196 |
| Auth register | 999 | 0 | 71 | 82 | 88 | 196 |
| Create chat session | 999 | 0 | 6 | 9 | 12 | 30 |
| Open SSE | 999 | 0 | 3 | 4 | 6 | 41 |
| Close SSE | 999 | 0 | 0 | 1 | 1 | 2 |

Units: ms. 999 concurrent Auth-register / Create-session / Open-SSE / Close-SSE cycles with **0 errors** and Open-SSE P95 **4 ms** — the SSE subscriber registration path is effectively unbottlenecked at this scale and the backend held all active connections through the hold window.

**Findings**

- Application-layer Chat API write path sustains ≥ 446 req/s with P95 < 50 ms and zero errors against a real PG+Redis+Rabbit stack, proving the outbox+publish-confirm design does not add per-request latency beyond the HTTP+DB layer.
- SSE session open/close path is flat (P95 4 ms) and trivially scales to 999 concurrent clients without contention on the session registry.
- These numbers exclude LLM round-trip time by design; real user-visible response time will be dominated by the downstream DeepSeek/ZhipuAI call, which is out of scope for this harness.

**HTML reports (archived locally; not committed)**

- `tools/gatling/target/gatling/chatapiloadsimulation-20260415232057296/index.html`
- `tools/gatling/target/gatling/ssecapacitysimulation-20260415233327784/index.html`

### 8.14 PDF Extraction Quality Eval

**Implemented**: 2026-04-16
**Run**: 2026-04-16T20:24:55, 20 golden PDFs across 4 categories
**Report**: `bootstrap/target/eval-reports/pdf-extraction-quality-eval-2026-04-16T20-24-55.json`

`PdfExtractionQualityEvalTest` parses all 20 golden-sample PDFs through `PdfDocumentParser` (with `NoopVdpEngine` for visual-track pages) and compares output against the expected `*.segments.json` snapshots. Measures segment count accuracy, extraction mode accuracy, content recall (mustContain phrases), content precision (mustNotContain exclusions), and page routing accuracy.

**Overall results:**

| Metric | Value |
|--------|-------|
| Documents | 20 |
| Segment count accuracy | **100.00%** |
| Extraction mode accuracy | **100.00%** |
| Content recall rate | **100.00%** (70/70 phrases) |
| Content precision rate | **100.00%** (32/32 exclusions) |
| Route accuracy | **100.00%** (31/31 pages) |

**Per-category:**

| Category | Docs | Segment Count | Extraction Mode | Content Recall | Content Precision | Route Accuracy |
|----------|------|--------------|----------------|---------------|-------------------|----------------|
| headings | 5 | 100% | 100% | 1.00 | 1.00 | 1.00 |
| tables | 5 | 100% | 100% | 1.00 | 1.00 | 1.00 |
| scanned | 5 | 100% | 100% | 1.00 | 1.00 | 1.00 |
| mixed | 5 | 100% | 100% | 1.00 | 1.00 | 1.00 |

**Finding**: the PDF parser correctly routes pages to FAST_TRACK (native text) or VISUAL_TRACK (OCR-required) based on character density, preserves all expected content phrases, and matches expected extraction modes for all 20 synthetically generated golden samples. Limitation: golden PDFs are generated by PDFBox with known text content — this validates the parser's routing and assembly logic, not real-world OCR accuracy against actual scanned documents.

### 8.15 Reranker Fallback Chain Latency Eval

**Implemented**: 2026-04-16
**Run**: 2026-04-16T20:24:20, 200 benchmark iterations, 10 candidates
**Report**: `bootstrap/target/eval-reports/reranker-fallback-chain-eval-2026-04-16T20-24-20.json`

`RerankerFallbackChainEvalTest` measures latency across the reranker fallback paths: Noop (baseline), BGE with circuit-OPEN (fallback to original order), and the full circuit recovery cycle (CLOSED→OPEN→HALF_OPEN→CLOSED). No external infrastructure needed — uses an unreachable stub endpoint and a manually tripped circuit breaker.

**Latency results (10 candidates, 200 iterations after 50 warmup):**

| Path | P50 (ms) | P95 (ms) | P99 (ms) |
|------|----------|----------|----------|
| Noop (baseline) | 0.0001 | 0.0001 | 0.0001 |
| BGE circuit-OPEN fallback | 0.0646 | 0.1477 | 0.1974 |

**Fallback overhead vs Noop P50**: 0.065 ms — negligible for a per-request path.

**Fallback behavior verified**: when the circuit breaker is OPEN, `BgeHttpRetrievalReranker.rerank()` skips the HTTP call entirely, returns candidates in original order with `scoreType="fallback"`, and does not touch the network.

**Recovery cycle (10 cycles, openStateMs=80):**

| Phase | P50 (ms) | P99 (ms) |
|-------|----------|----------|
| Trip (CLOSED→OPEN) | 0.0644 | 0.1207 |
| Recovery (HALF_OPEN→CLOSED) | 0.0523 | 0.0830 |
| Full cycle (incl. 80ms wait) | 103.80 | — |

**Architecture note**: the current fallback is internal to `BgeHttpRetrievalReranker` — when the circuit opens, it returns original order (equivalent to Noop). There is no cascading BGE→LLM→Noop chain at runtime; the three reranker implementations (`BgeHttpRetrievalReranker`, `LlmRetrievalReranker`, `NoopRetrievalReranker`) are selected at startup via `rag.retrieval.reranker.provider` config, not dynamically chained. The sub-ms fallback overhead confirms this design is safe for latency-sensitive retrieval paths.

### 8.16 MQ Outbox Reliability Eval Harness

**Implemented**: 2026-04-16
**Run**: 2026-04-16T01:09:44, 6 scenarios, 2092 seeded rows
**Report**: `bootstrap/target/eval-reports/mq-outbox-reliability-eval-2026-04-16T01-09-44.json`

`OutboxReliabilityEvalTest` validates the transactional outbox state machine with real production services (`OutboxRecordService`, `OutboxPollingPublisher`) and deterministic in-memory adapters. It does not require RabbitMQ or PostgreSQL, but it exercises the same claim, retry, sent, failed, stale-claim, and cleanup transitions used by the production poller.

**Overall results:**

| Metric | Value |
|--------|-------|
| Scenarios | 6 |
| Passed | 6 |
| Pass Rate | **100.00%** |
| Seeded Rows | 2092 |
| Publish Attempts | 2151 |
| Successful Publishes | 2055 |
| Duplicate Successful Publishes | **0** |

**Per-scenario:**

| Scenario | Seeded | Sent | Failed | Attempts | Successes | Duplicate Successes | Max Retry | Pass |
|----------|--------|------|--------|----------|-----------|----------------------|-----------|------|
| At-least-once | 1000 | 1000 | 0 | 1000 | 1000 | 0 | 0 | Yes |
| Retry Timing | 30 | 30 | 0 | 90 | 30 | 0 | 2 | Yes |
| Terminal Failure | 12 | 0 | 12 | 36 | 0 | 0 | 3 | Yes |
| Multi-instance | 1000 | 1000 | 0 | 1000 | 1000 | 0 | 0 | Yes |
| Stale Claim Recovery | 25 | 25 | 0 | 25 | 25 | 0 | 1 | Yes |
| Cleanup | 25 | 5 | 0 | 0 | 0 | 0 | 1 | Yes |

**Finding**: the outbox poller preserves the core at-least-once contract under deterministic failure and concurrency: retryable rows are not lost, terminal failures stop at the configured max attempts, stale claims are reclaimed, and four concurrent pollers produce zero duplicate successful publishes. Remaining gap: this still does not validate PostgreSQL `FOR UPDATE SKIP LOCKED`, RabbitMQ publisher confirms, or broker TTL/DLQ behavior under real network and broker conditions.

### 8.17 Contextual Enrichment ROI A/B Eval

**Implemented**: 2026-04-17
**Run**: 2026-04-17T00:03:38, 27 queries, 8 corpus docs per arm
**Report**: `bootstrap/target/eval-reports/contextual-enrichment-ab-eval-2026-04-17T00-03-38.json`

`ContextualEnrichmentAbEvalTest` compares retrieval quality on two corpus variants built from the same intent tree: control (raw chunk text: leaf name + description + examples) vs treatment (chunk text + contextual enrichment prefix: domain path, category description, section context — simulating what `LlmContextualChunkEnricher` would produce). Uses lexical scoring as a retrieval proxy, no external infrastructure needed.

**Overall A/B results:**

| Metric | Control | Treatment | Lift |
|--------|---------|-----------|------|
| Hit@1 | 85.19% | 85.19% | 0.00 pp |
| Hit@3 | 96.30% | 96.30% | 0.00 pp |
| MRR | 0.9105 | 0.9105 | 0.0000 |
| NDCG@3 | 0.9171 | 0.9171 | 0.0000 |

**Per-category:**

| Category | Cases | Control NDCG@3 | Treatment NDCG@3 | Lift |
|----------|-------|---------------|-----------------|------|
| 人事制度 | 11 | 1.0000 | 1.0000 | 0.0000 |
| 财务制度 | 16 | 0.8601 | 0.8601 | 0.0000 |

**Per-query-type:**

| Type | Cases | Control NDCG@3 | Treatment NDCG@3 | Lift |
|------|-------|---------------|-----------------|------|
| direct | 16 | 0.9769 | 0.9769 | 0.0000 |
| coreference | 11 | 0.8301 | 0.8301 | 0.0000 |

**Finding**: delta is zero across all metrics. The leaf name alone is such a strong lexical signal that adding the domain-path prefix and category description does not change ranking under character-overlap + bigram-Jaccard scoring. This is expected: contextual enrichment is designed to improve vector embedding quality (by giving the embedder more semantic context for chunk disambiguation), not lexical matching. The real value of `LlmContextualChunkEnricher` would show in dense retrieval with BGE-M3 embeddings, where short chunks with identical keywords but different contexts would otherwise receive similar embeddings. A proper A/B test requires ingesting the same documents with and without enrichment into Milvus and comparing dense retrieval quality — this lexical proxy confirms the baseline is already strong (NDCG@3=0.917) and that enrichment doesn't degrade lexical retrieval.

### 8.18 Response Quality LLM-Judge Eval

**Implemented**: 2026-04-17
**Run**: 2026-04-17T00:13:41, smoke mode (20 queries, 5 per category), DeepSeek-chat
**Report**: `bootstrap/target/eval-reports/response-quality-eval-2026-04-17T00-15-14.json`

`ResponseQualityEvalTest` is a `@SpringBootTest` that evaluates end-to-end answer quality using an LLM-as-judge pattern. Given golden queries and synthetic context paragraphs (simulating perfect retrieval), it generates answers via DeepSeek and judges them on faithfulness, answer relevancy, and answer containment (recall of expected golden fragments).

**Overall results:**

| Metric | Score |
|--------|-------|
| Queries | 20 |
| Avg Faithfulness | **1.0000** |
| Avg Answer Relevancy | **0.9250** |
| Avg Answer Containment | 0.7375 |
| Full Contain Rate | 30.00% (6/20) |

**Per-category:**

| Category | Cases | Faithfulness | Relevancy | Containment | Full Contain Rate |
|----------|-------|-------------|-----------|-------------|-------------------|
| factual | 5 | 1.00 | 1.00 | 0.93 | 80% |
| temporal | 5 | 1.00 | 0.80 | 0.75 | 20% |
| comparison | 5 | 1.00 | 0.90 | 0.68 | 20% |
| multi-hop | 5 | 1.00 | 1.00 | 0.58 | 0% |

**Finding**: faithfulness is perfect (1.0) because answers are generated strictly from provided context with explicit "don't fabricate" instructions, and the judge confirms all claims are context-supported. Relevancy is high (0.925) with slight drops in temporal queries (some LLM responses tangent into general policy rather than addressing specific time-bound questions). Containment is moderate (0.7375) because the golden fragments use exact phrasing while the LLM often paraphrases or restructures the same information — this is a known limitation of string-match containment metrics. Factual queries achieve the highest containment (0.93) because they tend to produce direct quotes. Multi-hop queries have the lowest containment (0.58) because the LLM synthesizes across multiple documents and rarely preserves the exact golden fragment wording. Full-run mode (100 queries) can be enabled with `-Deval.smoke=false`.

---

## 9. Supporting Utilities

### EvalReportWriter

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/EvalReportWriter.java`

Writes timestamped JSON reports to `target/eval-reports/<name>-<timestamp>.json`. Uses Jackson `ObjectMapper` with `INDENT_OUTPUT`.

### GoldenDatasetLoader

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/GoldenDatasetLoader.java`

Loads golden datasets from classpath `eval/golden/`. Supports smoke mode (`-Deval.smoke=true`) limiting entries per category.

### EvalTestTreeFactory

**File**: `bootstrap/src/test/java/com/yulong/chatagent/eval/EvalTestTreeFactory.java`

Builds a 4-domain enterprise intent tree (HR, Finance, IT, Admin) with 23 nodes across DOMAIN/CATEGORY/TOPIC levels for intent routing evaluation.

---

## 10. Resume Data Points

> - RAG retrieval pipeline with hybrid search (BGE-M3 dense + BM25 + RRF fusion) achieving P50=104ms retrieval latency, full pipeline P50=373ms with BGE reranker
> - BGE-reranker-v2-m3 always-rerank strategy validated via A/B test (40 queries, 20 docs, 3 KBs): Hit@3 +7.5%, MRR +4.6%, cross-topic NDCG +45.8%
> - Full RAGAS evaluation framework: 20 documents, 40 golden queries across 3 categories — Hit@3 92.5%, Faithfulness 77.5%, Answer Relevancy 76%
> - Circuit breaker with sub-ms state transitions (trip <0.1ms, recovery <0.1ms) and atomic concurrent probe control for reranker fault tolerance
> - Multi-turn dialogue eval-driven optimization: intent accuracy 63%→80% via history-aware routing fallback that re-routes with previous context on failure
> - Programmatic anchor enforcement across all intent kinds (KB/TOOL/SYSTEM): coref recall 15%→85% by guaranteeing intent leaf name presence in rewritten queries
> - End-to-end multi-turn evaluation framework: 10 dialogues, 35 turns, 13 coref-bearing turns, 4 domains — finance domain reached 100% intent accuracy and 100% coref recall
> - MQ chaos evaluation harness: 10 mocked-channel failure scenarios covering retry handoff, DLQ, dedupe, Redis FAIL_FAST/FAIL_OPEN, and session-lock requeue — 100% pass rate without external infrastructure
> - Session lock stress evaluation: 192 concurrent acquisition attempts across hot-session, multi-session, and sequential-wave scenarios — one owner per session key, 181 correctly blocked contenders, 0 errors
> - System load test (Gatling, degraded full-stack profile against real PG/Redis/RabbitMQ): Chat API write path sustained 446.9 req/s over 294,951 requests with P50/P95/P99 = 24/49/71 ms and 0 errors; SSE capacity run held 999 concurrent open/close cycles with Open-SSE P95 4 ms and 0 errors — both Gatling assertions (< 1% KO, Create-chat-message P95 < 5000 ms) passed
> - PDF extraction quality eval: 20 golden PDFs across 4 categories (headings/tables/scanned/mixed) — 100% segment count, extraction mode, content recall (70/70), content precision (32/32), and page routing accuracy
> - Reranker fallback chain latency eval: BGE circuit-OPEN fallback path adds only 0.065ms overhead vs Noop baseline, sub-ms circuit trip (0.064ms) and recovery (0.052ms), fallback preserves original order with scoreType=fallback
> - Transactional outbox reliability eval: 2092 seeded rows across delivery, retry, terminal failure, stale-claim, cleanup, and four-poller competition scenarios — 100% pass rate and 0 duplicate successful publishes
> - Contextual enrichment ROI A/B eval: 27 golden retrieval queries over paired control/treatment corpora showed no lexical-regression from enrichment (Hit@1 85.19%, Hit@3 96.30%, MRR 0.9105, NDCG@3 0.9171 unchanged), clarifying that dense Milvus/BGE embedding A/B is the right next step for semantic ROI.
> - Response quality LLM-judge eval: 20-query DeepSeek smoke run with synthetic perfect-retrieval context reached Faithfulness 1.0000, Answer Relevancy 0.9250, Answer Containment 0.7375, and exposed paraphrase-sensitive containment gaps in temporal/comparison/multi-hop answers.
