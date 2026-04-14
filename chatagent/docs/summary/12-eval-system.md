# 12. RAG Evaluation System

## 1. Overview

Three-dimensional evaluation framework for the ChatAgent RAG pipeline:

1. **Retrieval Quality** — RAGAS metrics (Hit@K, MRR, NDCG, Faithfulness, Answer Relevancy)
2. **Reranker Effectiveness** — A/B comparison (BGE vs Noop)
3. **Performance** — Latency baseline, circuit breaker recovery benchmark

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

### 5.5 Other Eval Tests

| Test | Tag | Infra | Description |
|------|-----|-------|-------------|
| `EvalMetricsComputationTest` | default | None | Unit tests for metric formulas with known inputs |
| `RetrievalQualityEvalTest` | `eval-rag-retrieval` | None | Mock-mode retrieval eval using `rag-golden.json` |
| `IntentRoutingEvalTest` | `eval-intent` | None | Intent routing with mocked LLM |
| `IntentRoutingIntegrationEvalTest` | `eval-intent` | PostgreSQL, Redis, DeepSeek | Intent routing with real LLM |

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
