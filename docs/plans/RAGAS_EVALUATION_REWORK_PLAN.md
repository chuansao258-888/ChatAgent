# RAGAS Evaluation Rework Plan

Status: Approved for implementation; Phases 1-9 accepted; Phase 10 in progress; Phase 10a production document-ingestion accepted-size export + all-rows tuning completed with MinerU/MQ full-chain coverage and 200 real downloaded documents; old SciFact real-embedding 10a retired from acceptance
Date: 2026-06-08 (Phase 10a replacement amendment: 2026-06-08)
Scope: Replace the legacy ad hoc evaluation package with an opt-in, modular, reproducible evaluation system for RAGAS, retrieval/text recall, memory, and adjacent agent modules.

## Product Summary

ChatAgent currently has a broad evaluation package under `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval`, but it mixes RAG quality, RAGAS-like LLM judging, memory quality, query rewrite, tools, PDF extraction, and MQ/lock benchmarks in one JUnit test package.

The new system should make evaluation a first-class workflow:

- Default unit/integration tests stay fast and do not write eval reports.
- RAGAS evaluation uses the actual Ragas library or a clearly named compatibility mode, not a hand-rolled "RAGAS-style" judge.
- Retrieval and text recall metrics are deterministic, dataset-backed, and separated from answer-quality judge metrics.
- Memory evaluation covers L1 loader behavior, L2 compaction quality, L3 promotion, and L3 recall.
- All suites emit one normalized report schema with baselines, thresholds, run metadata, and per-sample failures.
- Every quality-affecting numeric configuration is inventoried, reproducibly tuned on real-data development splits, and promoted only after sealed holdout verification.

## Current Understanding

The old evaluation system is mostly JUnit-based and lives in:

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/`
- `chatagent/bootstrap/src/test/resources/eval/golden/`
- `chatagent/bootstrap/target/eval-reports/`
- `docs/summary/12-eval-system.md`
- `chatagent/bootstrap/pom.xml` under `surefire.excludedGroups`

The old "RAGAS" test is `RetrievalQualityIntegrationEvalTest`. It is not a Python Ragas run. It computes Hit@K, MRR, NDCG in Java, then uses a manually written DeepSeek judge prompt for faithfulness and answer relevancy.

Ragas official docs currently expose RAG metrics such as Context Precision, Context Recall, Context Entities Recall, Noise Sensitivity, Response Relevancy, and Faithfulness, and also agent/tool metrics such as Tool Call Accuracy and Tool Call F1. Ragas `evaluate()` accepts a dataset, metrics, optional LLM/embeddings, run config, callbacks, batching, and column mapping. Its `SingleTurnSample` supports fields that map well to ChatAgent export data: `user_input`, `retrieved_contexts`, `reference_contexts`, `retrieved_context_ids`, `reference_context_ids`, `response`, `reference`, and `rubric`.

Sources checked:

- Ragas available metrics: https://docs.ragas.io/en/latest/concepts/metrics/available_metrics/
- Ragas evaluate API: https://docs.ragas.io/en/latest/references/evaluate/
- Ragas evaluation dataset: https://docs.ragas.io/en/latest/concepts/components/eval_dataset/
- Ragas evaluation schema: https://docs.ragas.io/en/latest/references/evaluation_schema/

## Assumptions

- The new RAGAS suite uses official Python Ragas through `tools/eval`. Java-only metrics remain deterministic retrieval/text-recall metrics and must not be labeled as RAGAS.
- Evaluation must be opt-in and never run as part of default Maven tests.
- Generated reports are local artifacts, not committed source files.
- Old local/private course PDF paths should be removed from source code. If those PDFs remain useful, they should become an explicit local corpus path, not hardcoded Java constants.
- Eval-created database, knowledge-base, Milvus, and document-storage state must be namespaced by run ID and must clean only its own resources.
- Judge/model/provider choices should be configurable through eval-specific variables and should not hardcode `deepseek-chat` in tests.
- Primary evaluation samples must come from real public corpora or published benchmark datasets, not from simple hand-written toy examples.
- Synthetic datasets may be used only for stress, fault injection, or fixture-level edge cases; they must not be the source of truth for headline RAGAS/retrieval/memory quality gates.
- The new evaluation system should be a clean-room reset. Legacy golden files, inline toy cases, and old synthetic enterprise samples are audit inputs only; they must not be migrated into v2 datasets, v2 thresholds, or headline baselines.
- Accuracy is the first optimization objective. Latency, cost, reliability, and safety remain hard constraints or tie-breakers so the selected configuration is usable in production.
- "Highest accuracy" means the best sealed-holdout-validated candidate within a declared parameter space and trial budget; the system must not make an unverifiable global-optimum claim.
- The tuning system must not optimize against the sealed test/holdout split. Parameter search uses train/development data only; the sealed holdout is opened only for final candidate verification.
- Every numeric runtime/eval setting must be registered as quality-tunable, operational-only, safety-fixed, or intentionally excluded with a written rationale. Unregistered magic numbers are not accepted.

## Scope

In scope:

- Legacy eval audit and cleanup.
- New dataset schema and corpus layout.
- New normalized result schema and report writer.
- RAG retrieval and RAGAS suites.
- Text recall accuracy suite for chunks, parsers, and retrieved contexts.
- Memory suites for L1, L2, L3 promotion, and L3 recall.
- Query rewrite, intent, tool-call, and response quality suites if they share the new framework.
- Accuracy-first parameter search, experiment tracking, champion selection, and reviewed configuration promotion.
- Documentation and run commands.

Out of scope for the first implementation:

- UI dashboard for eval results.
- Production telemetry storage for every eval run.
- Full hosted CI for GPU/Milvus/LLM suites.
- New architecture decisions for model/provider routing unless the user requests them.

Deferred:

- Automated testset generation with Ragas.
- LangSmith/Arize/Langfuse style observability integration.
- Statistical significance dashboards beyond baseline deltas and bootstrap confidence intervals in JSON/Markdown reports.

## Real Sample Requirements

This is now a hard gate for the rework.

### Sample Source Policy

Primary sample sources must be one of:

- Third-party public benchmarks with real corpora, queries, and relevance labels.
- Public real-world files downloaded from official or clearly licensed sources, with references derived from source text, structured data, or human review.
- Project-owned real operational exports only if sanitized and explicitly approved.

Primary sample sources must not be:

- Small hand-written examples created only to make the suite pass.
- Unverifiable local private files.
- Synthetic company documents as the only benchmark.
- LLM-generated questions without grounding in a real document and without validation.

Synthetic data is still allowed for:

- Unit tests for metric formulas.
- Failure/chaos scenarios.
- Prompt-injection edge cases.
- Load/stress volume where realism is documented as synthetic.

### Recommended Public Sources

| Source | Why It Fits | Planned Use | Notes |
|---|---|---|---|
| [BEIR](https://huggingface.co/datasets/BeIR/trec-covid) | Heterogeneous IR benchmark spanning QA, biomedical IR, fact checking, news, argument retrieval, duplicate question retrieval, citation prediction, and entity retrieval | Main retrieval and text recall baseline | Prefer subsets with manageable size first: SciFact, NFCorpus, FiQA, TREC-COVID, NQ sample |
| [MIRACL](https://github.com/project-miracl/miracl) | Multilingual retrieval benchmark with human annotations across 18 languages and large Wikipedia-derived corpora, including Chinese and English | Multilingual/Chinese retrieval recall and reranker behavior | Use sampled language slices instead of ingesting full corpora at first |
| [IBM MTRAG](https://github.com/IBM/mt-rag-benchmark) | Human-generated multi-turn RAG benchmark with four document corpora and 842 evaluation tasks | Multi-turn RAG, query rewrite, coreference, conversation retrieval | Good replacement for hand-written multiturn golden cases |
| [SEC EDGAR](https://www.sec.gov/search-filings/edgar-application-programming-interfaces) | Official public company filings and XBRL APIs | Financial HTML/table retrieval, numeric fact recall, document parser stress | Use filings and XBRL facts to build verifiable references |
| [PubMed Central Open Access subset](https://pmc.ncbi.nlm.nih.gov/tools/openftlist) | Real open-access biomedical papers | Long scientific documents, citation-like retrieval, dense technical text | Respect article license metadata |
| [arXiv API / bulk access](https://arxiv.org/help/api/user-manual) | Real technical papers and PDFs | PDF ingestion, formula/table/headings, long context retrieval | Use small curated lists by category |
| [Data.gov](https://data.gov/) / official government PDFs | Real policy, reports, notices, forms | Government/policy document retrieval and PDF extraction | Store source URL and retrieval date in corpus manifest |
| [EnterpriseRAG-Bench](https://github.com/onyx-dot-app/EnterpriseRAG-Bench) | Large realistic but synthetic internal-company benchmark | Optional enterprise-scale stress and comparison only | Not a primary "real sample" quality gate because it is synthetic |

### Approved First Slice

Phase 0 approves this subset for the first implementation slice:

- BEIR SciFact for deterministic retrieval because it provides a real corpus, queries, and qrels at a manageable first-slice size.
- IBM MTRAG human tasks for multi-turn RAG, query rewrite, coreference, and conversation-shaped memory discovery.
- A small SEC EDGAR filing/XBRL set for real HTML/table/numeric text recall.

Defer BEIR NFCorpus, MIRACL, PMC OA, arXiv, and Data.gov until downloader caching, manifest validation, and report contracts are stable. Keep EnterpriseRAG-Bench outside the primary source subset; it belongs only under synthetic stress/comparison suites.

Phase 0 source access check on 2026-06-06:

- Ragas docs pages for `evaluate()` and available metrics returned HTTP 200 in a lightweight HEAD check.
- Hugging Face `BeIR/scifact` dataset and its `corpus` tree returned HTTP 200 in a lightweight HEAD check.
- IBM MTRAG `mtrag-human` tree and `LICENSE` returned HTTP 200 in a lightweight HEAD check.
- SEC `data.sec.gov/submissions/CIK0000320193.json` returned HTTP 200 in a lightweight GET check. The SEC documentation page was accessible in browser/web verification; script access to SEC pages must use an SEC-compliant User-Agent and respect SEC rate/automation rules. Phase 3 downloader validation must still verify SEC bulk ZIP or selected filing downloads with hashes before full-suite use.

### Minimum Sample Size Targets

Smoke mode remains small for fast local checks, but headline metrics require enough samples to be meaningful.

| Suite | Smoke Target | Full Target | Stratification |
|---|---:|---:|---|
| Retrieval deterministic metrics | 50-100 queries, 1k-5k documents/passages | 1k+ queries, 50k+ documents/passages | Domain, query type, answerability, single-hop/multi-hop |
| RAGAS answer/context quality | 30-50 queries | 300-500 queries | Domain, context length, answerability, expected evidence count |
| Text/parser recall | 25-50 real files | 200+ real files | PDF/HTML/Markdown/table-heavy/scanned or low-text-density where supported |
| Memory L2/L3 quality | 20-30 real or human-authored conversation tasks | 200+ conversation tasks | User preference, durable fact, transient fact, contradiction, correction |
| Multi-turn RAG | 20 conversations | 100+ conversations or MTRAG human full set | Coreference, topic switch, underspecified, unanswerable |
| Reranker A/B | 100 queries | 1k+ queries | Cross-domain, semantic low-overlap, multi-evidence, noisy candidates |

If a public dataset cannot provide enough samples for a specific module, the plan should combine multiple real datasets rather than padding with toy samples.

### Memory Conversation Task Sources

Memory evaluation needs conversation-shaped inputs, but the source policy still applies.

- Use IBM MTRAG human tasks as the first public source for multi-turn conversation flow, query rewriting, topic switches, and coreference.
- For durable user preference/fact/correction cases that no public benchmark covers directly, create human annotation tasks grounded in approved real public documents. Each annotated dialogue must record the source document IDs, source URLs, annotator/reviewer metadata, and expected durable versus transient facts.
- Public forum, helpdesk, support, or email-style conversation datasets may be added only after license, privacy, and redistribution terms are approved in the manifest.
- Project-owned operational conversation exports may be used only if sanitized and explicitly approved; they must not leak private raw messages into committed datasets or reports.

Synthetic memory dialogues are allowed only as separately labeled fixture/stress cases for tool-message edge cases, token-budget pressure, prompt injection, or failure handling. They must not count toward the primary L2/L3 quality gate sample totals.

### Data Manifest Requirements

Every downloaded corpus must include a manifest entry:

```json
{
  "datasetId": "mtrag-human-dev",
  "sourceUrl": "https://github.com/IBM/mt-rag-benchmark/tree/main/mtrag-human",
  "license": "Apache-2.0",
  "licenseUrl": "https://github.com/IBM/mt-rag-benchmark/blob/main/LICENSE",
  "downloadedAt": "2026-06-06T00:00:00Z",
  "documentCount": 110,
  "queryCount": 842,
  "hash": "sha256:...",
  "localPath": "data/eval/raw/mtrag/human",
  "split": "dev",
  "notes": "Apache-2.0 applies to the MTRAG benchmark repository. Related corpus manifests must record any upstream corpus-specific terms separately."
}
```

Never copy placeholder license text into a corpus manifest. During Phase 3, each manifest must record the actual license, terms, or public-data status for that exact source, plus the URL used to verify it. Manifest validation should fail on generic values such as `dataset-specific license`, `unknown`, or `TBD`.

Reports must record dataset IDs and hashes so scores can be reproduced without relying on moving web content.

## Current Project Findings

### Old Evaluation Inventory

| Area | Current Files | Current Behavior | Rework Decision |
|---|---|---|---|
| Shared metrics/reporting | `EvalMetrics.java`, `EvalReportWriter.java`, `EvalMetricsComputationTest.java` | Java utility metrics and timestamped JSON reports under `target/eval-reports` | Keep concepts, replace with versioned report schema and deterministic metric library |
| Golden loading | `GoldenDatasetLoader.java`, DTO records, `eval/golden/*.json` | One loader for intent/RAG/memory/multiturn/tool JSON | Replace with typed dataset registry and schema validation |
| Old RAGAS-like full eval | `RetrievalQualityIntegrationEvalTest.java` | Destructive KB setup, hardcoded absolute PDF paths, Java metrics, manual LLM judge | Retire after new RAG export + Ragas runner exists |
| Mock retrieval eval | `RetrievalQualityEvalTest.java` | Simulated rankings from golden relevance data | Replace with deterministic metric unit tests and retrieval fixture tests |
| Reranker A/B | `RerankerAbEvalTest.java`, `LatencyBaselineEvalTest.java`, `CircuitBreakerRecoveryBenchmarkTest.java`, `RerankerFallbackChainEvalTest.java` | Mix of live infra A/B and pure circuit benchmark | Move reranker A/B into retrieval suite; keep circuit benchmark as benchmark, not RAGAS |
| Response quality | `ResponseQualityEvalTest.java` | Synthetic contexts, hand-rolled judge, string containment | Replace with Ragas answer quality plus deterministic containment/semantic similarity |
| Query rewrite | `QueryRewriteAbEvalTest.java` | Real rewriter plus lexical retrieval proxy | Port to module suite with typed output and thresholds |
| Contextual enrichment | `ContextualEnrichmentAbEvalTest.java` | Lexical proxy for contextual enrichment ROI | Port or retire; should not be default test |
| Memory | `MemorySummaryEvalTest.java`, `memory-golden.json` | Real LLM incremental summarizer, string mention/entity/topic recall | Replace with L1/L2/L3 suites aligned with Memory Compaction V2 and L3 Milvus recall |
| Multi-turn/intent/tool | `IntentRouting*`, `MultiturnDialogueEvalTest`, `ToolCallEvalTest` | Real or mocked LLM evals with custom reports | Migrate to agent module suites after RAG/memory core is in place |
| PDF/text extraction | `PdfExtractionQualityEvalTest.java`, `golden-pdfs` resources | Parser quality reports for generated PDFs | Fold into text recall/parser suite |
| MQ/locks/outbox | `MqChaosEvalTest.java`, `OutboxReliabilityEvalTest.java`, `LiveMqReliabilityEvalTest.java`, `SessionLockStressEvalTest.java` | Reliability/chaos benchmarks | Move to `benchmark` or `reliability-eval`; keep separate from RAGAS |

### Immediate Safety Problems

`chatagent/bootstrap/pom.xml` excludes these eval groups by default:

- `eval-intent`
- `eval-rag-retrieval`
- `eval-rag-e2e`
- `eval-agent`
- `eval-memory`
- `eval-mq-live`
- `eval-mq-chaos`
- `eval-multiturn`
- `eval-query-rewrite`
- `eval-response-quality`
- `eval-tool`

But current eval package also uses tags not excluded by default:

- `eval-contextual-enrichment`
- `eval-mq-outbox`
- `eval-pdf-quality`
- `eval-reranker`
- `eval-reranker-fallback`
- `eval-session-lock-stress`

This explains why default or broad test runs can create many `chatagent/bootstrap/target/eval-reports/*.json` files.

### Design Problems To Fix

- Hardcoded absolute paths to local course folders in RAG/Reranker evals.
- Setup deletes all existing KBs instead of only eval-owned resources.
- Old RAGAS does not use actual Ragas metrics or dataset schema.
- Golden data lives partly in JSON and partly as Java inline query definitions.
- Report schema is inconsistent across tests.
- Thresholds are weak or absent, often only checking "not empty" or `> 0.3`.
- LLM judge provider/model is hardcoded in tests.
- Some evals rely on lexical proxies but are named as if they measure production retrieval.
- Historical results in `docs/summary/12-eval-system.md` are mixed with design documentation and old commands.
- Generated result files accumulate locally under `target/eval-reports`.

## Domain Language / ADR Impact

Use project terms from `CONTEXT.md`: Agent, Session, Turn, Trace, Provider, Tool, Environment Variable, and Review Finding.

Memory work must respect ADR 0001:

- L2 Memory Compaction V2 uses structured synopsis plus segment rows.
- Old L2 summary compatibility is not required.
- Raw `chat_message`, L3 `memory_item`, and L3 extraction logs are not reset by V22.

The new memory eval should therefore measure V2 segment ranges, structured synopsis, L1 tail behavior, and L3 promotion/recall directly instead of only checking one rolling text summary.

No new ADR is required for the plan itself. If implementation chooses a long-lived Python Ragas dependency and cross-language eval runner as a hard project standard, record an ADR only if the team treats it as hard to reverse.

## Production Planning Gates

### Configuration / Environment

Existing relevant variables and config families:

- `CHATAGENT_RAG_EMBEDDING_BASE_URL`
- `CHATAGENT_RAG_EMBEDDING_MODEL`
- `CHATAGENT_RAG_TOP_K`
- `CHATAGENT_RAG_CANDIDATE_K`
- `CHATAGENT_RAG_RRF_K`
- `CHATAGENT_RAG_RERANKER_*`
- `CHATAGENT_RAG_VECTOR_STORE_PROVIDER`
- `CHATAGENT_MILVUS_*`
- `CHATAGENT_MEMORY_*`
- `CHATAGENT_DEEPSEEK_*`
- `CHATAGENT_ZHIPUAI_*`

New eval-specific variables to add or document:

- `CHATAGENT_EVAL_OUTPUT_DIR`
- `CHATAGENT_EVAL_RUN_ID`
- `CHATAGENT_EVAL_EXPERIMENT_ID`
- `CHATAGENT_EVAL_CORPUS_DIR`
- `CHATAGENT_EVAL_DATASET`
- `CHATAGENT_EVAL_MODE`
- `CHATAGENT_EVAL_RESET_OWNED_DATA`
- `CHATAGENT_EVAL_JUDGE_PROVIDER`
- `CHATAGENT_EVAL_JUDGE_MODEL`
- `CHATAGENT_EVAL_RAGAS_LLM_PROVIDER`
- `CHATAGENT_EVAL_RAGAS_LLM_MODEL`
- `CHATAGENT_EVAL_RAGAS_EMBEDDING_MODEL`
- `CHATAGENT_EVAL_MAX_CASES`
- `CHATAGENT_EVAL_SMOKE`
- `CHATAGENT_EVAL_PARAMETER_SPACE`
- `CHATAGENT_EVAL_OPTIMIZER`
- `CHATAGENT_EVAL_TRIAL_BUDGET`
- `CHATAGENT_EVAL_RANDOM_SEED`
- `CHATAGENT_EVAL_TUNING_SPLIT`
- `CHATAGENT_EVAL_HOLDOUT_SPLIT`
- `CHATAGENT_EVAL_MAX_LATENCY_P95_MS`
- `CHATAGENT_EVAL_MAX_COST_USD`
- `CHATAGENT_EVAL_PROMOTE_CONFIG`

Phase 0 judge/model default:

- Default judge provider: `deepseek`.
- Default judge model: `deepseek-chat`.
- Ragas LLM provider/model default to the same provider/model unless explicitly overridden.
- Semantic memory metrics use the same judge provider/model by default.
- Judge and Ragas LLM settings must remain eval-specific configuration, not hardcoded inside tests. If provider credentials are unavailable, judge/Ragas LLM metrics are skipped or warn-only rather than blocking deterministic suites.

Do not put real values in committed docs or logs. `docs/env_variables.txt` is secret-bearing and should be used only to check coverage.

The user confirmed that local provider credentials and environment settings are maintained in `docs/env_variables.txt`. Planning and implementation may inspect variable names and coverage from that file, but must never print, copy, or commit its values. The current process environment does not need to contain the credentials during cleanup/core-contract phases; provider-backed phases must load them through the approved local execution path and retain skip/warn behavior when unavailable.

### Data / Migration

No production DB migration is required for the evaluation framework.

Eval data isolation requirements:

- Eval-created KB names and document names must include `eval-{runId}`.
- Eval cleanup must query and delete only resources with the run prefix or recorded manifest.
- Milvus collections should use either dedicated eval collection names or test-owned document/source IDs.
- Local document storage paths should stay under eval-owned run directories.

### Security / Privacy

- Reports may include prompts, retrieved contexts, generated answers, memory content, and tool traces. Treat them as local artifacts by default.
- Sanitized reports should omit secrets, raw provider payloads, credentials, and private tool arguments.
- Full reports can be retained only under ignored artifact directories.
- If an eval corpus contains private local files, the manifest should record only dataset IDs and local path placeholders, not absolute personal paths.

### Release / Rollback

- First implementation should be opt-in and non-invasive.
- Legacy tests may be quarantined behind a single excluded `legacy-eval` tag only as a temporary safety step during implementation. They are not accepted v2 suites, v2 data sources, or baseline references.
- Rollback is simple if changes are limited to test/eval tools, docs, and POM tag exclusions.

### Phase 1 Scope Guard

Phase 1 is a cleanup-only slice. It must not change production behavior.

Allowed Phase 1 paths:

- `chatagent/bootstrap/pom.xml`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/**`
- `chatagent/bootstrap/src/test/resources/eval/**`
- `chatagent/bootstrap/target/eval-reports/**` only as local generated cleanup, never committed
- `.gitignore`
- `docs/**`

Forbidden Phase 1 paths unless a new plan is approved:

- `chatagent/bootstrap/src/main/**`
- non-eval tests outside `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/**`
- runtime resources outside `chatagent/bootstrap/src/test/resources/eval/**`
- `ui/**`
- `data/**`
- provider, memory, RAG, SSE, controller, service, repository, migration, or runtime configuration code

Phase 1 verification must include `git diff --name-only` and the implementer must stop if any path outside the allowed cleanup scope appears.

### CI Fail Policy

Phase 0 approves this CI policy:

- CI-failing gates: Phase 1 scope guard, eval tag/default-test pollution checks, schema validation, manifest validation with concrete license fields, deterministic metric unit tests, and no-live-provider smoke tests.
- Warn-only or manual gates: official Ragas LLM judge metrics, semantic memory judge metrics, live Milvus/Ollama/reranker suites, full corpus downloads, latency/load/chaos/reliability benchmarks, and any suite that requires paid provider credentials or GPU services.
- CI must not fail because an external corpus host or LLM Provider is temporarily unavailable. Those failures should become structured warnings unless the suite is explicitly run in a full/manual mode.

### Observability

New reports should include:

- Git SHA and branch.
- Run ID, suite name, mode, timestamp, host profile.
- Dataset name/version/hash.
- Model/provider identifiers, but no API keys.
- Complete numeric configuration summary, parameter classifications, config fingerprint, and exact trial values.
- Per-suite metrics and pass/fail status.
- Per-sample failure details.
- Latency percentiles and error counts.
- Token/cost metadata if available.

## Technical Design

### Recommended Architecture

Use a hybrid evaluation architecture:

1. Java/Spring export harness executes ChatAgent production interfaces and emits canonical JSONL samples.
2. Python `tools/eval` runner computes standard Ragas metrics and deterministic module metrics.
3. A shared report aggregator writes normalized JSON, JSONL, CSV, and Markdown summaries.

Why hybrid:

- ChatAgent runtime is Java/Spring; retrieval, memory, intent, and tool behavior should be captured through production interfaces.
- Ragas is Python-first. Keeping Ragas in Python avoids reimplementing or misnaming RAGAS.
- Deterministic retrieval metrics can be computed in either Java or Python, but one canonical report layer prevents drift.

Fallback architecture:

- If Python dependencies are rejected, keep a Java-only runner but call it `rag-quality` or `ragas-compatible`, not `ragas`, and document that it is not official Ragas.

### Precision Parameter Tuning And Promotion

The v2 system must support reproducible accuracy-first tuning instead of relying on one manually chosen configuration.

#### Numeric Configuration Registry

Every numeric setting discovered in runtime configuration, eval configuration, or suite code must appear in a versioned parameter registry with:

- Stable parameter ID and owning module.
- Runtime property path and environment-variable name when one exists.
- Type, default, valid range, discrete choices or step size, and dependency constraints.
- Classification: `quality-tunable`, `operational-only`, `safety-fixed`, or `excluded-with-rationale`.
- Suites and metrics affected.
- Search space version and promotion status.

Initial quality-tuning inventory:

| Area | Initial Parameters | Primary Quality Signal |
|---|---|---|
| Retrieval | `top-k`, `candidate-k`, `rrf-k`, reranker `max-candidates`, reranker `max-chunk-chars`, reranker `score-threshold` | Macro NDCG@K, Recall@K, MRR, phrase/span recall |
| Ingestion/text recall | parser selection thresholds, contextual `min-chunk-chars`, `max-context-chars`, document-enhancer keyword/question/length limits | Required phrase, table-cell, section, and chunk-span recall |
| Memory L1/L2 | L1 window turns/token budget, pending turn/token thresholds, warning ratio, segment/synopsis/tool-result character budgets, runtime max segments | Fact recall/F1, contradiction rate, complete-turn preservation |
| Memory L3 | recall `top-k`, extraction limits and promotion thresholds when exposed | Durable-memory extraction F1, Hit@K, MRR, user isolation |
| Agent modules | intent minimum score, query rewrite limits, tool/step/context budgets when exposed | Macro F1, routing accuracy, retrieval lift, tool-call F1 |
| Generation | candidate model temperature, top-p, max tokens, and response budgets when the suite owns the generated answer | Ragas quality metrics and deterministic task correctness |

Operational timeouts, pool sizes, retry counts, circuit-breaker values, and concurrency limits must still be registered, but they are tuned in reliability/performance suites rather than selected by the accuracy optimizer. Judge-model sampling parameters are frozen and versioned during candidate comparison; changing the judge to improve a candidate score is forbidden.

#### Experiment And Split Policy

- Real datasets must be split by source/document/conversation group, not random rows, to prevent near-duplicate leakage.
- Each suite owns train/calibration, development/tuning, sealed test/holdout, and optional challenge splits.
- Parameter search and threshold calibration may use only train/calibration and development/tuning splits.
- The sealed holdout is evaluated only for a short-listed candidate or final champion, never for iterative search.
- Synthetic fixtures and stress datasets cannot select or promote production quality parameters.
- Every trial records dataset hash, split hash, code SHA, parameter-space version, exact parameter values, models, random seed, metrics, latency, cost, and failures.

#### Search Strategy

1. Run a deterministic baseline with current defaults.
2. Run one-parameter sensitivity sweeps to identify influential values and invalid ranges.
3. Run coarse grid/random search over valid combinations with deterministic seeds.
4. Refine around the Pareto frontier using successive halving or an optional Bayesian/TPE optimizer adapter after dependency review.
5. Re-run top candidates across repeated seeds/runs when model or judge behavior is stochastic.
6. Evaluate the short-listed champion on the sealed holdout and challenge categories.

The initial implementation must work without a new optimizer dependency through deterministic grid/random search. An Optuna-style optimizer adapter may be added later, but the trial schema and promotion rules must not depend on one optimizer library.

#### Accuracy-First Champion Selection

Each suite defines one primary quality objective and explicit hard gates. There is no single opaque score that combines all modules.

- Select the candidate with the best development-split primary metric after all safety, correctness, category-regression, latency, and cost gates pass.
- Use secondary quality metrics first, then latency/cost, as tie-breakers.
- Require bootstrap confidence intervals and repeated-run variance in the comparison report.
- Reject a candidate that improves the overall metric by sacrificing a protected domain/category beyond the suite-owned regression tolerance.
- Threshold values are calibrated on calibration/development data and verified on holdout; they must not be tuned to make the same holdout pass.
- Promotion writes a reviewed recommended configuration artifact. It must not silently rewrite production `application.yaml` or runtime defaults.

#### Tuning Artifacts

Every tuning experiment emits:

- `experiment-manifest.json`
- `parameter-space.yaml`
- `trials.jsonl`
- `leaderboard.csv`
- `pareto-frontier.json`
- `champion-candidate.yaml`
- `holdout-verification.json`
- `promotion-decision.md`

### Proposed Repository Layout

```text
tools/eval/
  pyproject.toml
  README.md
  run_eval.py
  chatagent_eval/
    datasets.py
    deterministic_metrics.py
    ragas_runner.py
    reports.py
    thresholds.py
    schemas.py
    parameters.py
    tuning.py
    promotion.py
    optimizers/
      grid_random.py
    suites/
      rag_retrieval.py
      ragas_quality.py
      text_recall.py
      memory.py
      agent_modules.py

chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/
  EvalExportReportWriter.java
  EvalRunManifest.java
  EvalDatasetSchemaTest.java
  RagRetrievalExportEvalTest.java
  MemoryExportEvalTest.java
  TextRecallExportEvalTest.java

chatagent/bootstrap/src/test/resources/eval/v2/
  datasets/
    rag/
      beir-scifact-rag-v1.jsonl
      mtrag-human-rag-v1.jsonl
      rag-thresholds.yaml
    memory/
      memory-v2-dialogues.jsonl
      memory-v2-thresholds.yaml
    text-recall/
      parser-text-recall-v1.jsonl
  stress/
    synthetic/
      enterprise-rag-bench/
        enterprise-rag-stress-v1.jsonl
        enterprise-rag-stress-thresholds.yaml
        README.md
  corpora/
    manifests/
      beir-scifact.json
      mtrag-human.json
      sec-edgar-10k.json
      pmc-oa.json
  schemas/
    eval-sample.schema.json
    eval-report.schema.json
    eval-parameter-space.schema.json
    eval-trial.schema.json
  parameter-spaces/
    agent-modules-v1.json

tools/eval/config/
  parameter-spaces/
    rag-retrieval-v1.yaml
    text-recall-v1.yaml
    memory-v2-v1.yaml
    agent-modules-v1.yaml
  recommended/
    README.md
```

### Canonical RAG Sample Contract

Each RAG export sample should contain:

```json
{
  "id": "rag-sec-edgar-001",
  "suite": "ragas",
  "category": "factual",
  "domain": "public-filing",
  "user_input": "What revenue value is stated in the referenced SEC filing?",
  "reference": "Use the revenue value recorded in the corresponding SEC or XBRL source fact.",
  "reference_context_ids": ["sec-edgar-filing#chunk-001"],
  "reference_contexts": ["...gold context text..."],
  "retrieved_context_ids": ["sec-edgar-filing#chunk-001"],
  "retrieved_contexts": ["...retrieved chunk text..."],
  "retrieved_documents": [
    {
      "source_type": "KNOWLEDGE_BASE",
      "source_id": "eval-kb-...",
      "document_id": "sec-edgar-filing",
      "chunk_id": "sec-edgar-filing#chunk-001",
      "section_path": "Consolidated Statements",
      "rank": 1,
      "score": 0.82,
      "score_type": "reranker"
    }
  ],
  "response": "The answer should quote the value from the retrieved SEC filing context.",
  "rubric": {
    "must_contain": "reported value;filing context",
    "must_not_claim": "unverified estimate"
  }
}
```

This maps to Ragas `SingleTurnSample` while preserving ChatAgent-specific evidence IDs for deterministic metrics.

### RAGAS Metrics

Use official Ragas metrics for answer/context quality:

- Context Precision
- Context Recall
- Context Entities Recall where references provide entities
- Faithfulness
- Response Relevancy
- Answer Correctness or Factual Correctness where reference answers exist

Use deterministic metrics alongside Ragas:

- Hit@1/3/5
- Recall@K
- Precision@K
- MRR
- NDCG@K
- Exact context ID recall
- Expected phrase/span recall
- Source coverage for multi-document questions
- Latency P50/P95/P99
- Reranker fallback rate and low-confidence rate

### Text Recall Accuracy

Text recall should be separate from RAGAS. It answers: "Did the system retrieve or extract the exact evidence text we needed?"

Suites:

- Parser text recall: PDF/Markdown/Excel/image parser output vs expected phrases and forbidden noise.
- Chunk recall: chunker output preserves expected spans, table rows, headings, and page/section metadata.
- Retrieval text recall: retrieved topK contexts contain expected spans or normalized facts.
- Citation recall: final citations point to source chunks that contain supporting spans.

Metrics:

- Phrase recall: matched expected phrases / total expected phrases.
- Span coverage: expected normalized character spans covered by retrieved contexts.
- Table cell recall: required cells found in extracted markdown/table text.
- Must-not-contain precision: forbidden boilerplate/noise absent.
- Section accuracy: expected section/page/chunk location matches.
- TopK evidence coverage: every required evidence group represented in topK.

### Memory Evaluation

Memory should be split by responsibility.

L1 short-term memory loader:

- Public interface: `AgentMemoryLoader.load(sessionId, agentConfig)`.
- Metrics: recent-turn retention, complete turn preservation, tool call/response pairing, token budget compliance, oversized tool compaction, no orphan tool messages.
- Data: persisted chat messages generated from the approved memory dialogue dataset, with fixture-only synthetic variants for tool sequences and budget pressure.

L2 compaction:

- Public interfaces: `AsyncSummaryListener`, `IncrementalSummarizer`, `SummaryWatermarkService`, `ChatSessionSummaryRepository`, `ChatSessionSummarySegmentRepository`.
- Metrics: checkpoint fact recall, fact precision, entity recall, topic recall, contradiction rate, synopsis length, segment range coverage, structured JSON parse rate, deterministic fallback rate, retry/backoff outcomes.
- Data: `memory-v2-dialogues.jsonl` from the memory source policy above, with expected facts, entities, topics, contradictions, and segment boundaries.

L3 promotion:

- Public interface: `LongTermMemoryPromotionService.promote(sessionId, range, turns)`.
- Metrics: extracted memory recall/precision, type accuracy, tag F1, dedupe/content hash behavior, extraction log idempotency, index status.
- Data: source-labeled raw turns with expected durable memories and non-durable facts that must be ignored.

L3 recall:

- Public interface: `LongTermMemoryRecallService.recall(sessionId, query)` and `UserMemoryIndexService.search(userId, embedding, topK)`.
- Metrics: memory Hit@K, MRR, NDCG, user isolation, inactive memory exclusion, formatted prompt section correctness.
- Data: seeded memory items derived from the same source-labeled dialogue set, plus fixture-only active/inactive state edge cases.

### Retrieval Evaluation

Use production retrieval interfaces:

- Knowledge base retrieval: `KnowledgeBaseSimilaritySearcher.searchByKnowledgeBaseIds`.
- Candidate export: `searchCandidateHitsByKnowledgeBaseIds` for dense/BM25/RRF analysis.
- Session file retrieval: `SessionFileSimilaritySearcher.searchBySessionFileIds`.
- Scope merge: `SearchScopeResolver.searchBySession`.
- Prompt evidence formatting: `RetrievalHitFormatter`.

Suites:

- `rag-retrieval-smoke`: small sampled public corpus, deterministic, default local safe.
- `rag-retrieval-full`: real Milvus/Ollama/reranker, full corpus.
- `rag-reranker-ab`: BGE/LLM/noop reranker comparison.
- `rag-scope`: session file + scoped KB + fallback policy correctness.
- `rag-citation`: evidence formatting and citation metadata.

### Agent Module Evaluation

After RAG and memory:

- Intent routing: exact path, domain accuracy, ambiguity handling, out-of-scope, clarification.
- Query rewrite: anchor preservation, retrieval lift, no over-expansion.
- Tool use: tool call accuracy/F1, step budget, final answer containment, tool error handling.
- Multi-turn: coreference recall, topic switch, wrong-history suppression.
- Response quality: Ragas plus deterministic containment on exported perfect-context samples.

### Report Schema

Every run writes:

```text
artifacts/eval/<run-id>/
  manifest.json
  samples.jsonl
  metrics.json
  report.md
  failures.jsonl
  ragas-results.jsonl
```

`manifest.json` should include:

- `runId`
- `suite`
- `mode`
- `timestamp`
- `gitBranch`
- `gitSha`
- `datasetId`
- `datasetHash`
- `config`
- `configFingerprint`
- `parameterSpaceId`
- `experimentId`
- `trialId`
- `randomSeed`
- `models`
- `thresholds`
- `artifactFiles`

Tuning-specific manifest fields (`parameterSpaceId`, `experimentId`, `trialId`, and `randomSeed`) are optional. They must be null or absent for non-tuning smoke, full, baseline, or single-suite runs. Non-null values indicate that the run is part of a parameter-search experiment and must reference its reproducible tuning artifacts.

`metrics.json` should include:

- `status`: `pass`, `warn`, or `fail`
- `overall`
- `byCategory`
- `byDomain`
- `bySourceType`
- `latency`
- `cost`
- `thresholdResults`
- `regressionAgainstBaseline`
- `parameterSensitivity`
- `confidenceIntervals`
- `promotionDecision`

### Threshold Policy

Use suite-owned threshold YAML:

```yaml
suite: rag-retrieval
mode: smoke
thresholds:
  hit_at_3:
    min: 0.85
    severity: fail
  mrr:
    min: 0.70
    severity: fail
  latency_p95_ms:
    max: 3000
    severity: warn
```

Rules:

- Deterministic metrics can fail CI.
- LLM/Ragas judge metrics should start as warn-only until variance is measured.
- Full infra suites should compare against baseline and report deltas.
- Per-category regressions should be visible even when overall passes.
- Thresholds and parameter values must be calibrated on train/development data and verified on sealed holdout data.
- A threshold file must record the dataset/split hash and calibration procedure that produced it.

## Old System Cleanup Plan

### Cleanup Phase C0: Freeze And Inventory

Goal: capture useful old findings before deleting or rewriting code.

Actions:

- Keep this plan and implementation record as the new source of truth.
- Record old results from `docs/summary/12-eval-system.md` in a short legacy archive section or file.
- Keep at most one representative legacy report per suite if needed for comparison.
- Do not commit `target/eval-reports` generated files.

Acceptance:

- Old suite inventory exists.
- Legacy result references are clearly marked historical.
- No generated reports are treated as source of truth.

### Cleanup Phase C1: Stop Default Test Pollution

Goal: make all eval/benchmark tests opt-in.

Actions:

- Update `chatagent/bootstrap/pom.xml` to exclude every current eval tag or move legacy tests under one excluded tag such as `legacy-eval`.
- Add a test or script that fails if an `@Tag("eval-*")` in the eval package is not excluded by default.
- Ensure `EvalReportWriter` cannot write during default Maven tests.

Acceptance:

- `mvn -pl chatagent/bootstrap test` or project equivalent does not run eval tests.
- No new files appear under `chatagent/bootstrap/target/eval-reports` during default tests.

### Cleanup Phase C2: Retire Risky Legacy RAG Tests

Goal: remove unsafe/destructive behavior before implementing the replacement.

Actions:

- Disable or tag legacy `RetrievalQualityIntegrationEvalTest`, `RerankerAbEvalTest`, and `LatencyBaselineEvalTest` as `legacy-eval`.
- Remove hardcoded absolute file paths from source code.
- Replace "delete all knowledge bases" setup with eval-owned cleanup if any legacy test must remain runnable temporarily.

Acceptance:

- No eval test deletes non-eval knowledge bases.
- No source file contains personal absolute corpus paths.

### Cleanup Phase C3: Clean-Room Datasets

Goal: rebuild datasets from approved sources instead of migrating legacy golden data.

Actions:

- Build primary `eval/v2/datasets/rag/*.jsonl` only from approved public sources.
- Build `eval/v2/datasets/memory/memory-v2-dialogues.jsonl` only from IBM MTRAG, approved public conversation datasets, or human annotation over approved real public documents.
- Rebuild multiturn, intent, and tool datasets from approved real/public or human-reviewed sources instead of converting `multiturn-golden.json`, `intent-golden.json`, or `tool-golden.json`.
- Delete or quarantine legacy `rag-golden.json`, `memory-golden.json`, and other old golden files after their replacement suites exist; use them only to audit coverage categories, not as sample content.
- Delete inline RAG lecture queries unless the private course corpus is explicitly approved as local-only optional material outside headline metrics.
- If synthetic stress coverage is still needed later, import or generate it fresh under `eval/v2/stress/synthetic/**` with explicit manifests and labels; do not copy old synthetic enterprise samples forward.
- Add schema validation tests.

Acceptance:

- Every suite reads from dataset files, not inline Java query lists.
- Each dataset has a schema, version, and threshold file.
- No v2 dataset or threshold file is derived from legacy golden sample content.

### Cleanup Phase C4: Replace Report Outputs

Goal: stop fragmented report writing.

Actions:

- Replace `EvalReportWriter` with v2 report writer.
- Move outputs from `target/eval-reports` to configurable `CHATAGENT_EVAL_OUTPUT_DIR`, defaulting to `artifacts/eval/<run-id>`.
- Add gitignore for `artifacts/eval/` if not already ignored.
- Write `manifest.json`, `metrics.json`, `samples.jsonl`, `failures.jsonl`, and `report.md`.

Acceptance:

- All v2 suites emit the same report layout.
- Reports include run metadata, thresholds, and per-sample failure details.

### Cleanup Phase C5: Delete Or Move Legacy Code

Goal: remove duplicate old frameworks once replacements exist.

Actions:

- Delete or archive replaced legacy tests under `com.yulong.chatagent.eval`.
- Keep `EvalMetricsComputationTest` only if it tests the new deterministic metrics.
- Move reliability tests to a separate benchmark package and tag family.
- Replace `docs/summary/12-eval-system.md` with a concise pointer to the new plan and current run commands.

Acceptance:

- No old RAGAS-like test remains under a misleading name.
- `docs/summary/12-eval-system.md` no longer claims obsolete historical results as current truth.

## Implementation Phases

### Phase 0: Review And Decisions

Goal: approve architecture choices before code changes.

Deliverables:

- User confirmation on Python Ragas vs Java-only compatibility mode.
- User confirmation on corpus strategy.
- User confirmation on judge/model provider policy.

Acceptance criteria:

- Phase 0 decisions at the end of this plan are recorded.
- Implementation can proceed one vertical slice at a time.

### Phase 1: Legacy Evaluation Cleanup

Goal: remove or quarantine old evaluation files/code first, while proving no unrelated business code changed.

Priority order:

1. Inventory every legacy eval class, golden resource, generated report location, and eval tag.
2. Delete obsolete RAGAS/RAG/memory/module eval code and old golden resources that are being fully replaced.
3. Move only intentionally retained reliability/benchmark tests out of the RAGAS/eval quality path and into benchmark/reliability naming and tags.
4. Update Maven tag exclusions only to keep default tests clean during and after cleanup.
5. Verify the diff is limited to the Phase 1 cleanup allowlist.

Likely files:

- `chatagent/bootstrap/pom.xml`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/*`
- `chatagent/bootstrap/src/test/resources/eval/*`
- `.gitignore`
- `docs/summary/12-eval-system.md`

Deliverables:

- Legacy eval inventory with delete/quarantine/benchmark-retain decision per file.
- Old RAGAS-like, RAG retrieval, memory summary, response quality, query rewrite, intent, multiturn, tool, and parser-quality eval files deleted or quarantined when they are not intentionally retained benchmarks.
- Old golden resources deleted or quarantined outside v2 and marked historical; none are migrated into v2.
- Complete eval tag exclusion for anything temporarily quarantined.
- Generated report cleanup command documented.
- A Phase 1 scope proof showing `git diff --name-only` contains only eval/test-resource/build-doc cleanup paths.

Tests / verification:

- Tag exclusion audit command.
- Default Maven test dry run or targeted verification that eval tags are excluded.
- `git diff --name-only` reviewed against the Phase 1 Scope Guard allowlist.
- `rg -n "RetrievalQuality|ResponseQuality|MemorySummary|rag-golden|memory-golden|eval-reports" chatagent/bootstrap/src/test chatagent/bootstrap/pom.xml` to prove stale active eval references are gone or explicitly quarantined.

Acceptance criteria:

- Phase 1 performs cleanup before any new v2 framework implementation.
- No file under `chatagent/bootstrap/src/main/**`, `ui/**`, `data/**`, or non-eval test packages changes.
- Default test execution does not run legacy eval suites or write eval reports.
- Old eval datasets and reports are not treated as source of truth.

### Phase 2: V2 Core Contracts

Goal: introduce shared dataset, metrics, threshold, and report contracts.

Likely files:

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/*`
- `chatagent/bootstrap/src/test/resources/eval/v2/schemas/*`
- `tools/eval/chatagent_eval/schemas.py`
- `tools/eval/chatagent_eval/reports.py`

Deliverables:

- JSON schema for samples and reports.
- JSON schema for parameter spaces and trials.
- Java export report writer.
- Deterministic metric library.
- Config fingerprinting, experiment manifests, and deterministic grid/random search contracts.
- Dataset schema validation tests.

Tests / verification:

- Unit tests for Hit@K, Recall@K, Precision@K, MRR, NDCG, phrase recall, threshold evaluation, parameter validation, config fingerprinting, and champion-selection rules.
- Schema validation against sample fixtures.

### Phase 3: Versioned Corpora And Datasets

Goal: create clean-room v2 datasets backed by approved real public corpora.

Likely files:

- `chatagent/bootstrap/src/test/resources/eval/v2/datasets/**`
- `chatagent/bootstrap/src/test/resources/eval/v2/corpora/**`
- `tools/eval/chatagent_eval/downloaders/**`

Deliverables:

- Downloaders/manifests for approved public sources.
- RAG v1 datasets built first from BEIR SciFact, IBM MTRAG human tasks, and SEC EDGAR.
- Memory v2 dialogue dataset seeded from IBM MTRAG, approved public conversation datasets, or human annotation over approved real public documents, plus separately labeled fixture-only synthetic edge cases.
- Text recall parser/chunker dataset built from real PDFs/HTML/Markdown/table-heavy documents.
- Threshold YAML files.
- Source-grouped train/calibration, development/tuning, sealed holdout, and challenge split manifests.

Tests / verification:

- Dataset schema validation.
- Corpus manifest validation, including concrete non-placeholder license strings and license/terms URLs.
- No source references to private absolute corpus paths.
- Dataset size gates prove full suites have the minimum query/document/file counts.
- Split-leakage tests prove the same source/document/conversation group cannot appear in tuning and sealed holdout splits.

### Phase 4: RAG Retrieval Export Suite

Goal: run production retrieval and export samples.

Likely files:

- `RagRetrievalExportEvalTest.java`
- `EvalOwnedKnowledgeBaseFixture.java`
- `EvalRunManifest.java`
- `KnowledgeBaseSimilaritySearcher` tests only if public contracts need hardening.

Deliverables:

- Eval-owned KB/document setup.
- Retrieval sample export with retrieved IDs, contexts, scores, sources, and latencies.
- Deterministic retrieval metrics.
- Trial-scoped runtime parameter overrides and exact config capture for retrieval tuning.

Acceptance criteria:

- Smoke suite runs with a small corpus and writes v2 artifacts.
- Cleanup deletes only eval-owned resources.
- Metrics include Hit@K, Recall@K, MRR, NDCG, phrase recall, source coverage, and latency.
- A baseline and one-parameter sensitivity sweep can run without editing production defaults.

### Phase 5: Official RAGAS Runner

Goal: compute standard Ragas metrics from exported samples.

Likely files:

- `tools/eval/pyproject.toml`
- `tools/eval/run_eval.py`
- `tools/eval/chatagent_eval/ragas_runner.py`
- `tools/eval/chatagent_eval/reports.py`

Deliverables:

- Python runner loading exported JSONL as Ragas samples.
- Configurable Ragas LLM and embeddings.
- Ragas metrics merged into v2 report.

Acceptance criteria:

- `ragas-smoke` can evaluate a small sample set.
- Ragas failures become row-level `nan` or structured failures according to config.
- Report distinguishes deterministic retrieval metrics from Ragas LLM judge metrics.

### Phase 6: Text Recall Suite

Goal: measure extraction/chunk/retrieval evidence recall directly.

Likely files:

- `TextRecallExportEvalTest.java`
- Parser/chunker fixtures under `eval/v2/corpora/text-recall`
- Python deterministic text recall metrics.

Deliverables:

- Parser text recall.
- Chunk span recall.
- Retrieval context phrase/span recall.
- Citation support recall.

Acceptance criteria:

- Required phrases and table cells are measured separately from answer quality.
- Failure report shows missing phrases and the actual topK contexts.
- Parser/chunker/context parameters can be swept through versioned parameter spaces.

### Phase 7: Memory Suite

Goal: evaluate L1/L2/L3 memory with V2 semantics.

Likely files:

- `MemoryExportEvalTest.java`
- `memory-v2-dialogues.jsonl`
- `tools/eval/chatagent_eval/suites/memory.py`

Deliverables:

- L1 loader test/eval cases.
- L2 compaction quality export.
- L3 promotion export.
- L3 recall export.

Acceptance criteria:

- L1 preserves complete turns/tool responses within budget.
- L2 metrics include fact recall/precision, contradiction rate, segment range coverage, and fallback/retry stats.
- L3 metrics include durable memory extraction F1, type/tag accuracy, idempotency, and recall Hit@K/MRR.
- The offline runner processes one sample at a time and records no cross-user memory state. Live `UserMemoryIndexService` user isolation and inactive memory exclusion are deferred to a later live-memory integration suite with fixture-only active/inactive edge cases.
- Memory budget, compaction, and recall parameters can be tuned without changing the sealed holdout.

### Phase 8: Agent Module Suites

Goal: rebuild query rewrite, intent, multiturn, and tool evals in v2.

Likely files:

- `AgentModuleExportEvalTest.java`
- `agent-modules/*.jsonl`
- Python module metric runners.

Deliverables:

- Intent routing metrics.
- Query rewrite anchor and retrieval lift metrics.
- Tool call accuracy/F1 metrics.
- Multi-turn coreference/topic-switch metrics.

Acceptance criteria:

- Legacy module eval reports are replaced by v2 reports.
- V2 module datasets are clean-room datasets, not converted legacy golden JSON.
- Tool/multiturn Ragas agent metrics are optional and clearly labeled.
- Agent-module numeric parameters can participate in suite-owned parameter spaces.

### Phase 9: Precision Parameter Tuning And Promotion

Goal: systematically tune every registered quality-affecting numeric value and produce reviewed recommended configurations.

Likely files:

- `tools/eval/chatagent_eval/parameters.py`
- `tools/eval/chatagent_eval/tuning.py`
- `tools/eval/chatagent_eval/promotion.py`
- `tools/eval/chatagent_eval/optimizers/grid_random.py`
- `tools/eval/config/parameter-spaces/**`
- `tools/eval/config/recommended/**`

Deliverables:

- Complete numeric configuration registry with classification and rationale.
- Reproducible baseline, sensitivity sweep, combination search, leaderboard, and Pareto-frontier reports.
- Suite-owned primary objectives, hard gates, regression tolerances, and tie-break rules.
- Sealed-holdout verification and reviewed champion-candidate artifacts.
- Promotion workflow that proposes runtime defaults without silently changing them.

Acceptance criteria:

- Every discovered numeric setting is registered or explicitly excluded with rationale.
- `top-k`, `candidate-k`, `rrf-k`, reranker thresholds/candidate limits, text-recall parameters, and memory L1/L2/L3 parameters have reproducible search results.
- Tuning runs use only approved real datasets and tuning splits.
- Sealed holdout hashes do not appear in iterative trial inputs.
- Champion selection is reproducible from trial artifacts and includes confidence intervals, category regressions, latency, and cost gates.
- No production runtime default changes without a separate reviewed promotion change.

Tests / verification:

- Parameter-space schema and constraint tests.
- Deterministic trial ordering/fingerprint tests.
- Split leakage and sealed-holdout access tests.
- Champion-selection, tie-break, regression-gate, and promotion-artifact tests.
- Re-run the chosen champion from its manifest and reproduce metrics within the declared tolerance.

### Phase 9 Cross-Review Finding: Deterministic Proxies Do Not Measure Real Quality

Phase 9 cross-review identified that all suite runners from Phase 4–8 use **deterministic proxies** instead of real models for the "model under test" layer:

1. **RAG Retrieval (Phase 4)**: `EvalOwnedKnowledgeBaseFixture` uses hash-based embeddings (token hashCode mapped to vector bins), not the production bge-m3 embedding model via Ollama. The retrieval code path (`KnowledgeBaseSimilaritySearcher`) is real, but the embeddings it searches against are synthetic.

2. **Memory L2/L3 (Phase 7)**: `_extract_l3_memories` and `_expected_l3_memories` call the same `_memory_candidates` function with identical arguments, so L3 extraction F1 is always 1.0 and never fails. L2 uses text concatenation (`_l2_synopsis` joins segment turn texts) instead of LLM-generated compaction summaries, so L2 fact recall is also always 1.0.

3. **Agent Modules (Phase 8)**: Intent, rewrite, tool call, and coreference metrics are derived deterministically from MTRAG metadata labels (answerability, questionType, multiTurn, domain), not from real model outputs. Intent accuracy tests whether label-to-path mapping is correct, not whether a real classifier predicts the right label.

The metrics computation (Hit@K, phrase recall, F1, etc.), data pipeline (splits, manifests, SHA-256), sealed-holdout mechanism, and tuning framework are all real, tested, and correct. The gap is specifically in the layer between real data and real metrics — the deterministic proxy.

**Impact**: Phase 9 tuning results (baseline champion, zero-overlap holdout) prove the tuning infrastructure works, but the metrics being tuned do not yet reflect real model quality. The baseline-is-optimal result is an artifact of deterministic proxies that produce 1.0 at default parameters, not evidence that production defaults are genuinely optimal.

**Resolution**: The deterministic diagnostics remain in place as regression tests and parameter-sensitivity checks (free, fast, no infrastructure needed). Insert Phase 10 to upgrade each module from deterministic proxy to real model, then re-run tuning on real metrics.

### Phase 10: Real Model Evaluation Upgrade

Goal: replace deterministic proxies with real model connections in production document ingestion/retrieval, memory L2/L3, and agent module evaluations, producing genuinely representative quality measurements for ChatAgent's actual workflows.

Likely files:

- `ProductionDocumentIngestionEvalTest.java` (new Java export runner)
- `application-eval-real-doc-ingestion.yaml` (new Spring profile)
- `tools/eval/chatagent_eval/doc_ingestion_runner.py` (new file, reusing dataset-root reading pattern from text_recall_runner.py)
- `MemoryExportEvalTest.java` (new Java export runner)
- `tools/eval/chatagent_eval/memory_runner.py` (updated to consume Java exports)
- `AgentModuleExportEvalTest.java` (new Java export runner)
- `tools/eval/chatagent_eval/agent_module_runner.py` (updated to consume Java exports)

Deliverables:

- Production document-ingestion runner that downloads/uses real multi-format files, enables the local MinerU service from `tools/mineru` for PDF/visual extraction, runs ChatAgent's production outbox/RabbitMQ ingestion path into Milvus, and measures chunk recall, citation support, and text recall.
- Memory L2/L3 Java export runner exercising real `UserMemoryIndexService` + LLM compaction, with genuine fact recall and extraction quality.
- Agent module Java export runner capturing real intent classifier, query rewriter, and tool selection outputs (coreference is measured through rewrite quality, not as a separate module).
- Updated tuning results on real metrics for doc-ingestion-retrieval, memory-v2, and agent-modules (replacing the trivially-perfect baseline results).
- Large/full real-run exports and tuning runs for all Phase 10 suites. Smoke runs are preflight checks only and do not satisfy Phase 10 final acceptance.
- The old SciFact real-embedding benchmark is retired from Phase 10 acceptance because normalized JSONL benchmark rows do not exercise ChatAgent's production multi-format document ingestion path. Any existing 10a SciFact code/artifacts must be removed, quarantined as optional benchmark-only work, or explicitly excluded from acceptance.

Acceptance criteria:

- Phase 10 final acceptance requires real-model exports and tuning runs for 10a production document-ingestion retrieval, 10b memory-v2, and 10c agent-modules. Each sub-phase is accepted independently: 10b and 10c are not blocked by 10a completion. A smoke run may prove infrastructure and artifact wiring, but cannot close a sub-phase as quality-accepted.
- Production document-ingestion artifacts must prove that real files were parsed by production parsers, chunked by production chunkers, embedded, inserted into Milvus, and retrieved through production search. Metrics must include parser phrase recall, chunk span recall, retrieval context recall, citation support recall, table/numeric recall where applicable, and per-format breakdowns.
- 10a artifacts must prove the accepted run used network-downloaded real documents, the local MinerU API where PDF/visual routing applies, and the production outbox/RabbitMQ ingestion path. Direct `ingestSync` or Python-only parsing is allowed only for non-acceptance preflight fixtures.
- Memory L2/L3 real-model run artifacts must include provider/model metadata and raw model outputs alongside computed metrics. For smoke runs, provenance confirmation suffices; non-perfect metrics are logged as observations. For full/tuning runs, if all samples score 1.0 on L2 fact recall or L3 F1, it is a finding indicating the measurement lacks discriminative power.
- Agent module real-model run artifacts must include provider/model metadata and raw model outputs (intent routing result, rewritten query, available tool list). For smoke runs, provenance confirmation suffices; non-perfect metrics are logged as observations. For full/tuning runs, if all samples score perfectly, it is a finding indicating the measurement lacks discriminative power.
- Each upgraded suite gracefully skips with a clear warn message when its required infrastructure (Ollama, Milvus, RabbitMQ, MinerU, etc.) is not available, and the deterministic diagnostic mode remains available as a regression test.
- Deterministic proxy modes are preserved and continue passing as CI regression tests.
- Tuning re-run on real metrics produces non-trivial champion selection (the champion may differ from baseline, or the baseline may be confirmed with genuine evidence rather than proxy artifacts).
- No production runtime default changes; promotion artifacts still require separate reviewed change.

Full-run sample requirements:

- **10a production document-ingestion export**: use at least 200 real documents downloaded from public network sources across multiple formats: SEC EDGAR filing HTML/XBRL, real PDFs, DOCX/Word files, XLSX/spreadsheet workbooks, and standalone web/HTML or Markdown pages. CSV files may remain optional preflight/diagnostic samples, but they do not count toward the headline accepted-size format quota unless a dedicated CSV table-aware parser is implemented. Hand-made, generated, toy, or private local documents are not allowed in headline 10a metrics. The accepted run must include at least five format families, at least 500 grounded recall items/queries total, per-format metrics, and source URL/hash/license or terms metadata for every file. A smaller 5/100/500-document smoke may remain a preflight only.
- **10b memory-v2 full export**: use the full Phase 3 MTRAG human dialogue dataset, currently 842 dialogue rows, preserving calibration, development, sealed holdout, and challenge split membership. The 10-row balanced smoke remains only a preflight.
- **10c agent-modules full export**: use the full Phase 3 MTRAG human dialogue dataset, currently 842 dialogue rows, preserving calibration, development, sealed holdout, and challenge split membership. The 10-row balanced smoke remains only a preflight.
- Full tuning uses all calibration/development rows for parameter search and all sealed holdout rows for final candidate verification. Challenge rows, when present, are run as a non-selection stress report and must not influence champion selection.
- Full tuning must use the suite's real parameter space rather than a smoke-only `--combination-budget 2` shortcut. Any reduced budget must be explicitly recorded as a non-acceptance exploratory run.

#### Cross-Cutting: Real-Model Test Activation And Infrastructure Detection

All Phase 10 sub-phases share a consistent infrastructure activation strategy:

- **Maven profile `eval-real`**: activates real-model eval tests. The current default (`surefire.excludedGroups` in `pom.xml`) excludes all `eval-v2` tagged tests, so neither deterministic nor real-model eval tests run in a normal build. To run deterministic eval tests, use the existing opt-in mechanism (e.g., override `surefire.excludedGroups` to remove `eval-v2`). To run real-model eval tests, activate the `eval-real` profile, which removes `eval-v2` and `eval-real` from the exclusion list so both deterministic and real-model tests execute (real-model tests then use infrastructure probes to skip if services are unavailable). Real-model test classes carry both `@Tag("eval-v2")` and `@Tag("eval-real")`.
- **Infrastructure probe at startup**: each real-model test class uses a JUnit 5 `ExecutionCondition` to probe its required services before the Spring context loads. Probes are lightweight health checks (e.g., Ollama `/api/tags`, Milvus TCP/collection checks, Redis TCP, PostgreSQL TCP, RabbitMQ TCP/AMQP availability, MinerU `/health`, LLM provider chat completion with a minimal prompt). When a probe fails, the test class is disabled with a diagnostic message listing which service was unreachable.
- **Consistent skip behavior**: skipped tests report as JUnit-disabled/skipped test classes (for example, `Tests run: N, Skipped: N`), not a pass or failure. The Maven build continues green.
- **No fallback to deterministic mode within a real-model test**: if infrastructure is unavailable, the real-model test skips entirely. The deterministic mode lives in separate test classes that never require infrastructure.
- **Deterministic tests unchanged**: all existing `eval-v2` tagged deterministic tests remain opt-in (excluded from default build, runnable when `eval-v2` is removed from `surefire.excludedGroups`), with no infrastructure requirements.

#### Phase 10a: Production Document Ingestion Real Retrieval

Goal: evaluate ChatAgent's production document ingestion and retrieval path on real multi-format files, instead of relying on normalized one-row benchmark documents. This validates the scenario the application actually serves: file download/upload, parser selection, parsing, structure-aware chunking, embedding, Milvus insertion, retrieval, and citation/source grounding.

Likely files:

- `ProductionDocumentIngestionEvalTest.java` (new) — Java export runner for production ingestion and retrieval.
- `ProductionDocumentIngestionInfrastructureCondition.java` (new) — JUnit 5 `ExecutionCondition` probing PostgreSQL, RabbitMQ, Ollama bge-m3, Milvus, and MinerU before Spring context loading.
- `application-eval-real-doc-ingestion.yaml` (new) — Spring profile enabling production parser/chunker/embedding/Milvus/MQ settings with eval-owned collections and storage roots.
- `tools/eval/chatagent_eval/doc_ingestion_runner.py` (new file) — compute production-ingestion retrieval metrics from Java exports. Reuses the dataset root reading pattern, report/artifact writing, and tune-suite bridge conventions from `text_recall_runner.py` and `memory_runner.py`. Introduces new metric families (parser phrase recall, chunk span recall, citation support, per-format breakdowns) that differ from text-recall phrase recall, so extending `text_recall_runner.py` would create unnecessary coupling.
- `tools/eval/tests/test_doc_ingestion_runner.py` — schema, metric, and tuning fixture tests.
- `chatagent/bootstrap/src/test/resources/eval/v2/schemas/eval-doc-ingestion-dataset-record.schema.json` (new).
- `chatagent/bootstrap/src/test/resources/eval/v2/parameter-spaces/doc-ingestion-retrieval-v1.json` and `tuning-policies/doc-ingestion-retrieval-v1.json` (new).

Retired 10a SciFact work:

- The prior 10a SciFact real-embedding benchmark is not part of Phase 10 acceptance. Per the plan-review decision (2026-06-08), the code is **quarantined via `@Tag("eval-benchmark")` + POM surefire `excludedGroups` in both default and `eval-real` profiles**: `RagRetrievalRealEmbeddingEvalTest` remains in `eval/v2/retrieval/` but is excluded from all standard test runs, `rag_retrieval_runner.py` stays in `chatagent_eval/` but is not a required Phase 10 deliverable, and `rag-retrieval` tuning dispatch remains in `tuning_runner.py` but is marked non-gating.
- `rag-retrieval-v1.json` and related tuning policy/parameter-space files are also quarantined — they must not be treated as required Phase 10 deliverables unless explicitly reintroduced as an optional benchmark in a later plan.
- No final quality claim should cite SciFact JSONL real-embedding results as evidence for production document ingestion quality.

Production path under test:

1. Use real files downloaded from public network sources into the ignored eval artifact cache with source URL, license/terms, SHA-256, retrieval timestamp, MIME type, and file size recorded in the source manifest.
2. Start or require the local MinerU API from `tools/mineru` and route PDF/visual-track documents through the production VDP/MinerU path (`MinerUVdpEngine`, `PdfVdpDispatcher`, and related VDP cache/routing components) when the parser selects that route. MinerU unavailability disables the real 10a class rather than silently falling back for accepted runs.
3. Route each file through the same production parser selection and parsing components used by ChatAgent uploads/knowledge ingestion, including `DocumentParserSelector`, `PdfDocumentParser`, `WordDocumentParser`, `SpreadsheetDocumentParser`, `TikaDocumentParser`, and `MarkdownDocumentParser`.
4. Chunk via the production chunking path (`SegmentAwareChunkerRouter`, `StructureAwareMarkdownChunker`, `TableAwareChunker`, or the active production chunker for the parsed segment type).
5. For accepted 10a runs, create eval-owned knowledge-base documents through the production facade/outbox path, publish `KnowledgeIngestTaskPayload`, let `OutboxPollingPublisher` and `KnowledgeIngestTaskListener` drive `KnowledgeDocumentIngestionServiceImpl.ingestSync()`, then wait for document status/chunks/Milvus vectors. Direct `KnowledgeDocumentIngestionServiceImpl.ingestSync()` calls are allowed only for preflight or focused debugging and must be labeled non-acceptance.
6. Embed with real Ollama bge-m3 and insert into an eval-owned Milvus collection or eval-owned knowledge base/session IDs.
7. Retrieve through production search (`KnowledgeBaseSimilaritySearcher` or `SessionFileSimilaritySearcher`, matching the ingestion path), capturing topK contexts, candidate contexts, rank signals, citation metadata, and source/chunk IDs.

Real source mix (first batch: 200+ public-network real documents):

| Format | Count | Source | Details |
|--------|-------|--------|---------|
| SEC HTML/XBRL | 50 | SEC EDGAR APIs | Filings (10-K, 10-Q, 8-K), company facts, submissions API. No API key required. XBRL numeric tables for financial facts. |
| PDF | 60 | PMC Open Access Subset | Research papers under CC BY / CC0 / other permissive licenses. PMC OA Subset, PMC FTP. |
| DOCX/Word | 30 | Government/institutional public DOCX | CMS Part D Model Materials, record layouts, policy/template documents. |
| XLSX/Spreadsheet | 40 | Data.gov / CMS / public agency workbooks | Agency data resources filtered to real `.xlsx` workbooks so the production `SpreadsheetDocumentParser` and `TableAwareChunker` are exercised. CSV may be retained only as optional non-gating diagnostics until a CSV table-aware parser exists. |
| Web/HTML/Markdown | 20 | Federal Register / govinfo / project documentation | Official web page or Markdown documents with headings, lists, tables, links, and citations. National Archives Federal Register and public documentation repositories. |

Total: ~200 files across five format families.

No generated toy files, hand-written local files, and no old private course PDFs are allowed in headline 10a metrics.

Every file must record in the source manifest:

- Original source URL
- Download timestamp
- SHA-256 hash
- MIME type and file size in bytes
- License or terms of use: **per-file**, not per-source. PMC licenses vary per article; Data.gov is a catalog — record the actual agency file URL and terms, not "Data.gov license." SEC EDGAR must record the specific filing accession number and CIK.
- Source group (e.g., `sec-edgar`, `pmc-oa`, `cms-docx`, `data-gov-xlsx`, `federal-register-html`)
- File format
- Parser path (MinerU, `PdfDocumentParser`, `WordDocumentParser`, `SpreadsheetDocumentParser`, `MarkdownDocumentParser`, `TikaDocumentParser`)

Source download is the first step of 10a. Downloaders must record the items above into a source manifest before ingestion begins. The Phase 3 downloader patterns (SEC company facts, SciFact) may be reused or extended.

Deliverables:

- A Phase 3-compatible dataset root under `datasets/doc-ingestion/<id>.jsonl` with one row per grounded recall query/item. Rows include `sampleId`, `datasetId`, `sourceGroupId`, `split`, `fileId`, `fileFormat`, `sourceUrl`, `userInput`, `requiredPhrases`, optional `requiredTableCells`, `referenceChunkIds`, `retrievedContexts`, `metadata.parser`, `metadata.chunker`, `metadata.ingestion`, `metadata.candidateContexts`, `metadata.mineru` (required when the parser selected the MinerU/VDP route for a PDF/visual document), and `metadata.mq` (required for accepted full-chain ingestion rows — records outbox event IDs, consumer completion status, parse status, chunk counts, and Milvus insertion evidence; absent for direct-service-call preflight rows).
- Run artifacts include parser output snapshots, chunk counts, chunk metadata coverage, Milvus collection/knowledge-base IDs, embedding model metadata, retrieval rank signals, and per-format metrics.
- Run artifacts include MinerU service/version/probe metadata for PDF/visual documents and MQ/outbox/consumer provenance for accepted full-chain ingestion rows.
- Metrics include parser phrase recall, chunk span recall, retrieval context recall, citation support recall, table-cell/numeric recall where applicable, source/chunk metadata completeness, and per-format failure rows.
- Tuning support for document-ingestion retrieval parameters, including chunk size/overlap or segment chunker settings where configurable, topK, candidateK, RRF/reranker settings where available, and citation context size.

Query/Answer generation methodology:

Queries and reference answers are not written from scratch against a static document corpus. Instead, they are derived from real document evidence through a reproducible extraction → rewrite → verify pipeline:

1. **Ingest documents**: real files → parser → chunker → embedder → Milvus (the production path under test).

2. **Extract evidence from real documents**: from each ingested file, extract concrete spans:
   - A sentence or paragraph from the body text
   - A heading and its associated paragraph
   - A table cell or row (for SEC financials, XLSX spreadsheet cells/rows, HTML tables)
   - A numeric fact with units (e.g., SEC revenue/net income figures)
   - A methods/conclusion sentence (for PDF research papers)
   - Evidence extraction is deterministic where the file format allows (SEC/XBRL tag-to-value mapping, XLSX sheet/range/cell reference, HTML table position, structured paragraph index); for free-text PDFs, a section/paragraph index is used.

3. **Generate queries**: queries are derived from evidence, not invented:
   - **Template-based**: appropriate for SEC/table/numeric evidence (e.g., "What was {company} revenue for fiscal {year}?"). Templates are populated from XBRL tag names, table headers, and numeric cell context.
   - **LLM rewrite**: the evidence is rewritten into a natural human question by an LLM. The LLM may only rephrase — it must not decide the answer or change the evidence's semantic scope. A verifier MUST confirm the answer derived from the rewritten question still matches the original evidence text.
   - Every query row records which generation method was used and, for LLM-rewritten queries, the original evidence text and the verifier pass/fail status.

4. **Reference answer from original text**: the reference answer is always the original evidence text (or a normalized version of it for numeric facts, e.g., "$383.3 billion" → "383300000000"). The answer is not model-generated and must be verifiable against the source document.

5. **Establish referenceChunkIds**: after the document is chunked by the production chunker, each evidence span is mapped to the chunk(s) that contain it. These chunk IDs become `referenceChunkIds` — the ground-truth retrieval targets for scoring.

6. **Run retrieval and compute hit rates**: each query is sent through production search. Retrieved contexts are compared against `referenceChunkIds` and `requiredPhrases` to compute per-query metrics.

Scored metrics (10a retrieval focus — answer-level metrics deferred to later RAGAS answer phase):

- **hit@K**: whether any `referenceChunkId` appears in the top-K retrieved chunks
- **contextRecall@K**: fraction of `referenceChunkIds` that are present in the top-K retrieved contexts
- **phraseRecall**: whether all `requiredPhrases` appear in the concatenated retrieved text
- **tableCellRecall**: whether the required table cells (row/column/value) are present in the retrieved table chunks
- **MRR**: mean reciprocal rank — 1 / rank of the first correct chunk
- **sourceChunkSupportRecall**: fraction of reference source/chunk IDs that appear in the retrieved contexts. Measures whether the retrieved chunks include the chunks that contain the evidence. This is a retrieval-only metric — it does not evaluate answer-level citation accuracy (which requires a generated answer with citation statements, deferred to the later RAGAS answer-quality phase).
- **perFormat**: all metrics broken down by file format family

Answer-level metrics (`answerExact`, `numericMatch`, RAGAS answer faithfulness/relevance) are deferred to the later RAGAS answer-quality phase and are not part of 10a acceptance.

Example SEC row:

```json
{
  "sampleId": "sec-apple-10k-2023-q1",
  "fileId": "sec-edgar-0000320193-23-000106",
  "fileFormat": "sec-html-xbrl",
  "sourceUrl": "https://www.sec.gov/Archives/edgar/data/320193/...",
  "userInput": "What was Apple's total net sales for fiscal 2023?",
  "referenceAnswer": "$383.3 billion",
  "requiredPhrases": ["net sales", "2023", "383,285"],
  "referenceChunkIds": ["chunk-apple-10k-2023-item8-table-12"],
  "metadata": {
    "evidenceSource": "XBRL tag us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax",
    "generationMethod": "template",
    "numericValue": 383285000000,
    "numericUnit": "USD"
  }
}
```

Acceptance criteria:

- The runner processes at least 200 real network-downloaded files across at least five format families and produces at least 500 grounded recall items/queries. Each format family must have its own metric breakdown and failure rows; no single format family should dominate the corpus without a recorded reason.
- **Per-format query minimums**: each format family must contribute at least 50 grounded recall items/queries. If a format family has fewer than 50 items, the run is a preflight, not an accepted 10a run. Calibration, development, and holdout splits must each contain data from every format family; no split may be dominated by a single format.
- Every row is grounded in a source file with recorded URL/hash and required phrases/table cells extracted from or manually verified against the real file; synthetic expected spans are not allowed for headline metrics.
- The export proves production parser/chunker/ingestion/Milvus/search components were used. A Python-only text splitter or lexical scorer may be used only in unit fixtures, not headline 10a results.
- The accepted 10a export proves production MQ ingestion was used by recording outbox/event IDs, consumer completion status, parse status, chunk counts, and Milvus insertion evidence. A direct service-call export is a preflight artifact only.
- PDF/visual-track rows prove MinerU was enabled and selected where applicable; if a PDF is intentionally parsed without MinerU, the row records the parser decision and cannot be used to satisfy the MinerU coverage requirement.
- Eval-owned cleanup rules prevent deletion of non-eval knowledge bases, session files, chunks, source files, and Milvus collections. Non-eval preservation guard tests must pass.
- `tune-suite --suite doc-ingestion-retrieval` runs on the real export and produces Phase 9-style artifacts with sealed holdout zero-overlap verification.
- If all formats score perfectly on the accepted 10a run, it is a discriminative-power finding rather than automatic proof that the pipeline is optimal.

Phase 10a prerequisite: OllamaEmbeddingClient connection-resilience hardening (three-layer design)

**Ownership**: this is a Phase 10a sub-task, implemented and verified before the accepted production 10a export run. It is a production-code change (`OllamaEmbeddingClient.java`) and requires its own review gates.

**Motivation**: the old SciFact-style 10a smoke established the current scaling boundary: 5 documents / 3 queries, 100 documents / 10 queries, and 500 documents / 5 queries completed, but 1000 documents / 50 queries failed with an Ollama 500 after roughly 156 seconds and about 1000 sequential embedding calls. The accepted production 10a must not proceed until this is fixed.

Fix is designed in three layers. MQ helps with backpressure but does not fix an HTTP connection leak or exhausted pool — the Ollama client itself must be hardened.

**Layer 1 — Harden OllamaEmbeddingClient itself**

Current state: the client is too thin — a plain `WebClient.post().retrieve().bodyToMono().block()` with no explicit bounded connection provider, timeout, or diagnostic error-body handling. Reactor Netty provides a default connection pool, but it is not sized or eviction-configured for long sequential embedding workloads. Each batch or test run constructs a new client and connection pool, making the lifecycle unpredictable under sustained load.

Target state: a dedicated singleton `WebClient` shared across all Ollama embedding calls, with explicit Reactor Netty configuration:

- `ConnectionProvider` with `maxConnections` small and stable (2–4), `pendingAcquireTimeout`, `maxIdleTime`, `maxLifeTime`, and `evictInBackground` enabled. Do NOT use `Connection: close` — it causes port/TIME_WAIT pressure.
- `HttpClient`-level timeouts: `connectTimeout` and `responseTimeout`.
- Error responses: read the response body on errors and surface Ollama's real error message, not a generic 500. This removes a major debugging blind spot.

Files changed: `OllamaEmbeddingClient.java` (production-code change with review gates).

**Layer 2 — Batch embedding via /api/embed**

Current state: the client calls `/api/embeddings` one call per text. For 1000 documents + 50 queries, that is 1050 sequential HTTP calls.

Target state: add `embedBatch(List<String> texts)` using Ollama's `/api/embed` endpoint, which accepts a string array and returns embeddings in one round-trip. Batch size fixed at 16–32 texts per call. After the response, validate every embedding vector has dimension 1024. Keep the old `/api/embeddings` single-text endpoint as a compatible fallback, but 10a full runs and bulk ingestion must prefer batch.

This is an additive change to `OllamaEmbeddingClient.java` — the existing `embed(String)` method is preserved; `embedBatch()` is new.

**Layer 3 — MQ for backpressure only, not root-cause fix**

MQ helps by limiting concurrent ingestion workers (e.g., 1–2 documents at a time) and avoiding 200 documents flooding Ollama simultaneously. But MQ does not fix an HTTP client that leaks connections or exhausts its pool — it only slows the failure. Therefore:

- MQ-backed ingestion is required for 10a full-chain acceptance.
- Ollama client long-run stability (Layer 1 + Layer 2) is also a required 10a prerequisite.
- An independent regression test must complete at least 1200 embedding inputs (sequential or batched) through `OllamaEmbeddingClient` against local Ollama without connection-pool exhaustion, port exhaustion, or late opaque 500 errors.

**Recommended implementation order for this prerequisite**:

1. Write a minimal repro test targeting `OllamaEmbeddingClient` only — no RAG, no Spring context.
2. Fix the WebClient connection pool and timeouts (Layer 1).
3. Add `embedBatch()` using `/api/embed` (Layer 2).
4. Run the 1200-input long-run regression and prove it passes.
5. Only then run the full 10a MinerU + MQ + Milvus pipeline.

Tests / verification:

Prerequisite tests (OllamaEmbeddingClient hardening — must pass before full 10a pipeline):

- **Slice 1**: Minimal reproducer test targeting `OllamaEmbeddingClient` only — no RAG, no Spring context. 1000+ sequential `embed()` calls against local Ollama bge-m3. Expected: no late opaque 500s, no connection-pool exhaustion. Initially expected to fail, proving the problem.
- **Slice 2**: Singleton WebClient with explicit `ConnectionProvider` config (maxConnections 2–4, pendingAcquireTimeout, maxIdleTime, maxLifeTime, evictInBackground), `HttpClient` timeouts, and error-body reading. Re-run Slice 1 reproducer. Expected: passes.
- **Slice 3**: `embedBatch(List<String>)` via `/api/embed` with batch size 16–32. Validate every embedding vector has dimension 1024. Re-run long-run test with batch path. Expected: passes faster and more reliably than sequential.
- **Slice 4**: 1200-input long-run regression (sequential or batched) completes without connection-pool exhaustion, port exhaustion, or late opaque 500 errors. Failures surface as bounded, diagnostic errors with Ollama's real error message.

10a production pipeline tests:

- New Java tests: infrastructure skip before Spring context load (PostgreSQL + RabbitMQ + Ollama + Milvus + MinerU), eval-owned cleanup guard, parser-family coverage, MinerU-selected PDF/visual route coverage, MQ/outbox consumer completion coverage, and one small mixed-format smoke (5 files, ~10 queries).
- New Java tests: query/answer verifier — evidence extraction from each format family produces non-empty requiredPhrases and valid referenceChunkIds; LLM-rewritten queries pass verifier (answer from rewritten query matches original evidence).
- New Python tests: schema validation for `eval-doc-ingestion-dataset-record.schema.json`, malformed metadata rejection, metric aggregation by format, and `tune-suite --suite doc-ingestion-retrieval` fixture run.
- New Python tests: `test_doc_ingestion_runner.py` — per-format metric breakdowns, malformed/empty row rejection, tuning smoke.
- Manual gate: live PostgreSQL + RabbitMQ + Ollama bge-m3 + Milvus + MinerU export over the 200+ real-document multi-format corpus completes; query/answer generation produces at least 500 grounded recall items across all format families; document-ingestion tuning completes over the full calibration/development/holdout splits; artifacts show per-format recall, citation support, and non-perfect discriminative metrics.
- Java bootstrap suite unaffected.

#### Phase 10b: Memory Real Export

Goal: add a Java export runner that exercises the real `UserMemoryIndexService` and LLM compaction on MTRAG dialogue tasks, producing genuine L2/L3 memory quality measurements.

Likely files:

- `MemoryExportEvalTest.java` (smoke implementation exists and passes on live infrastructure; full-run mode remains for acceptance) — Java export runner for real memory system
- `tools/eval/chatagent_eval/memory_runner.py` — add mode to read Java-exported v2 artifacts
- `tools/eval/tests/test_memory_runner.py` — add real-export mode tests

Calling convention (Path B):

- The export runner uses `@SpringBootTest` with a dedicated test profile (e.g., `eval-real-memory`) that sets `milvus.enabled=true` and `chatagent.memory.l3.enabled=true` to activate `DefaultUserMemoryMilvusIndexService` instead of `NoOpUserMemoryIndexService`.
- Calls `IncrementalSummarizer.summarizeWithDetails()` directly (synchronous), bypassing `AsyncSummaryListener` and its Redis lock. This is appropriate because the export runner processes one dialogue at a time sequentially — no concurrent compaction coordination is needed.
- Calls `LongTermMemoryExtractor.extract()` directly (synchronous), bypassing `LongTermMemoryPromotionListener`. The export runner manages the extraction log lifecycle itself.
- For L3 recall ranking, uses real `OllamaEmbeddingClient.embed()` + `UserMemoryIndexService.search()` instead of the Python token-overlap scorer.
- Infrastructure prerequisites: PostgreSQL (default datasource), Ollama (bge-m3 embeddings), Milvus (vector index), and the configured LLM provider (DeepSeek) must all be reachable. The runner probes each through a JUnit 5 `ExecutionCondition` before the Spring context loads and disables the test class with a clear diagnostic when any prerequisite is unavailable.

Database strategy:

- `IncrementalSummarizer.summarizeWithDetails(sessionId, anchorSeqNo)` internally reads from `SummaryWatermarkService.resolvePendingRange()`, `ChatSessionSummaryRepository.findBySessionId()`, and `TurnBasedContextExtractor.extractPendingTurns()` — all require conversation state in the database. A bare `@SpringBootTest` without pre-populated data returns an empty result.
- The test uses programmatic insertion via Spring beans in `@BeforeEach` to pre-populate eval-prefixed conversation turns, session summary rows (`ChatSessionSummaryRepository`), and watermark rows (`SummaryWatermarkService`) from MTRAG dialogue fixtures. `@AfterEach` cleans up by `eval-memory-*` prefix.
- Prerequisites include PostgreSQL (default datasource) alongside Ollama, Milvus, and the LLM provider.
- DB setup/teardown is verified: `summarizeWithDetails` returns a non-empty `SummaryResult` after fixture insertion, and `@AfterEach` cleanup leaves no eval-prefixed rows.

Spring profile configuration (new file `application-eval-real-memory.yaml`):
```yaml
milvus.enabled: true
chatagent.memory.l3.enabled: true
# datasource: reuse default PostgreSQL from application.yaml
# Milvus collection: chat_user_memory_eval
```
The Maven `eval-real` profile (Surefire groups) activates the test class; the Spring `eval-real-memory` profile (`@ActiveProfiles("eval-real-memory")` within `@SpringBootTest`) configures the application context. They are distinct concepts.

Isolation and cleanup rules:

- All eval-created entities use eval-owned ID prefixes: user IDs like `eval-memory-<sampleId>`, session IDs like `eval-memory-<runId>-<sampleId>`, memory IDs like `eval-mem-<hash>`. This prevents any overlap with real user data.
- The `eval-real-memory` Spring profile sets `milvus.user-memory.collection` to a dedicated eval collection (e.g., `chat_user_memory_eval`) instead of the production `chat_user_memory`. This isolates vector data at the Milvus collection level.
- After each run, cleanup deletes only eval-owned rows (by prefix match) from the DB and drops or flushes only the eval Milvus collection. The runner must never delete from the production collection or non-eval user/session/memory rows.
- Non-eval preservation guard: before cleanup, the runner queries for any non-eval-owned data in the eval collection and fails the test if any is found (indicating a collection name collision with production data).
- Acceptance test: seed a non-eval user's memory into the test DB/collection, run the export, assert only eval-prefixed data was created, and assert the non-eval seed data is unchanged.

Deliverables:

- Java-side export runner that processes MTRAG dialogues through the real memory pipeline (L1 tail-window → L2 LLM compaction → L3 LLM extraction → Milvus recall), producing v2 artifact files.
- Python-side metric computation on exported artifacts, reusing all existing metric functions.
- L2 fact recall reflects real LLM compaction quality (expected < 1.0 for some samples).
- L3 extraction F1 reflects real LLM extraction quality (expected < 1.0 for some samples).
- Full mode processes every Phase 3 MTRAG memory row. Sample caps are allowed only for smoke mode and must be recorded in run config.

Acceptance criteria:

- Export artifacts include provider/model metadata (LLM model name, embedding model name, Ollama base URL) and raw model outputs (L2 compaction JSON, L3 extracted memories JSON) alongside computed metrics, proving real model provenance.
- All eval-created entities use eval-owned ID prefixes. Cleanup deletes only eval-prefixed rows and the dedicated eval Milvus collection.
- Non-eval preservation guard passes: seed non-eval data, run export, assert non-eval data is unchanged.
- User isolation tested: insert L3 memories for two eval-prefixed users (`eval-memory-user-a`, `eval-memory-user-b`) into the same eval Milvus collection. Run L3 recall for user-a only. Assert user-b's memories are not returned.
- Inactive memory exclusion tested: flag one memory belonging to `eval-memory-user-a` as inactive. Run L3 recall. Assert the inactive memory is excluded while active memories for the same user are still returned.
- When Milvus, Ollama, or the LLM provider is not available, the export runner skips with a clear message; deterministic memory runner continues to pass.
- Deterministic `memory_runner.py` mode is unchanged and all existing tests continue to pass.
- Discriminative power gate: for smoke runs, provenance confirmation suffices; if L2 fact recall or L3 F1 equals 1.0 on the smoke slice, the run passes with a warn. For full/tuning runs, if all samples score 1.0 on L2 or L3, it is a finding indicating the measurement lacks discriminative power and needs investigation.
- Full 10b acceptance requires a successful live PostgreSQL + Ollama bge-m3 + Milvus + LLM export over the complete Phase 3 MTRAG dialogue dataset and a subsequent `tune-suite --suite memory-v2` run over that export. The tuning run must use the full calibration/development and sealed holdout splits, not the 10-row smoke slice.

Tests / verification:

- Existing deterministic memory tests pass unchanged.
- New Java unit test: export runner detects Milvus/Ollama/LLM unavailability and skips.
- New Java unit test: non-eval preservation guard — seed non-eval data, run export, assert non-eval data survives.
- Integration test (manual): real Milvus + Ollama + LLM produces genuine quality metrics with provenance metadata.
- Python metrics on exported artifacts match manual spot-check.
- Full manual gate: complete MTRAG memory export plus full memory-v2 tuning finishes, writes full split counts, and reports champion evidence for memory numeric parameters.
- Bootstrap suite unaffected.

#### Phase 10c: Agent Module Real Outputs

Goal: capture real intent classifier, query rewriter, and tool selection outputs on MTRAG tasks, evaluate them against expected behavior, and produce genuine agent module quality measurements.

Likely files:

- `AgentModuleExportEvalTest.java` (smoke implementation exists and passes on live infrastructure; full-run mode remains for acceptance) — Java export runner for real agent module outputs
- `tools/eval/chatagent_eval/agent_module_runner.py` — already supports `moduleOutputs`; add mode to read Java-exported v2 artifacts
- `tools/eval/tests/test_agent_module_runner.py` — add real-export mode tests

Entry points:

The full capture pipeline for each MTRAG query uses three confirmed production entry points:
1. `IntentRouter.route(agentId, query)` → `IntentRoutingResult` (contains `IntentResolution` on resolved, `clarificationCandidates` otherwise; confirmed at `IntentRouter.java:81`).
2. `QueryRewriter.rewrite(originalQuery, intentResolution)` → rewritten query string (confirmed at `QueryRewriter.java:38`).
3. `AgentToolCallbackFactory.create(agentConfig, intentResolution)` → `List<ToolCallback>` — extract tool definition names for the `toolList` field (confirmed at `AgentToolCallbackFactory.java:69`).

Spring profile: Phase 10c uses a dedicated `eval-real-agent-modules` profile (`@ActiveProfiles("eval-real-agent-modules")`), separate from 10b's `eval-real-memory` profile. A new `application-eval-real-agent-modules.yaml` provides the minimal configuration — the LLM provider, database (for intent tree loading), and Redis (for intent tree caching via `DefaultIntentTreeCacheManager`) are required. No Milvus, Ollama, or L3 memory beans are enabled. The infrastructure probe checks PostgreSQL, Redis, and LLM provider availability; Milvus and Ollama are explicitly not required so their unavailability does not skip 10c. This ensures 10c can run or skip independently of Milvus/Ollama availability.

Scope clarifications:

- **Coreference is not a standalone module.** Production coreference resolution is embedded inside the `QueryRewriter` LLM prompt (rule 2: "Resolve References — Expand pronouns and omitted references into the concrete business object"). Coreference quality is measured through rewrite quality: whether the rewritten query correctly expands pronouns/references that the original query omitted. No separate coreference capture is needed.
- **Intent tree source.** The export runner creates a synthetic test intent tree fixture (JSON resource or Java builder) whose node hierarchy matches the MTRAG metadata label taxonomy (domain → questionType → answerability), so that the real `IntentRouter` can classify against realistic nodes. The fixture produces deterministic `IntentResolution` objects for comparison against expected taxonomy labels. This fixture lives in the test class and is not shared with production.
- **Tool selection scope.** Phase 10c captures the available tool list exposed to the LLM for each intent resolution (from `AgentToolCallbackFactory.create(agent, resolution)`), not the LLM's actual tool call decisions (which would require the full `ReactRuntimeEngine` runtime). The available-tool list is compared against expected production `ToolDefinition.name()` values for each intent kind (e.g., KB intents should expose `SessionFileSearchTool`). Capturing the LLM's actual tool call decisions is deferred to a later iteration.

Deliverables:

- Java-side export runner that runs real intent classification and query rewrite on MTRAG tasks, captures available tools per intent resolution, and records all outputs as `moduleOutputs` in v2 sample artifacts.
- Python-side metric computation on exported artifacts, reusing all existing metric functions.
- Real intent classifier accuracy measured against MTRAG ground-truth labels.
- Real query rewrite anchor recall and coreference resolution measured from actual rewritten queries.
- Full mode processes every Phase 3 MTRAG agent-module row. Sample caps are allowed only for smoke mode and must be recorded in run config.

Acceptance criteria:

- Real intent classifier accuracy is measured by a real model against the synthetic intent tree. Run artifacts include raw `IntentRoutingResult` outputs and provider/model metadata proving real model provenance. For smoke runs, provenance confirmation suffices; non-perfect accuracy is an observation. For full/tuning runs, if all samples score perfectly, it is a finding indicating the measurement lacks discriminative power.
- Real query rewrite anchor recall is measurable from actual rewrite output. Run artifacts include raw rewritten queries and provider/model metadata.
- Real tool selection accuracy is measurable: the available tool list for each intent kind is compared against expected tools. For smoke runs, provenance confirmation suffices; non-perfect selection is an observation. For full/tuning runs, if all samples score perfectly, it is a finding.
- When required infrastructure (PostgreSQL, Redis, or LLM provider) is not available, the export runner skips; deterministic agent module runner continues to pass.
- Deterministic `agent_module_runner.py` mode is unchanged and all existing tests continue to pass.
- Full 10c acceptance requires a successful live PostgreSQL + Redis + LLM export over the complete Phase 3 MTRAG dialogue dataset and a subsequent `tune-suite --suite agent-modules` run over that export. The tuning run must use the full calibration/development and sealed holdout splits, not the 10-row smoke slice.

Tests / verification:

- Existing deterministic agent module tests pass unchanged.
- New Java unit test: export runner detects model unavailability and skips.
- New Java unit test: synthetic intent tree covers all MTRAG metadata label combinations present in the dataset.
- Integration test (manual): real model outputs produce measurable quality metrics.
- Full manual gate: complete MTRAG agent-module export plus full agent-modules tuning finishes, writes full split counts, and reports champion evidence for intent/rewrite/tool numeric parameters.
- Bootstrap suite unaffected.

#### Phase 10 Tuning Integration

After each sub-phase produces real-model exports, the tuning framework from Phase 9 is re-run on real metrics to produce genuine champion selections. Phase 10a adds `doc-ingestion-retrieval` tuning support to `tune-suite` for production parser/chunker/ingestion retrieval quality.

Files that must change to add `doc-ingestion-retrieval` tuning:

- **`tuning_runner.py`**: add `"doc-ingestion-retrieval"` to `SUPPORTED_SUITES`; add `SUITE_CONFIG_FIELDS["doc-ingestion-retrieval"]` mapping tuning parameters (topK, candidateK, rrfK, chunkSize, chunkOverlap where configurable) to `DocIngestionConfig` fields; add `_dataset_id("doc-ingestion-retrieval")` returning the Phase 3 document-ingestion dataset ID; add `_run_suite` dispatch for `"doc-ingestion-retrieval"` calling `run_doc_ingestion()`.
- **`doc_ingestion_runner.py`** (new): define `DocIngestionConfig` dataclass with parameter validation, `run_doc_ingestion()` function reading Java real-export rows from the Phase 3-compatible dataset root, computing per-format retrieval metrics, and writing v2 artifacts (report.json, samples.jsonl, failures.jsonl) to the output run directory.
- **`run_eval.py`**: add `doc-ingestion-retrieval` to the tune-suite CLI suite validation (if validated).
- **`test_tuning_runner.py`**: add fixture test `test_cli_tune_suite_runs_doc_ingestion_retrieval` and policy regression test.
- **`doc-ingestion-retrieval-v1.json`** (new): parameter space with fields matching the production ingestion/retrieval parameters.
- **`doc-ingestion-retrieval-v1.json`** (new, tuning-policies/): tuning policy with `primaryMetric`, `direction`, `baselineParameters`, `secondaryMetrics`, `hardGates`, and gate configuration.
- **`parameter-registry-v1.json`**: add `doc-ingestion-retrieval-v1` entry if the registry tracks parameter spaces.

Bridge contract: Phase 10 Java export runners write a **Phase 3-compatible dataset root** alongside their run artifacts. No adapter mode in `tune-suite` is needed. Specifically, each export runner produces:

- `manifests/datasets/<datasetId>.json` — dataset manifest with `datasetId`, `datasetHash` (SHA-256 of the real-export JSONL), `splitManifestPath`, `localPath`, `recordCount`, `groupCount`, `splits` summary, and `provenance` object recording `provider`, `modelName`, `embeddingModel`, and `exportTimestamp`.
- `manifests/splits/<datasetId>.json` — split manifest preserving the sealed split/holdout contract: same `groupHash` and `groupIds` from the original Phase 3 source splits, so holdout verification produces zero source-group overlap.
- `datasets/<suite>/<datasetId>.jsonl` — the real-model samples as dataset rows.

Per-suite dataset row schemas:

- **10a (doc-ingestion-retrieval)** — `datasets/doc-ingestion/<id>.jsonl`: rows include `sampleId`, `datasetId`, `sourceGroupId`, `split`, `fileId`, `fileFormat`, `sourceUrl`, `userInput`, `requiredPhrases`, optional `requiredTableCells`, `referenceChunkIds`, `retrievedContexts`, `metadata.parser`, `metadata.chunker`, `metadata.ingestion`, `metadata.mineru` when PDF/visual routing applies, `metadata.mq` for accepted full-chain ingestion, and `metadata.candidateContexts` with rank signals.
- **10b (memory)** — `datasets/memory/<id>.jsonl`: rows include `sampleId`, `datasetId`, `sourceGroupId`, `split`, `turns` (original conversation turns from MTRAG dialogues), `expectedResponse`, `referenceContextIds`, `metadata.l1Segments`, `metadata.l2Segments`, `metadata.l3Memories`, `moduleOutputs.l1Summary` (raw `SummaryResult`), `moduleOutputs.l3Extraction` (raw `ExtractionResult`), `moduleOutputs.provider` (model name, embedding model).
- **10c (agent-modules)** — `datasets/agent-modules/<id>.jsonl`: rows include `sampleId`, `datasetId`, `sourceGroupId`, `split`, `turns` (original MTRAG query + metadata), `expectedResponse`, `referenceContextIds`, `moduleOutputs.intent` (raw `IntentRoutingResult` serialized from `IntentRouter.route()`), `moduleOutputs.queryRewrite` (rewritten query text from `QueryRewriter.rewrite()`), `moduleOutputs.toolList` (list of tool definition names from `AgentToolCallbackFactory.create()`), `moduleOutputs.provider` (model name, classifier model, rewrite model).

The `tune-suite` CLI uses the export directory directly as `--dataset-root` with no adapter.

Provenance traceability: the dataset manifest's `provenance` field records the real model/embedding provider and model names. The experiment manifest records `datasetHash`. Provenance is traceable through the hash chain: `experiment-manifest.json` → `datasetHash` → dataset manifest → `provenance` object. No changes to `tuning_runner.py` provenance propagation are required — the current `models: {}` field in trial artifacts is left empty; the authoritative provenance lives in the dataset manifest referenced by hash.

For each completed sub-phase (10a, 10b, 10c):

1. **Export real-model artifacts** using the new Java export runner (or updated fixture), writing the Phase 3-compatible dataset root structure to a directory under `artifacts/eval/phase10/`.
2. **Run Phase 9 tuning** via `python tools/eval/run_eval.py tune-suite` with the export directory as `--dataset-root`, the suite-owned parameter space and policy, and the full real dataset splits. Smoke runs may use `--combination-budget 2` and sample caps; full acceptance runs must either omit sample caps or set them equal to the full available calibration/development and holdout counts, and must use the complete approved parameter grid or record any reduction as non-acceptance exploratory evidence.
3. **Expected outputs** follow the Phase 9 artifact structure: `experiment-manifest.json`, `trials.jsonl`, `leaderboard.csv`, `pareto-frontier.json`, `champion-candidate.yaml`, `holdout-verification.json`, `promotion-decision.md`.
4. **Validation**: the tuning run must write a complete manifest recording `datasetHash` linking to a dataset manifest with real-model provenance, sealed-holdout verification with zero source-group overlap, and a promotion candidate that either confirms or differs from the deterministic baseline champion.

Acceptance criteria:

- Phase 10 export runners produce a Phase 3-compatible dataset root that `tune-suite` can read without adapter changes.
- Each Phase 10 suite completes a real-model tuning run at its accepted sample size and produces all Phase 9-style artifacts. Smoke-budget tuning is useful for preflight only and is not sufficient for Phase 10 acceptance.
- The experiment manifest's `datasetHash` links to a dataset manifest containing a `provenance` object with real model/provider metadata, proving real outputs were used.
- The promotion candidate is based on real-model metrics, not deterministic proxy metrics.
- No production runtime default changes; the promotion candidate remains a review-only artifact.

Tests / verification:

- New Python test: fixture real-export directory (with manifests including provenance + JSONL) feeds `tune-suite` successfully for each supported suite, producing Phase 9 artifact set with sealed-holdout verification.
- Smoke tuning run on real-model export artifacts produces a complete Phase 9 artifact set as preflight.
- Full/large accepted tuning run on each real-model export artifact set uses the approved split counts and produces a complete Phase 9 artifact set.
- Dataset manifest in fixture contains `provenance` object; experiment manifest's `datasetHash` matches.
- `holdout-verification.json` passes with zero source-group overlap.

### Phase 11: Documentation And Runbook

Goal: make the new workflow usable.

Likely files:

- `tools/eval/README.md`
- `docs/summary/12-eval-system.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`
- `docs/env_variables.txt` variable-name coverage only

Deliverables:

- Smoke/full commands.
- Required local services.
- Env var names and secret boundaries.
- Baseline update procedure.
- Troubleshooting guide.

Acceptance criteria:

- A new agent can run smoke eval from docs.
- Full eval prerequisites are explicit.
- Manual E2E checks are not marked complete unless actually run.
- Parameter search, sealed-holdout verification, and configuration-promotion procedures are documented.

### Phase 12: Legacy Removal

Goal: finish replacement and reduce maintenance load.

Deliverables:

- Remove or archive old eval package classes that v2 replaces.
- Keep only benchmark/reliability tests that are intentionally scoped outside RAGAS.
- Delete stale result artifacts from local target directories.

Acceptance criteria:

- No duplicate RAGAS/RAG quality suite remains.
- Old golden datasets are deleted or quarantined outside v2 and are not used by any current quality gate.
- Default tests are clean.
- New v2 reports are the only current evaluation source of truth.

## Files / Docs To Create Or Update

Create:

- `tools/eval/pyproject.toml`
- `tools/eval/README.md`
- `tools/eval/run_eval.py`
- `tools/eval/chatagent_eval/**`
- `tools/eval/config/parameter-spaces/**`
- `tools/eval/config/recommended/**`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/**`
- `chatagent/bootstrap/src/test/resources/eval/v2/**`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Update:

- `chatagent/bootstrap/pom.xml`
- `.gitignore`
- `docs/summary/12-eval-system.md`
- `docs/env_variables.txt` only for variable-name coverage, without exposing values

Delete after replacement:

Note: Phase 1 deletes or quarantines obsolete legacy eval files before v2 implementation begins. This list tracks the cumulative end-state for files that may also need Phase 12 final cleanup; it does not mean these files should survive until all v2 replacement suites exist.

- `RetrievalQualityIntegrationEvalTest.java`
- `RetrievalQualityEvalTest.java`
- `RerankerAbEvalTest.java` if v2 reranker A/B replaces it
- `LatencyBaselineEvalTest.java` if v2 latency replaces it
- `ResponseQualityEvalTest.java` if Ragas response suite replaces it
- `MemorySummaryEvalTest.java` if v2 memory suite replaces it
- `QueryRewriteAbEvalTest.java`, `ContextualEnrichmentAbEvalTest.java`, `ToolCallEvalTest.java`, `MultiturnDialogueEvalTest.java`, `IntentRouting*EvalTest.java` after v2 module suites exist

Move or rename:

- MQ/lock/outbox/circuit-breaker tests into benchmark/reliability naming and tag family.

## Acceptance Criteria

- Default Maven tests do not run eval suites and do not write eval reports.
- No eval source uses hardcoded personal absolute paths.
- No eval setup deletes non-eval production/dev data.
- RAG/RAGAS data exports map cleanly to Ragas `SingleTurnSample`.
- Retrieval metrics and Ragas metrics are separated in reports.
- Text recall accuracy is measured independently of answer quality.
- Memory evaluation covers L1, L2, L3 promotion, and L3 recall.
- Reports are reproducible by run ID and include dataset/config/model metadata.
- Thresholds are versioned per suite and per mode.
- Every numeric setting is registered as quality-tunable, operational-only, safety-fixed, or excluded with rationale.
- Accuracy-first tuning uses real-data development splits, never iterative access to sealed holdout data.
- Trial artifacts can reproduce the selected `top-k`, `candidate-k`, `rrf-k`, reranker, text-recall, memory, and agent-module values.
- Recommended configurations are promoted through reviewed artifacts rather than silently rewriting runtime defaults.
- Documentation explains smoke/full commands and prerequisites, and clearly states that Phase 10a/10b/10c final acceptance requires accepted-size real exports plus tuning rather than smoke results.

## Test Strategy

Narrow checks:

- Schema validation tests.
- Deterministic metric unit tests.
- Threshold evaluation tests.
- Report writer tests.
- Parameter registry, constraint, config fingerprint, search, and champion-selection tests.
- Split leakage and sealed-holdout access tests.
- Eval-owned fixture cleanup tests.

Integration checks:

- RAG retrieval smoke export with eval-owned corpus.
- Ragas smoke runner over exported JSONL.
- Memory L2/L3 smoke with controlled mocked or local LLM behavior where practical.
- Full local-gpu suite only when Milvus/Ollama/reranker/judge model are available.
- Dataset download/manifest verification for real public corpora.
- Accuracy-tuning smoke run that performs a small sensitivity sweep and reproduces the winning trial.

Manual checks:

- Confirm no non-eval KBs are deleted.
- Inspect `artifacts/eval/<run-id>/report.md`.
- Confirm reports contain no secrets.
- Confirm docs commands match actual scripts.
- Spot-check sampled real documents and qrels/references against source files.
- Confirm a promoted candidate was selected without tuning on sealed holdout results.

## Risks

- Ragas API and metric names may shift; pin dependency versions and verify against official docs during implementation.
- LLM judge metrics are noisy; thresholds should start as warn-only until baselines are stable.
- Full infra suites can be slow and expensive; keep smoke suites small.
- Real public corpora can be large and slow to ingest; downloaders must support sampling, caching, hashes, and resume.
- Some public datasets have mixed licenses; manifests must record licenses and exclude files that cannot be reused.
- Synthetic corpora can overfit; they are only allowed for fixtures/stress and must not drive headline quality claims.
- Memory quality metrics are partly semantic; string-only recall can miss paraphrases and over-penalize good summaries.
- Phase 4–8 deterministic proxies measure infrastructure correctness, not real model quality. Phase 10 upgrades these to real model connections.
- Cross-language Java/Python artifacts need schema discipline to avoid drift.
- Large parameter spaces can create combinatorial cost; use sensitivity screening, valid constraints, pruning, caching, and explicit trial budgets.
- Tuning against one dataset/domain can overfit; use source-grouped splits, protected category gates, challenge sets, and sealed holdout verification.
- LLM/judge drift can reorder candidates; freeze judge configuration per experiment and repeat stochastic comparisons before promotion.
- Maximizing accuracy without constraints can produce unusable latency/cost; keep those values as hard gates and tie-breakers, not the primary objective.

## Phase 0 Decisions

1. Use official Python Ragas through `tools/eval`. Do not keep a Java-only RAGAS compatibility mode; Java metrics are deterministic retrieval/text-recall metrics only.
2. First implementation source subset is BEIR SciFact, IBM MTRAG human tasks, and a small SEC EDGAR filing/XBRL set. BEIR NFCorpus, MIRACL, PMC OA, arXiv, Data.gov, and EnterpriseRAG-Bench are deferred.
3. Default judge provider/model is DeepSeek `deepseek-chat`, shared by Ragas LLM metrics and semantic memory metrics unless eval-specific configuration overrides it.
4. CI fails only deterministic/local gates: cleanup scope, tag exclusion, schema/manifest validation, deterministic metrics, and no-live-provider smoke. Ragas LLM judge, semantic memory judge, live infra, full corpus, load/chaos, and paid-provider suites are warn-only or manual.
5. The old private course PDF corpus is dropped from source, headline metrics, and v2 datasets. It may be used only as an optional local-only ignored corpus if explicitly configured later, with no hardcoded absolute paths and no quality-gate role.
6. Numeric configuration selection is accuracy-first and experiment-driven. Every numeric setting must be registered; quality parameters are tuned on real-data development splits, sealed holdouts are used only for final verification, and runtime-default promotion requires a separate reviewed change.

No blocking Phase 0 open questions remain. Phase 3 performed actual pinned-download, hash, manifest, size-gate, and source-group split validation for BEIR SciFact and IBM MTRAG. The SEC EDGAR 25-file smoke preparation also completed with a process-local contact-bearing User-Agent; generated artifacts contain per-file source URLs/hashes and HTML-grounded recall samples without persisting the contact value.

## Recommended Next Step

Stop treating the currently running SciFact real-embedding smoke as Phase 10 acceptance evidence. Next, retire or quarantine the old SciFact 10a implementation, then implement the replacement Phase 10a production document-ingestion eval and run the accepted Phase 10 sequence: 10a multi-format document-ingestion export + doc-ingestion-retrieval tuning, full 10b MTRAG memory export + memory-v2 tuning, and full 10c MTRAG agent-module export + agent-modules tuning. Commit only after artifacts and review notes clearly separate optional benchmark evidence from final production-path quality evidence.
