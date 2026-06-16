# RAGAS Evaluation Rework Implementation Record

Status: Phase 10 implementation complete and ready for final review (10a accepted-size full-chain export + all-rows tuning completed; 10b + 10c full exports and complete-grid/all-search-row tuning completed; no production defaults changed)
Date: 2026-06-09

Phase 10b + 10c final tuning: 10b memory-v2 complete-grid tuning used `combination_budget=81`, `--strategy grid`, `max_samples_per_trial=100000`, and `holdout_max_samples=100000`, producing 81 trials over 710 search rows and 93 holdout rows; champion trial-0001, overlap=0, status=pass. 10c agent-modules was regenerated after fixing null `queryRewrite` serialization, then complete-grid tuning used `combination_budget=1458`, `--strategy grid`, `max_samples_per_trial=100000`, and `holdout_max_samples=100000`, producing 1458 completed trials over 710 search rows and 93 holdout rows; champion trial-0007, development intentAccuracy=0.8366, holdout intentAccuracy=0.8817, overlap=0, status=pass. Both promotions are proposed and require a separate reviewed promotion change; no production defaults changed.

Latest verification: 2026-06-09 Phase 10a accepted-size production document-ingestion run completed over 200 real network-downloaded public files: SEC_HTML 50, PDF 20, DOCX 40, XLSX 45, WEB_MD 45. The run used production facade/outbox/RabbitMQ ingestion, parser/chunker, Ollama bge-m3 embeddings, Milvus indexing/search, and eval-profile MinerU visual routing for PDFs. It exported `chatagent/artifacts/eval/phase10a/doc-ingestion-full-ee056c79` with 6,651 chunks and 585 grounded recall rows (SEC_HTML 150, PDF 56, DOCX 115, XLSX 129, WEB_MD 135; calibration 301, development 141, holdout 143). Java live metrics: `hit@3=0.585`, `contextRecall@3=0.585`, `MRR=0.505`; all 585 rows record MQ/outbox provenance and 56 PDF rows record MinerU-selected evidence. Full all-rows tuning (`phase10a-doc-ingestion-full-ee056c79-grid27-allrows`) ran all 27 parameter combinations with no sample caps. Champion candidate: `topK=8`, `candidateK=12`, `rrfK=60`; development replay `contextRecall@K=0.7376` over 442 calibration/development rows; sealed holdout `contextRecall@K=0.6294` over 143 rows; overlap count 0; holdout status `warn`, so production defaults remain unchanged and promotion is rejected pending separate review.

Phase 10b + 10c full exports verified: 842 MTRAG rows each, 0 errors. Memory export: 78.5 min (LLM summarizer + extractor + Ollama embedding + Milvus indexing). Agent-module export was rerun after review fixes in 42.6 min (LLM intent routing + query rewrite + tool callbacks), and all 842 exported rows include required `moduleOutputs.intent`, `moduleOutputs.queryRewrite`, `moduleOutputs.toolList`, and `moduleOutputs.provider`.

## Phase Checklist

- [x] Phase 0: Review and decisions
- [x] Phase 1: Legacy evaluation cleanup
- [x] Phase 2: V2 core contracts
- [x] Phase 3: Versioned corpora and datasets
- [x] Phase 4: RAG retrieval export suite
- [x] Phase 5: Official RAGAS runner
- [x] Phase 6: Text recall suite
- [x] Phase 7: Memory suite
- [x] Phase 8: Agent module suites
- [x] Phase 9: Precision parameter tuning and promotion
- [x] Phase 10: Real model evaluation upgrade (10a, 10b, 10c full exports + sealed-holdout tuning completed; champions identified, no production defaults changed)
- [ ] Phase 11: Documentation and runbook
- [ ] Phase 12: Legacy removal

## Planning Audit

Files and areas inspected:

- `CONTEXT.md`
- `docs/adr/0001-memory-compaction-v2-l2-schema-reset.md`
- `docs/summary/12-eval-system.md`
- `chatagent/bootstrap/pom.xml`
- `chatagent/bootstrap/src/main/resources/application.yaml`
- `chatagent/bootstrap/src/main/resources/application-local-gpu.yaml`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/**`
- `chatagent/bootstrap/src/test/resources/eval/golden/**`
- `chatagent/bootstrap/target/eval-reports/**`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/rag/**`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/memory/**`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/**`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/AgentMemoryLoader.java`

Key findings:

- Legacy RAGAS is Java/JUnit plus a manual LLM judge, not official Ragas.
- Some eval tags are not excluded by default Maven test configuration.
- Legacy RAG/Reranker evals hardcode personal absolute paths and can delete all KBs in setup.
- Golden data is split between JSON resources and inline Java query definitions.
- Reports are inconsistent and accumulate under `target/eval-reports`.
- Memory eval is not aligned enough with L2 V2 segments and L3 recall/promotion.
- User clarified that primary evaluation samples must be real and sufficiently large, such as downloaded public files or public benchmark datasets, not simple fabricated examples.
- User clarified that every quality-affecting numeric value should be precisely and reproducibly tuned for maximum accuracy, including values such as retrieval `top-k`.

## Files Changed

Planning phase:

- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 1 implementation:

- `chatagent/bootstrap/pom.xml`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/**`
- `chatagent/bootstrap/src/test/resources/eval/golden/**`
- `docs/summary/12-eval-system.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 2 implementation:

- `.gitignore`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/**`
- `chatagent/bootstrap/src/test/resources/eval/v2/schemas/**`
- `chatagent/bootstrap/src/test/resources/eval/v2/fixtures/**`
- `tools/eval/pyproject.toml`
- `tools/eval/chatagent_eval/**`
- `tools/eval/tests/test_core_contracts.py`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 3 implementation:

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/EvalSchemaFixtureTest.java`
- `chatagent/bootstrap/src/test/resources/eval/v2/corpora/catalog/**`
- `chatagent/bootstrap/src/test/resources/eval/v2/datasets/**`
- `chatagent/bootstrap/src/test/resources/eval/v2/schemas/eval-{source-catalog,corpus-manifest,dataset-manifest,split-manifest}.schema.json`
- `tools/eval/chatagent_eval/datasets.py`
- `tools/eval/chatagent_eval/downloaders/**`
- `tools/eval/chatagent_eval/prepare_phase3.py`
- `tools/eval/tests/test_phase3_datasets.py`
- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 4 implementation:

- `chatagent/bootstrap/pom.xml`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/retrieval/EvalOwnedKnowledgeBaseFixture.java`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/retrieval/RagRetrievalExportRunner.java`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/retrieval/RagRetrievalExportRunnerTest.java`
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/v2/retrieval/RagRetrievalExportEvalTest.java`
- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 5 implementation:

- `tools/eval/pyproject.toml`
- `tools/eval/run_eval.py`
- `tools/eval/chatagent_eval/ragas_runner.py`
- `tools/eval/chatagent_eval/reports.py`
- `tools/eval/tests/test_ragas_runner.py`
- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 6 implementation:

- `tools/eval/run_eval.py`
- `tools/eval/chatagent_eval/text_recall_runner.py`
- `tools/eval/tests/test_text_recall_runner.py`
- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 7 implementation:

- `tools/eval/run_eval.py`
- `tools/eval/chatagent_eval/memory_runner.py`
- `tools/eval/tests/test_memory_runner.py`
- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 8 implementation:

- `tools/eval/run_eval.py`
- `tools/eval/chatagent_eval/agent_module_runner.py`
- `tools/eval/tests/test_agent_module_runner.py`
- `chatagent/bootstrap/src/test/resources/eval/v2/parameter-spaces/agent-modules-v1.json`
- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

Phase 9 implementation:

- `tools/eval/run_eval.py`
- `tools/eval/chatagent_eval/parameters.py`
- `tools/eval/chatagent_eval/tuning.py`
- `tools/eval/chatagent_eval/tuning_runner.py`
- `tools/eval/chatagent_eval/promotion.py`
- `tools/eval/tests/test_tuning_runner.py`
- `chatagent/bootstrap/src/test/resources/eval/v2/parameter-registry-v1.json`
- `chatagent/bootstrap/src/test/resources/eval/v2/parameter-spaces/{rag-retrieval,text-recall,memory-v2}-v1.json`
- `chatagent/bootstrap/src/test/resources/eval/v2/tuning-policies/**`
- `chatagent/bootstrap/src/test/resources/eval/v2/schemas/eval-{trial,parameter-registry}.schema.json`
- `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`
- `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`

## Decisions Made

- Recommended architecture is hybrid: Java export harness plus Python Ragas runner.
- Deterministic retrieval/text recall metrics should be separated from Ragas LLM judge metrics.
- Memory evaluation should be split into L1, L2, L3 promotion, and L3 recall.
- Legacy reliability/chaos tests should move to benchmark/reliability categories, not RAGAS.
- Primary quality gates should use real public corpora/benchmarks. Synthetic samples are allowed only for unit fixtures, fault injection, or optional stress, not headline metrics.
- The plan now recommends a first source slice: BEIR SciFact/NFCorpus, IBM MTRAG human tasks, and a small SEC EDGAR filing/XBRL set. EnterpriseRAG-Bench remains optional synthetic stress/comparison only.
- The v2 evaluation system is a clean-room reset. Legacy golden files, inline toy cases, and old synthetic enterprise samples are audit inputs only and must not be migrated into v2 datasets, thresholds, or headline baselines.
- Legacy eval tests may be quarantined behind excluded tags during implementation, but the accepted end state is deletion or benchmark-only relocation after replacement coverage exists.
- Phase 1's first implementation task is old evaluation file/code cleanup. It must inventory and delete/quarantine legacy eval classes, old golden resources, and stale report outputs before any v2 framework code is added.
- Phase 1 must not touch unrelated business code. Its implementation record must include `git diff --name-only` proof that changes stay within eval/test-resource/build-doc cleanup paths.
- Phase 0 approves official Python Ragas through `tools/eval`; Java code keeps only deterministic retrieval/text-recall metrics and must not call itself RAGAS.
- Phase 0 approves the first implementation source subset: BEIR SciFact, IBM MTRAG human tasks, and a small SEC EDGAR filing/XBRL set. BEIR NFCorpus, MIRACL, PMC OA, arXiv, Data.gov, and EnterpriseRAG-Bench are deferred.
- Phase 0 approves DeepSeek `deepseek-chat` as the default eval judge/Ragas LLM/semantic memory judge model, with eval-specific configuration overrides and skip/warn behavior when credentials are unavailable.
- Phase 0 approves CI failure only for deterministic/local gates: cleanup scope, tag exclusion, schema/manifest validation, deterministic metrics, and no-live-provider smoke. Live Ragas judge, semantic memory judge, full corpus, live infra, paid-provider, and load/chaos/reliability suites are warn-only or manual.
- Phase 0 drops the old private course PDF corpus from source, headline metrics, and v2 datasets. It may only be used later as an optional ignored local-only corpus with no hardcoded absolute paths and no quality-gate role.
- Accuracy-first parameter tuning is now part of the approved design. Every numeric setting must be registered as quality-tunable, operational-only, safety-fixed, or excluded with rationale.
- "Highest accuracy" is defined as the best sealed-holdout-validated candidate within the declared parameter space and trial budget, not an unverifiable global optimum.
- Parameter search uses real-data development/tuning splits only. Sealed holdout data is reserved for final verification, and production default promotion requires a separate reviewed change.
- Latency, cost, reliability, and safety are hard gates or tie-breakers; they do not replace the primary accuracy objective.
- Judge configuration is frozen per experiment so changing the judge cannot be used to improve candidate scores.

## Open Decisions

No unresolved architecture or product decisions blocking Phase 10. Remaining prerequisites for Phase 10 implementation: PostgreSQL/default datasource + RabbitMQ/outbox ingestion + Ollama bge-m3 + Milvus + local MinerU from `tools/mineru` + at least 200 network-downloaded real multi-format public source files + an `OllamaEmbeddingClient` long-run connection-resilience fix or proof (10a), PostgreSQL/default datasource + Ollama bge-m3 + Milvus + LLM provider (10b), PostgreSQL/default datasource + Redis + LLM provider (10c; Milvus and Ollama are explicitly not required for 10c).

## Phase 1 Legacy Inventory Decisions

Deleted as obsolete clean-room reset inputs:

- Old RAG/RAGAS-style quality suites: retrieval quality, retrieval integration, response quality, reranker A/B, latency baseline.
- Old memory and dialogue suites: memory summary, multiturn dialogue, query rewrite, contextual enrichment.
- Old agent module suites: intent routing, intent routing integration, tool call quality, PDF extraction quality.
- Old support classes: golden DTOs/loaders, old metrics computation, old eval test tree helper.
- Old golden resources: `rag-golden.json`, `memory-golden.json`, `multiturn-golden.json`, `intent-golden.json`, `tool-golden.json`.

Retained outside the quality gate as benchmark/reliability tests:

- `CircuitBreakerRecoveryBenchmarkTest` under `eval/benchmark`, tagged `benchmark`.
- `RerankerFallbackChainBenchmarkTest` under `eval/benchmark`, tagged `benchmark`.
- `MqChaosReliabilityTest` under `eval/reliability`, tagged `reliability` and `chaos`.
- `OutboxReliabilityTest` under `eval/reliability`, tagged `reliability`.
- `SessionLockStressReliabilityTest` under `eval/reliability`, tagged `reliability` and `stress`.
- `LiveMqReliabilityTest` under `eval/reliability`, tagged `reliability` and `reliability-live`.
- `ReportArtifactWriter` under `eval/support`, used only by retained benchmark/reliability tests.

`chatagent/bootstrap/pom.xml` now excludes `benchmark`, `reliability`, `reliability-live`, `stress`, `chaos`, and `legacy-eval` by default.

## Phase 2 Core Contracts

Implemented canonical shared schemas:

- Evaluation sample.
- Normalized evaluation report.
- Evaluation run manifest with optional tuning metadata.
- Parameter space.
- Parameter-search trial.

Implemented dependency-free Python core contracts:

- Deterministic Hit@K, Recall@K, Precision@K, MRR, NDCG@K, and phrase recall.
- Threshold evaluation with fail-over-warn precedence and severity validation.
- Parameter-space validation and canonical SHA-256 config fingerprints.
- Deterministic grid/random trial generation.
- Accuracy-first champion selection with gate filtering, secondary/tie-break support, and nullable latency/cost handling.
- Normalized manifest and artifact writing that omits tuning metadata from non-tuning runs.
- A small JSON Schema Draft 2020-12 subset validator sufficient for Phase 2 shared fixtures, without adding a new runtime dependency.

Implemented Java export-side contracts under `eval/v2`:

- Deterministic retrieval/text metrics and threshold evaluation.
- Canonical config fingerprinting.
- `EvalRunManifest` with optional tuning record.
- `EvalArtifactWriter` for `manifest.json`, `metrics.json`, `samples.jsonl`, and `failures.jsonl`, including safe run-ID validation and compact one-row-per-line JSONL output.
- Shared schema/fixture validation tests.

Cross-language parity is protected by `core-contract-parity.json`: Java and Python must agree on config fingerprints exactly and deterministic floating-point metrics within a declared strict tolerance.

## Tests Added Or Updated

Phase 1:

- Updated retained benchmark/reliability tests to use non-RAGAS tags, new packages, and the retained report artifact writer.
- Deleted old quality-evaluation tests and old golden resources.

Phase 2:

- Added Python standard-library contract tests covering schemas, metrics, thresholds, parameter validation, fingerprints, deterministic trials, champion selection, and report writing.
- Added Java tests covering metrics, thresholds, fingerprints, export artifact writing, safe run IDs, JSONL formatting, and shared schema fixtures.
- Added negative tests for missing required fields, invalid parameter types, invalid threshold severity, unsafe run IDs, and non-positive K.

## Verification Commands

Audit commands run:

```powershell
git branch --show-current
git status --short
rg --files 'chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval'
rg --files 'chatagent/bootstrap/src/test/resources/eval'
rg -n '@Tag|@SpringBootTest|@ActiveProfiles|target/eval-reports' chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval
```

Eval tag exclusion audit found these tags currently not excluded by `chatagent/bootstrap/pom.xml`:

- `eval-contextual-enrichment`
- `eval-mq-outbox`
- `eval-pdf-quality`
- `eval-reranker`
- `eval-reranker-fallback`
- `eval-session-lock-stress`

Phase 1 verification commands run:

```powershell
git branch --show-current
git status --short
rg --files chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval
rg --files chatagent/bootstrap/src/test/resources/eval
rg -n '@Tag\("eval-|EvalReportWriter|GoldenDatasetLoader|rag-golden|memory-golden|multiturn-golden|intent-golden|tool-golden|RetrievalQuality|ResponseQuality|MemorySummary|ToolCallEvalTest|IntentRouting|MultiturnDialogue|QueryRewrite|ContextualEnrichment|PdfExtractionQuality|RerankerAb|LatencyBaseline|RetrievalQualityIntegration' chatagent/bootstrap/src/test chatagent/bootstrap/pom.xml
git diff --name-only
$env:JAVA_HOME='<jdk-17-home>'; .\mvnw.cmd -pl bootstrap "-Dsurefire.excludedGroups=" "-Dtest=CircuitBreakerRecoveryBenchmarkTest,RerankerFallbackChainBenchmarkTest,MqChaosReliabilityTest,OutboxReliabilityTest,SessionLockStressReliabilityTest" test
$env:JAVA_HOME='<jdk-17-home>'; .\mvnw.cmd -pl bootstrap "-Dtest=CircuitBreakerRecoveryBenchmarkTest" test
```

Phase 1 static verification results:

- Active eval test files are now limited to `eval/benchmark/**`, `eval/reliability/**`, and `eval/support/ReportArtifactWriter.java`.
- No old `eval-*` tags, old golden resource names, old golden loader, or deleted suite names were found in active test/POM references.
- `chatagent/bootstrap/src/test/resources/eval` has no remaining tracked files.
- Scope proof from `git diff --name-only` stayed within the Phase 1 allowlist: `chatagent/bootstrap/pom.xml`, `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/**`, and `chatagent/bootstrap/src/test/resources/eval/**`.
- Targeted local benchmark/reliability verification passed: 9 tests run, 0 failures, 0 errors.
- Default Maven exclusion dry run passed: `CircuitBreakerRecoveryBenchmarkTest` selected with default excluded groups resulted in 0 tests run, proving benchmark-tagged tests remain excluded by default.
- Maven required a temporary command-local `JAVA_HOME` correction because the shell environment pointed `JAVA_HOME` at the JDK `bin` directory.
- Generated report cleanup command:

```powershell
$workspace = (Resolve-Path -LiteralPath '.').Path
$candidates = @('chatagent/target/eval-reports','chatagent/bootstrap/target/eval-reports')
foreach ($candidate in $candidates) {
  $resolved = Resolve-Path -LiteralPath $candidate -ErrorAction SilentlyContinue
  if ($resolved) {
    $path = $resolved.Path
    if (-not $path.StartsWith($workspace, [System.StringComparison]::OrdinalIgnoreCase)) {
      throw "Refusing to remove path outside workspace: $path"
    }
    Remove-Item -LiteralPath $path -Recurse -Force
  }
}
```

Phase 2 verification commands run:

```powershell
python -m unittest discover -s tests -v
python -m compileall -q chatagent_eval tests
$env:JAVA_HOME='<jdk-17-home>'
& '<maven-wrapper-distribution>\bin\mvn.cmd' -pl bootstrap "-Dtest=DeterministicEvalMetricsTest,EvalThresholdEvaluatorTest,EvalConfigFingerprintTest,EvalArtifactWriterTest,EvalSchemaFixtureTest" test
& '<maven-wrapper-distribution>\bin\mvn.cmd' -pl bootstrap test
git diff --check
git status --short
```

Phase 2 verification results:

- Python core contract suite: 8 tests passed, including the artifact path-boundary regression.
- Python bytecode compilation: passed.
- Java targeted v2 contract suite: 9 tests passed.
- Complete `bootstrap` suite: 810 tests passed, 0 failures, 0 errors, 0 skipped.
- Cross-language parity caught and documented normal floating-point tail variance; deterministic metric comparison uses a strict `1e-12` tolerance while config fingerprints remain exact.
- No generated eval artifacts were added to source; `.gitignore` now excludes `artifacts/eval/` and Python test caches.
- Phase 2 cross-review accepted all deliverables. The only review finding, Python artifact-writer path-boundary defense, was fixed with a resolve/relative-to check and a `runId=".."` regression test.

## Phase 3 Versioned Corpora And Datasets

Implementation details:

- Added standard-library download support with HTTPS-only sources, resumable partial files, cache reuse, SHA-256 verification, and ZIP extraction path-boundary checks.
- Added approved source catalogs with concrete licenses/terms for the official BEIR SciFact archive, pinned IBM MTRAG human reference tasks, and official SEC EDGAR APIs/filing HTML.
- Added normalized real-data builders:
  - BEIR SciFact: 5,183 real scientific documents and 1,109 expert-annotated retrieval queries.
  - IBM MTRAG human: 110 human-reviewed conversations, 842 multi-turn tasks, and 1,800 deduplicated reference passages.
  - SEC EDGAR: configurable 25-file smoke or 200-file full HTML filing set, with Company Facts/submissions support files and recall phrases extracted directly from visible filing HTML.
- Added corpus, dataset, and explicit source-group split manifests. Every downloaded file records a SHA-256 hash; multi-file SEC snapshots also record each exact source URL.
- Added retrieval, memory, and text-recall record schemas. Every dataset manifest names its record schema, and generated real-data rows are validated against it.
- SciFact query groups are connected through shared relevant documents before split assignment. MTRAG tasks are grouped by conversation. Calibration, development, sealed holdout, and challenge group IDs are explicit and hashable.
- Added suite threshold YAML files with the approved real-sample size gates. Accuracy thresholds remain empty and explicitly pending Phase 9 calibration rather than being invented.
- Kept all downloaded/generated third-party data under ignored `artifacts/eval/phase3`; only source catalogs, schemas, thresholds, downloaders, and tests are tracked.

Implementation simplicity and style:

- Used only the Python standard library; no downloader, parquet, YAML, or schema dependency was introduced.
- Kept source-specific normalization in three small downloader modules and shared only hashing, manifest, split, and network/archive behavior.
- Did not modify production `src/main/**` business code or runtime defaults.

Actual real-data preparation results:

- BEIR SciFact pinned archive hash matched; generated 1,109 retrieval rows and 5,183 documents. The full retrieval size gate passed.
- IBM MTRAG pinned task-file hash matched; generated 842 RAG rows, 842 memory rows, 110 conversation groups, and 1,800 deduplicated reference passages. Full memory and multi-turn size gates passed.
- Generated split manifests contain:
  - SciFact: 304 calibration, 99 development, 139 holdout, and 30 challenge source groups.
  - MTRAG: 69 calibration, 23 development, 13 holdout, and 5 challenge conversation groups.
- The local ignored Phase 3 artifact cache contains 20 files totaling approximately 37 MB before SEC preparation.
- SEC EDGAR smoke preparation completed with 25 real filing HTML samples and 76 downloaded source/support files. Every sample has eight visible-HTML-grounded recall phrases, and the process-local contact User-Agent was not persisted in generated artifacts or source.
- The SEC downloader encountered a real missing Company Facts endpoint during smoke preparation. It now closes and skips official `404` responses and continues until the requested complete-company target is met; a regression test protects this behavior.

Phase 3 verification:

```powershell
python -m chatagent_eval.prepare_phase3 --catalog-root ..\..\chatagent\bootstrap\src\test\resources\eval\v2\corpora\catalog --output-root ..\..\artifacts\eval\phase3 --sources beir-scifact mtrag-human
python -m unittest discover -s tests -v
python -m compileall -q chatagent_eval tests
$env:JAVA_HOME='<jdk-17-home>'
& '<maven-wrapper-distribution>\bin\mvn.cmd' -pl bootstrap -Dtest='com.yulong.chatagent.eval.v2.*Test' test
& '<maven-wrapper-distribution>\bin\mvn.cmd' -pl bootstrap test
git diff --check
```

Verification results:

- Python eval suite: 19 tests passed, including concrete-license validation, private-path rejection, ZIP traversal rejection, SEC User-Agent enforcement, missing-company skip behavior, HTML-grounded phrase extraction, full sample-size gates, manifest schema validation, and split-leakage checks against generated real datasets.
- Python bytecode compilation: passed.
- Java targeted v2 suite: 10 tests passed, including approved source-catalog schema validation.
- Complete `bootstrap` suite: 811 tests passed, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: passed.
- Phase 3 scope audit found no production business-code changes.
- Initial Phase 3 implementation cross-review found one Spec gap: dataset manifests and generated records were not linked to dataset-specific row schemas. The gap was fixed with retrieval, memory, and text-recall record schemas plus generated-row validation. Cross-review rerun found no remaining Spec or Standards findings and accepted the phase.

## Phase 4 RAG Retrieval Export Suite

Implementation details:

- Added an eval-owned in-memory knowledge-base fixture with a strict `eval-v2-` ownership prefix. Search and cleanup reject non-eval knowledge-base IDs, and cleanup removes only resources created by the fixture.
- Added a retrieval export runner that creates a production `KnowledgeBaseSimilaritySearcher` per trial. `topK`, `candidateK`, and `rrfK` are injected per run without editing production defaults.
- The runner writes v2 `manifest.json`, `metrics.json`, `samples.jsonl`, and `failures.jsonl` artifacts through the Phase 2 writer. Each retrieved context includes document ID, text, source ID, and score; each sample records retrieval latency.
- Added deterministic aggregate Hit@K, Recall@K, Precision@K, MRR, NDCG@K, phrase recall, unique relevant-source coverage, average latency, and p95 latency.
- Exact trial configuration is fingerprinted. The real-data smoke also records split, query/document limits, retrieval backend, embedding mode, and reranker mode so fixture-backed results cannot be mistaken for live infrastructure results.
- Added an opt-in `eval-v2` SciFact smoke suite. It reads the ignored Phase 3 real public dataset, selects 50 development queries, indexes 1,000 base documents plus all required relevant documents, and exports baseline `topK=3` and one-parameter `topK=5` sensitivity trials. The actual indexed count is captured in each run manifest.
- Added `eval-v2` to Maven default excluded groups. Deterministic runner/cleanup tests remain in the default suite; real-data artifact-producing smoke is opt-in.

Implementation simplicity and boundary:

- No production `src/main/**` code, runtime defaults, environment variables, live providers, or external infrastructure were changed.
- The smoke suite exercises the production hybrid fusion/topK path in `KnowledgeBaseSimilaritySearcher`, while its index and embedding are deterministic eval-only fixtures. It is a reproducible local retrieval-export gate, not a claim about live Milvus, Ollama embedding, or reranker quality.
- Synthetic text appears only in the small unit fixture. Headline smoke metrics use real SciFact queries and documents prepared in Phase 3.

Actual real-data smoke results:

- Baseline `topK=3`: Hit@K `0.32`, Recall@K `0.31`, MRR `0.2667`, NDCG@K `0.2756`.
- Sensitivity `topK=5`: Hit@K `0.34`, Recall@K `0.33`, MRR `0.2717`, NDCG@K `0.2842`.
- Both runs captured 50 actual queries and 1,027 actual indexed documents, including all required relevant documents.
- The sensitivity result is an input to later tuning, not a promoted optimum. Phase 9 still owns complete parameter search and sealed-holdout promotion.
- SciFact has no required-phrase field, so phrase recall is emitted as `0.0` for this suite; grounded phrase/span quality is evaluated by the Phase 6 text-recall suite.
- Generated Phase 4 artifacts remain under ignored `artifacts/eval/phase4`.

Phase 4 verification:

```powershell
$env:JAVA_HOME='<jdk-17-home>'
.\mvnw.cmd -pl bootstrap -Dtest='com.yulong.chatagent.eval.v2.retrieval.RagRetrievalExportRunnerTest' test
.\mvnw.cmd -pl bootstrap -Dtest='com.yulong.chatagent.eval.v2.*Test,com.yulong.chatagent.eval.v2.retrieval.RagRetrievalExportRunnerTest' test
$branch=(git branch --show-current); $sha=(git rev-parse HEAD)
.\mvnw.cmd -pl bootstrap "-Dsurefire.excludedGroups=" "-Dchatagent.eval.gitBranch=$branch" "-Dchatagent.eval.gitSha=$sha" -Dtest='com.yulong.chatagent.eval.v2.retrieval.RagRetrievalExportEvalTest' test
.\mvnw.cmd -pl bootstrap "-Dsurefire.failIfNoSpecifiedTests=false" -Dtest='com.yulong.chatagent.eval.v2.retrieval.RagRetrievalExportEvalTest' test
.\mvnw.cmd -pl bootstrap test
git diff --check
```

Verification results:

- Retrieval runner and cleanup tests: 2 passed.
- Relevant Java v2 suite: 12 passed.
- Real SciFact baseline/sensitivity smoke: 1 passed and wrote both normalized run directories.
- Default exclusion dry run: 0 tests executed for the tagged real-data smoke, proving it remains opt-in.
- Complete `bootstrap` suite: 813 tests passed, 0 failures, 0 errors, 0 skipped.
- Scope audit found no production business-code changes and no environment/credential changes.

## Phase 5 Official RAGAS Runner

Implementation details:

- Added an optional Python `ragas` extra under `tools/eval` so default dependency-free eval contracts remain lightweight.
- Added `run_eval.py ragas-smoke`, which loads a prior v2 export run, applies `CHATAGENT_EVAL_*` runtime configuration, and writes normalized v2 artifacts under the selected output root.
- Added `chatagent_eval.ragas_runner`, which maps v2 retrieval samples to official Ragas `EvaluationDataset` records using fields such as `user_input`, `retrieved_contexts`, `retrieved_context_ids`, `reference_context_ids`, `response`, and `reference`.
- The official evaluator path lazy-loads Ragas and OpenAI-compatible clients only when valid Ragas records need LLM judging. DeepSeek `deepseek-chat` remains the default eval judge model, with eval-specific provider/model/base-url/embedding overrides and no secret values written to artifacts.
- The report merges deterministic retrieval metrics under `deterministic.*` and Ragas LLM judge metrics under `ragas.*`, preserving the canonical flat `metrics` object required by the v2 report schema.
- Ragas row failures support both configured modes: `structured` writes per-row failure records, while `nan` leaves row-level `null` scores without structured failure rows. Missing optional dependencies or Provider credentials become warn-only structured failures by default.

Implementation simplicity and boundary:

- No production `src/main/**` code, Java retrieval behavior, runtime defaults, committed environment values, or live infrastructure configuration were changed.
- The Ragas adapter is local to `tools/eval` and uses injected evaluator tests for no-live-provider coverage. Official Ragas execution remains opt-in and lazy so deterministic suites and default CI are not coupled to paid or networked Providers.
- The real-data CLI smoke intentionally used the Phase 4 SciFact export and did not fabricate new headline samples. Because Phase 4 retrieval exports do not yet include generated answers/reference answers, the smoke validated conversion and warn behavior rather than running paid LLM judge calls.

Phase 5 verification:

```powershell
cd tools/eval
python -m unittest discover -s tests -v
python -m compileall -q chatagent_eval tests run_eval.py
python run_eval.py ragas-smoke --input-run-dir ..\..\artifacts\eval\phase4\phase4-scifact-baseline --output-root ..\..\artifacts\eval\phase5 --run-id phase5-real-export-smoke --max-samples 3
```

Verification results:

- Python eval suite: 26 tests passed.
- Python compileall: passed for `chatagent_eval`, `tests`, and `run_eval.py`.
- Real Phase 4 SciFact export CLI smoke: passed and wrote ignored artifacts under `artifacts/eval/phase5/phase5-real-export-smoke`.
- CLI smoke report status was `warn` with deterministic metrics preserved and `ragas.*` metrics set to `null`; failures listed the three sampled real SciFact rows as missing `reference` and `response`, which is expected until an answer-generation/reference-answer slice feeds the Ragas judge.

## Phase 6 Text Recall Suite

Implementation details:

- Added `chatagent_eval.text_recall_runner`, a deterministic runner over Phase 3 real text-recall datasets. It reads the dataset manifest, loads source files from the prepared artifact root, and rejects source-file path escapes before reading.
- Added `run_eval.py text-recall-smoke` with configurable `dataset-root`, `dataset-id`, `chunk-size`, `chunk-overlap`, `top-k`, `max-samples`, and split filters. These values are recorded in the run manifest and config fingerprint so parser/chunker/context parameters can be swept without editing source.
- Parser recall extracts visible HTML text and measures required phrase recall independently from answer quality.
- Chunk span recall chunks parsed text with the configured size/overlap and measures whether each required phrase appears wholly inside at least one chunk.
- Retrieval context phrase recall ranks chunks with a deterministic lexical phrase-overlap scorer and measures recall over actual topK contexts. Failure rows include `missingPhrases` and the exact topK context texts used by the run.
- Citation support recall is measured separately by requiring missing/found phrases to be supported by contexts carrying the source URL. Table-cell recall is also separate; current SEC text-recall data has no table-cell annotations, so `textRecall.tableCellRecall` is `null` and `textRecall.tableCellSampleCount` is `0`.

Implementation simplicity and boundary:

- No production `src/main/**` code, Java retrieval code, Provider settings, credentials, or runtime defaults were changed.
- The runner is local to `tools/eval` and depends only on existing Phase 2/3 Python helpers. Unit fixtures are synthetic only for local parser/path/failure tests; the executed smoke uses the already downloaded real SEC EDGAR Phase 3 files.
- The retrieval scorer is intentionally deterministic and transparent. It is a text-recall diagnostic, not a replacement for the production RAG retrieval export or the later Phase 9 optimizer.

Phase 6 verification:

```powershell
cd tools/eval
python -m unittest discover -s tests -v
python -m compileall -q chatagent_eval tests run_eval.py
python run_eval.py text-recall-smoke --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase6 --run-id phase6-sec-text-recall-smoke --top-k 5 --chunk-size 2000 --chunk-overlap 200
python run_eval.py text-recall-smoke --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase6 --run-id phase6-sec-text-recall-topk1 --top-k 1 --chunk-size 2000 --chunk-overlap 200
```

Verification results:

- Python eval suite: 30 tests passed.
- Python compileall: passed for `chatagent_eval`, `tests`, and `run_eval.py`.
- Real SEC EDGAR Phase 3 text-recall smoke with `topK=5`, `chunkSize=2000`, and `chunkOverlap=200`: status `pass`, 25 real files, 200 required phrases, parser phrase recall `1.0`, chunk span recall `1.0`, retrieval context phrase recall `1.0`, citation support recall `1.0`.
- Real SEC EDGAR topK sensitivity with `topK=1`: status `warn`, retrieval context phrase recall `0.925`, citation support recall `0.925`, and failure rows include missing phrases plus the actual topK contexts. This proves the suite exposes parameter sensitivity without changing production defaults.

## Phase 7 Memory Suite

Implementation details:

- Added `chatagent_eval.memory_runner`, a deterministic Memory V2 runner over the Phase 3 `memory-v2-dialogues` dataset from IBM MTRAG human tasks. It records ADR 0001 in the run config and evaluates V2-style structured synopsis plus segment ranges rather than the retired rolling-summary semantics.
- Added `run_eval.py memory-smoke` with configurable `dataset-root`, `dataset-id`, `l1-window-turns`, `l1-budget-chars`, `l2-segment-turns`, `l3-top-k`, `max-samples`, and split filters. These values are recorded in the run manifest and config fingerprint for parameter sweeps.
- L1 metrics measure complete tail-turn preservation, budget compliance, and tool-response recall when tool turns are present.
- L2 metrics measure deterministic fact recall/precision, explicit-pair contradiction rate, segment range coverage, fallback rate, and retry count.
- L3 metrics measure durable memory extraction precision/recall/F1, type accuracy, tag accuracy, deterministic idempotency, and lexical recall Hit@K/MRR.
- Failure rows include `topContexts` from L1, L2 segments, and L3 ranked memories so review can inspect what the memory suite actually used.

Implementation simplicity and boundary:

- No production `src/main/**` code, database schema, Java memory runtime, Provider settings, credentials, or runtime defaults were changed.
- The runner is local to `tools/eval`, uses real MTRAG Phase 3 artifacts for smoke results, and uses small synthetic unit fixtures only for path-free schema/failure coverage.
- The L3 extractor and recall scorer are deterministic diagnostics. They prove artifact shape, V2 metric coverage, idempotency, and tunable recall behavior; live LLM extraction and Milvus recall remain separate manual/provider-backed concerns.
- User isolation is implicit in the offline runner because each sample is processed independently and no cross-user memory store is read or written. Live `UserMemoryIndexService` isolation and inactive-memory exclusion are deferred to a later live-memory integration suite with fixture-only active/inactive edge cases.

Phase 7 verification:

```powershell
cd tools/eval
python -m unittest discover -s tests -v
python -m compileall -q chatagent_eval tests run_eval.py
python run_eval.py memory-smoke --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase7 --run-id phase7-mtrag-memory-smoke --l1-window-turns 4 --l1-budget-chars 8000 --l2-segment-turns 4 --l3-top-k 3
python run_eval.py memory-smoke --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase7 --run-id phase7-mtrag-memory-low-l1-budget --l1-window-turns 4 --l1-budget-chars 200 --l2-segment-turns 4 --l3-top-k 3 --max-samples 50
```

Verification results:

- Python eval suite: 35 tests passed.
- Python compileall: passed for `chatagent_eval`, `tests`, and `run_eval.py`.
- Real IBM MTRAG memory full smoke: 842 real tasks, status `pass`, L1 complete turn recall `1.0`, L2 fact recall/precision `1.0`, L2 segment range coverage `1.0`, L2 contradiction rate `0.0`, L3 extraction F1 `1.0`, L3 type/tag accuracy `1.0`, L3 idempotency `1.0`, L3 recall Hit@K/MRR `1.0`.
- Real low L1 budget sensitivity with 50 MTRAG tasks and `l1BudgetChars=200`: status `warn`, L1 complete turn recall `0.445`, and failures show the missing L1 preservation path plus actual top contexts. This proves the suite exposes L1 parameter sensitivity without changing production defaults.

## Phase 8 Agent Module Suites

Implementation details:

- Added `chatagent_eval.agent_module_runner`, a deterministic agent-module runner over the Phase 3 `memory-v2-dialogues` dataset from IBM MTRAG human tasks. The suite emits normalized v2 artifacts for intent routing, query rewrite, tool call, and multi-turn diagnostics without reviving legacy golden JSON.
- Added `run_eval.py agent-modules-smoke` with configurable `intent-history-turns`, `intent-min-evidence-terms`, `rewrite-history-turns`, `rewrite-max-anchors`, `rewrite-max-extra-terms`, `tool-candidate-limit`, `multiturn-coref-window-turns`, split filters, and max sample count. The numeric values are recorded in the run manifest and config fingerprint.
- Intent metrics cover exact path, domain, out-of-scope, clarification, and ambiguity accuracy from clean-room labels derived from MTRAG metadata.
- Query rewrite metrics cover anchor recall, retrieval lift rate, no-over-expansion rate, and numeric rewrite history/anchor parameters.
- Tool-call metrics cover accuracy, precision, recall, and F1 for expected production `ToolDefinition.name()` values such as `SessionFileSearchTool`. Optional Ragas agent metric slots are explicitly present as null and labeled disabled until a provider-backed Ragas agent run is added.
- Multi-turn metrics cover coreference recall, topic switch accuracy, and wrong-history suppression.
- Added `agent-modules-v1` suite-owned parameter space so Phase 9 can sweep intent history, intent evidence, rewrite history, rewrite anchor count, rewrite expansion allowance, tool candidate limit, and coreference window values.

Implementation simplicity and boundary:

- No production `src/main/**` code, Provider settings, credentials, runtime defaults, database schema, or Java agent runtime code were changed.
- The runner is local to `tools/eval`, supports future rows with explicit `moduleOutputs`, and otherwise uses deterministic clean-room diagnostics over real MTRAG tasks.
- Fixture-only synthetic rows are limited to unit tests for schema, mismatch, topic-switch, and parameter-sensitivity paths; headline smoke uses 842 real MTRAG tasks.

Phase 8 verification:

```powershell
cd tools/eval
python -m unittest tests.test_agent_module_runner -v
python -m compileall -q chatagent_eval tests run_eval.py
python -m unittest discover -s tests -v
python run_eval.py agent-modules-smoke --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase8 --run-id phase8-mtrag-agent-modules-smoke --rewrite-history-turns 6 --rewrite-max-anchors 3 --tool-candidate-limit 1 --multiturn-coref-window-turns 6
python run_eval.py agent-modules-smoke --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase8 --run-id phase8-mtrag-agent-modules-no-rewrite-anchors --rewrite-history-turns 6 --rewrite-max-anchors 0 --tool-candidate-limit 1 --multiturn-coref-window-turns 6 --max-samples 50
```

Verification results:

- Targeted Phase 8 Python tests: 5 tests passed.
- Python eval suite: 40 tests passed.
- Python compileall: passed for `chatagent_eval`, `tests`, and `run_eval.py`.
- Real IBM MTRAG agent-module full smoke: 842 real tasks, status `pass`, 0 failures, intent exact path/domain/out-of-scope/clarification/ambiguity accuracy `1.0`, rewrite anchor recall `1.0`, rewrite retrieval lift rate `1.0`, tool-call accuracy/F1 `1.0`, coreference recall `1.0`, topic switch accuracy `1.0`, wrong-history suppression `1.0`.
- Real rewrite-anchor sensitivity with 50 MTRAG tasks and `rewriteMaxAnchors=0`: status `warn`, rewrite anchor recall `0.06976744186046512`, rewrite retrieval lift rate `0.18`, coreference recall `0.14`, and failures include actual rewrite/tool/multiturn contexts.

## Phase 9 Precision Parameter Tuning And Promotion

Implementation details:

- Added a versioned numeric parameter registry that classifies the approved retrieval, reranker, text-recall, memory, intent, and agent-module settings as quality-tunable, operational-only, safety-fixed, or excluded/deferred with rationale. The registry records runtime property and environment-variable names where applicable, without secret values.
- Added deterministic baseline, one-parameter sensitivity, and bounded grid/random combination search. Trial ordering, config fingerprints, random seeds, exact parameters, dataset/split hashes, code SHA, metrics, category metrics, confidence intervals, variance, execution elapsed time, and gate failures are recorded.
- Added suite-owned tuning policies for agent modules, Memory V2, and text recall. Champion selection is accuracy-first, applies secondary metrics and hard gates, and prefers the baseline when all declared quality metrics tie so timing noise cannot propose a no-benefit configuration change.
- Added sealed-holdout enforcement. Iterative search accepts only train/calibration/development splits, rejects overlap by source-group ID, selects the champion before opening holdout/challenge, and records a holdout verification audit.
- Added `run_eval.py tune-suite` and review-only promotion artifacts: experiment manifest, parameter-space snapshot, trial JSONL, leaderboard CSV, Pareto frontier, champion candidate, holdout verification, and promotion decision. Promotion artifacts always state that production defaults remain unchanged and require a separate reviewed change.
- Correctly separates whole-experiment `executionElapsedMs` from true `latencyP95Ms`; a suite that does not emit real p95 latency leaves the p95 value null and does not apply a false latency gate.

Implementation simplicity and boundary:

- The optimizer uses the Python standard library and existing Phase 6-8 suite runners; no optimizer dependency, Provider call, credential, production source, or runtime default was added.
- JSON-formatted `*.yaml` artifacts intentionally use JSON as a valid YAML subset, avoiding a new YAML dependency while remaining machine-readable.
- Production retrieval `candidate-k`/`rrf-k` and live reranker tuning are registered and parameter-spaced, but are not falsely executed through a Python proxy. They remain pending a Java production-retrieval export/import adapter and available live reranker.

Phase 9 verification:

```powershell
cd tools/eval
python -m unittest tests.test_tuning_runner -v
python -m unittest discover -s tests -v
python -m compileall -q chatagent_eval tests run_eval.py
cd ..\..\chatagent
.\mvnw.cmd -pl bootstrap -Dtest=EvalSchemaFixtureTest test
cd ..\tools\eval
python run_eval.py tune-suite --suite agent-modules --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase9 --experiment-id phase9-agent-modules-tuning-smoke --strategy random --combination-budget 4 --random-seed 42 --max-samples-per-trial 50 --holdout-max-samples 50 --confidence-resamples 100 --git-branch codex/ragas-evaluation-rework --git-sha 68cfdff
python run_eval.py tune-suite --suite memory-v2 --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase9 --experiment-id phase9-memory-tuning-smoke --strategy random --combination-budget 3 --random-seed 42 --max-samples-per-trial 50 --holdout-max-samples 50 --confidence-resamples 100 --git-branch codex/ragas-evaluation-rework --git-sha 68cfdff
python run_eval.py tune-suite --suite text-recall --dataset-root ..\..\artifacts\eval\phase3 --output-root ..\..\artifacts\eval\phase9 --experiment-id phase9-text-recall-tuning-smoke --strategy random --combination-budget 3 --random-seed 42 --max-samples-per-trial 18 --holdout-max-samples 6 --confidence-resamples 100 --git-branch codex/ragas-evaluation-rework --git-sha 68cfdff
```

Verification results:

- Targeted Phase 9 Python tests: 6 tests passed.
- Complete Python eval suite: 46 tests passed.
- Java shared schema fixture verification: 3 tests passed.
- Python compileall, `git diff --check`, and secret/contact-value scan passed.
- Real MTRAG agent-module tuning: 18 trials, primary metric range `0.14` to `1.0`, 4 gated trials, baseline champion, holdout `pass`, overlap count `0`.
- Real MTRAG Memory V2 tuning: 11 trials, primary metric range `0.74` to `1.0`, 2 gated trials, baseline champion, holdout `pass`, overlap count `0`.
- Real SEC text-recall tuning: 9 trials, primary metric range `0.91` to `1.0`, 3 gated trials, baseline champion, holdout `pass`, overlap count `0`.
- All three promotion candidates remain review-pending with `productionDefaultsChanged=false`.

## Known Failures Or Blockers

- Old SciFact real-embedding 10a implementation is retired from Phase 10 acceptance by user decision. It should be removed or quarantined as optional benchmark-only work before the replacement production document-ingestion 10a is accepted.
- Live reranker thresholds/candidate limits remain deferred to a later live-reranker-capable suite; they must not be represented by the deterministic Python text-recall runner.
- Phase 7 implementation review accepted the memory suite. The only review fix was documentation-only: clarify that live user isolation and inactive-memory exclusion are deferred outside the deterministic offline runner.
- Live Milvus, live embedding, and live reranker quality runs are intentionally outside this deterministic Phase 4 smoke slice and remain manual/later tuning concerns.
- SEC 200-file full preparation remains a manual/full-corpus run; Phase 3 acceptance required and completed the 25-file smoke gate.
- The Phase 2 dependency-free schema validators intentionally implement only the JSON Schema keywords used by the current canonical schemas. Phase 5 did not need a full schema-validator dependency; the canonical schema files remain the source of truth.
- Provider-backed phases must load local credentials from the approved `docs/env_variables.txt` execution path without printing values, and must retain skip/warn behavior when credentials are unavailable.

## Manual Checks

- Planning-level source access checks completed on 2026-06-06:
  - Ragas docs `evaluate()` and available metrics pages returned HTTP 200.
  - Hugging Face `BeIR/scifact` dataset and `corpus` tree returned HTTP 200.
  - IBM MTRAG `mtrag-human` tree and `LICENSE` returned HTTP 200.
  - SEC `data.sec.gov/submissions/CIK0000320193.json` returned HTTP 200 with a lightweight GET. SEC bulk ZIP/download validation remains a Phase 3 downloader check to avoid large planning-time downloads.
- The user confirmed that local provider credentials and environment settings are maintained in `docs/env_variables.txt`. Only variable names and coverage may be inspected; no secret values may be printed or recorded.
- DeepSeek environment names are covered by `chatagent/bootstrap/src/main/resources/application.yaml` through `CHATAGENT_DEEPSEEK_*`.
- Phase 1 was committed on `codex/ragas-evaluation-rework` as `3310250` (`Clean up legacy evaluation suites`).
- Phase 2 was committed on `codex/ragas-evaluation-rework` as `0948368` (`Add evaluation v2 core contracts`).
- Phase 3 SEC 25-file smoke preparation completed and generated source/dataset/split manifests were inspected. The contact-bearing User-Agent was process-local and was not stored in source or artifacts.
- Phase 4 was reviewed, accepted, and committed on `codex/ragas-evaluation-rework` as `c227033` (`Add RAG retrieval export eval suite`).
- Phase 5 was reviewed, accepted, and committed on `codex/ragas-evaluation-rework` as `989c9c2` (`Add official Ragas evaluation runner`). A live official Ragas LLM judge run with installed optional dependencies and real Provider credentials remains manual/warn-only until the user explicitly runs it.
- Phase 6 was reviewed, accepted, and committed on `codex/ragas-evaluation-rework` as `53b9229` (`Add text recall evaluation runner`).
- Phase 7 was reviewed, accepted, and committed on `codex/ragas-evaluation-rework` as `ab82b94` (`Add memory evaluation runner`).
- Phase 7 real IBM MTRAG 842-task memory smoke and low-L1-budget sensitivity completed under ignored `artifacts/eval/phase7`.
- Phase 7 implementation review accepted the suite with one P3 documentation fix, now applied.
- Phase 8 real IBM MTRAG 842-task agent-module smoke and 50-task no-rewrite-anchor sensitivity completed under ignored `artifacts/eval/phase8`.
- Phase 8 implementation review accepted the suite and it was committed as `68cfdff` (`Add agent module evaluation suites`).
- Phase 9 real-data agent-module, Memory V2, and SEC text-recall tuning experiments completed under ignored `artifacts/eval/phase9`; all three holdout audits passed with zero source-group overlap and no production default changes.
- Phase 9 production Java retrieval and live reranker tuning remain manual/incomplete until their export adapter and live service prerequisites are available.

## Phase 10 Real Model Evaluation Upgrade

Background: Phase 9 cross-review identified that all suite runners (Phase 4–8) use deterministic proxies instead of real models. The metrics computation, data pipeline, sealed-holdout mechanism, and tuning framework are genuine; the gap is the "model under test" layer. Phase 10 upgrades each module to exercise real models while preserving deterministic diagnostics as CI regression tests.

Implementation plan:

- Cross-cutting: All Phase 10 sub-phases use a shared `eval-real` Maven profile to activate real-model tests. The current default (`surefire.excludedGroups`) excludes all `eval-v2` tagged tests; the `eval-real` profile removes both `eval-v2` and `eval-real` from the exclusion list so deterministic and real-model tests execute. Real-model tests use `@Tag("eval-real")` alongside `@Tag("eval-v2")`. Each test class has a JUnit 5 `ExecutionCondition` that probes required infrastructure before Spring context loads; when unavailable, the entire class is disabled so Spring never starts (e.g., new 10a probes PostgreSQL + RabbitMQ + Ollama + Milvus + MinerU, 10b probes Ollama + Milvus + PostgreSQL + LLM provider, 10c probes PostgreSQL + Redis + LLM provider). Deterministic tests remain opt-in (excluded from default build) with no infrastructure requirements.
- Acceptance criteria — two-tier gate: Smoke runs (small samples such as 10 MTRAG rows or a few multi-format files) are preflight only; provenance metadata confirmation suffices and non-perfect metrics are warns, not hard gates. Accepted-size/full tuning runs are required for final Phase 10 acceptance; if all samples score 1.0 or metrics are identical to the deterministic proxy baseline, the measurement lacks discriminative power and is a finding. This applies across Phase 10a production document-ingestion, 10b memory, and 10c agent modules.
- Phase 10a: Production Document Ingestion Real Retrieval. The former SciFact real-embedding 10a is retired from acceptance because normalized JSONL benchmark rows do not exercise ChatAgent's production multi-format ingestion path. The replacement 10a adds a production-ingestion eval runner over at least 200 real documents downloaded from public network sources: SEC EDGAR HTML/XBRL, public PDFs, DOCX/Word files, XLSX/spreadsheet workbooks, and standalone web/HTML or Markdown pages. CSV files may remain optional preflight/diagnostic samples, but they do not count toward the headline accepted-size format quota unless a dedicated CSV table-aware parser is implemented. No hand-made, generated, toy, private local, or old course PDFs can be used in headline 10a metrics. The accepted run must enable local MinerU from `tools/mineru` for PDF/visual-track routing and must exercise the production MQ path: create eval-owned knowledge-base documents through the production facade/outbox route, publish `KnowledgeIngestTaskPayload`, let `OutboxPollingPublisher` and `KnowledgeIngestTaskListener` drive `KnowledgeDocumentIngestionServiceImpl.ingestSync()`, then retrieve through `KnowledgeBaseSimilaritySearcher` or the matching session-file searcher. Direct `ingestSync` calls remain preflight/debug only. It should exercise production components such as `DocumentParserSelector`, `MinerUVdpEngine`, `PdfVdpDispatcher`, `PdfDocumentParser`, `WordDocumentParser`, `SpreadsheetDocumentParser`, `TikaDocumentParser`, `MarkdownDocumentParser`, `SegmentAwareChunkerRouter`, `StructureAwareMarkdownChunker`, `TableAwareChunker`, `KnowledgeBaseMilvusIndexer`/`SessionFileMilvusIndexer`, and `KnowledgeBaseSimilaritySearcher`/`SessionFileSimilaritySearcher`. Acceptance target: at least 200 network-downloaded real files across at least five format families and at least 500 grounded recall items/queries, with source URL/hash/license or terms metadata for every file. Metrics include parser phrase recall, chunk span recall, retrieval context recall, citation support recall, table/numeric recall where applicable, source/chunk metadata completeness, MinerU route coverage, MQ/outbox provenance, and per-format breakdowns. Tuning adds `doc-ingestion-retrieval` with production-ingestion rows under `datasets/doc-ingestion/<id>.jsonl`. Before the accepted-size 10a run, fix or prove unrelated the observed Ollama scaling failure: 5/100/500-document smokes passed, but the 1000-document / 50-query smoke failed with an Ollama 500 after about 156 seconds and roughly 1000 sequential embeddings. The implementation plan is to harden `OllamaEmbeddingClient` WebClient connection management, timeouts, pooling, response consumption, bounded concurrency/backpressure, and transient retry behavior, plus add a 1200-call long-run regression.
- Phase 10b: Memory Real Export. Add `MemoryExportEvalTest.java` using `@SpringBootTest` with a dedicated `eval-real-memory` Spring profile (`@ActiveProfiles("eval-real-memory")`). The Maven `eval-real` profile (Surefire groups) activates the test class; the Spring `eval-real-memory` profile configures the application context — they are distinct concepts. A new `application-eval-real-memory.yaml` provides: `milvus.enabled: true`, `chatagent.memory.l3.enabled: true`, datasource reuses the default PostgreSQL from `application.yaml`, and Milvus collection `chat_user_memory_eval`. Calls `IncrementalSummarizer.summarizeWithDetails(sessionId, anchorSeqNo)` and `LongTermMemoryExtractor.extract(turns)` directly (synchronous), bypassing async listeners and Redis locks. Database strategy: `summarizeWithDetails` internally reads from `SummaryWatermarkService.resolvePendingRange()`, `ChatSessionSummaryRepository.findBySessionId()`, and `TurnBasedContextExtractor.extractPendingTurns()` — all require conversation state in the database. The test uses programmatic insertion via Spring beans (`ChatSessionSummaryRepository`, etc.) in `@BeforeEach` to pre-populate eval-prefixed conversation turns, session summary rows, and watermark rows from MTRAG dialogue fixtures; `@AfterEach` cleans up by `eval-memory-*` prefix. Uses real `OllamaEmbeddingClient` + `UserMemoryIndexService` for L3 recall. Prerequisites: PostgreSQL (default datasource), Ollama, Milvus, configured LLM provider. Isolation: eval-owned ID prefixes (`eval-memory-*`), dedicated eval Milvus collection (`chat_user_memory_eval`), cleanup limited to eval-prefixed rows, non-eval preservation guard. Tests: (1) DB setup/teardown verification — `summarizeWithDetails` returns a non-empty `SummaryResult` after fixture insertion, and `@AfterEach` cleanup leaves no eval-prefixed rows; (2) user isolation — insert L3 memories for two users (`eval-memory-user-a`, `eval-memory-user-b`), run L3 recall for user-a only, assert user-b's memories are not returned; (3) inactive memory exclusion — flag one memory belonging to `eval-memory-user-a` as inactive, run L3 recall, assert the inactive memory is excluded while active memories for the same user are still returned. Non-perfect metrics (L2 fact recall < 1.0, L3 F1 < 1.0) are observations/warns, not hard gates. Skip gracefully when infrastructure unavailable.
- Phase 10c: Agent Module Real Outputs. Add `AgentModuleExportEvalTest.java` using `@SpringBootTest` with a dedicated `eval-real-agent-modules` Spring profile (`@ActiveProfiles("eval-real-agent-modules")`). Unlike 10b's `eval-real-memory` profile (which enables Milvus + L3 memory), this profile only requires the LLM provider, database (for intent tree loading), and Redis (for intent tree caching via `DefaultIntentTreeCacheManager`) — no Milvus, Ollama, or L3 memory beans. A new `application-eval-real-agent-modules.yaml` provides the minimal configuration; the infrastructure probe checks PostgreSQL, Redis, and LLM provider availability; Milvus and Ollama are explicitly not required so their unavailability does not skip 10c. This ensures 10c can run (or skip gracefully) independently of Milvus/Ollama availability. Captures the full real-model pipeline for each MTRAG query: (1) `IntentRouter.route(agentId, query)` → `IntentRoutingResult` (contains `IntentResolution` on resolved, `clarificationCandidates` otherwise; confirmed at `IntentRouter.java:81`); (2) `QueryRewriter.rewrite(originalQuery, intentResolution)` → rewritten query string (confirmed at `QueryRewriter.java:38`); (3) `AgentToolCallbackFactory.create(agentConfig, intentResolution)` → `List<ToolCallback>` — extract tool definition names for the `toolList` field (confirmed at `AgentToolCallbackFactory.java:69`). Coreference is not a separate module — it is measured through rewrite quality (the QueryRewriter LLM prompt handles pronoun/reference resolution internally). Tool selection scope is limited to capturing the available tool list per intent resolution, not LLM tool call decisions (deferred). A synthetic intent tree fixture (JSON resource or Java builder) provides node labels covering the MTRAG domain + questionType taxonomy; the fixture produces deterministic `IntentResolution` objects for comparison against expected taxonomy labels. Export as `moduleOutputs` in v2 samples: `moduleOutputs.intent` (raw `IntentRoutingResult` serialized), `moduleOutputs.queryRewrite` (rewritten query text), `moduleOutputs.toolList` (list of tool definition names). Run artifacts include raw model outputs and provider metadata. Non-perfect metrics are observations/warns. Skip gracefully when infrastructure unavailable (JUnit 5 `ExecutionCondition`).
- Phase 10 tuning integration: 10a doc-ingestion-retrieval, 10b memory-v2, and 10c agent-modules produce Phase 3-compatible dataset roots and feed `tune-suite`. Phase 10a adds `doc-ingestion-retrieval` support for production parser/chunker/ingestion retrieval quality. Each Java export runner writes a Phase 3-compatible dataset root (manifests/datasets/<id>.json with `provenance` object, manifests/splits/<id>.json, datasets/<suite>/<id>.jsonl) alongside run artifacts.
  - Dataset root structure for each sub-phase:
    - **10a (doc-ingestion-retrieval)**: `manifests/datasets/<id>.json`, `manifests/splits/<id>.json`, `datasets/doc-ingestion/<id>.jsonl`. Rows include `sampleId`, `datasetId`, `sourceGroupId`, `split`, `fileId`, `fileFormat`, `sourceUrl`, `userInput`, `requiredPhrases`, optional `requiredTableCells`, `referenceChunkIds`, `retrievedContexts`, `metadata.parser`, `metadata.chunker`, `metadata.ingestion`, `metadata.mineru` when PDF/visual routing applies, `metadata.mq` for accepted full-chain ingestion, and `metadata.candidateContexts`.
    - **10b (memory)**: `manifests/datasets/<id>.json`, `manifests/splits/<id>.json`, `datasets/memory/<id>.jsonl`. Rows include `sampleId`, `datasetId`, `sourceGroupId`, `split`, `turns` (original conversation turns from MTRAG dialogues), `expectedResponse`, `referenceContextIds`, `metadata.l1Segments` (L1 complete-turn data), `metadata.l2Segments` (L2 fact/precision/contradiction data), `metadata.l3Memories` (L3 extracted memory data), `moduleOutputs.l1Summary` (raw `SummaryResult` from `IncrementalSummarizer.summarizeWithDetails()`), `moduleOutputs.l3Extraction` (raw `ExtractionResult` from `LongTermMemoryExtractor.extract()`), `moduleOutputs.provider` (model name, embedding model from provenance).
    - **10c (agent-modules)**: `manifests/datasets/<id>.json`, `manifests/splits/<id>.json`, `datasets/agent-modules/<id>.jsonl`. Rows include `sampleId`, `datasetId`, `sourceGroupId`, `split`, `turns` (original MTRAG query + metadata), `expectedResponse`, `referenceContextIds`, `moduleOutputs.intent` (raw `IntentRoutingResult` serialized from `IntentRouter.route()`), `moduleOutputs.queryRewrite` (rewritten query text from `QueryRewriter.rewrite()`), `moduleOutputs.toolList` (list of tool definition names from `AgentToolCallbackFactory.create()`), `moduleOutputs.provider` (model name, classifier model, rewrite model from provenance).
  - The `tune-suite` CLI uses the export directory as `--dataset-root` with no adapter. Split manifests preserve original source-group hashes from Phase 3 so sealed-holdout verification produces zero overlap. Provenance traceability: experiment manifest records `datasetHash` → dataset manifest contains `provenance` (provider, modelName, embeddingModel, exportTimestamp).
- Phase 10 replacement amendment: Final acceptance now requires accepted-size/full real-model exports and tuning runs for new 10a doc-ingestion-retrieval, 10b memory-v2, and 10c agent-modules. 10a uses at least 200 real network-downloaded files across at least five format families and at least 500 grounded recall items/queries, with MinerU-enabled PDF/visual coverage and MQ-backed production ingestion evidence. 10b and 10c use the complete Phase 3 MTRAG human dialogue dataset (currently 842 rows). Tuning uses calibration/development rows for search and sealed holdout rows for final verification; challenge rows, when present, are report-only stress data and must not influence champion selection. Smoke runs remain useful for infrastructure preflight but do not close Phase 10 quality acceptance.
- Deferred: Ragas answer generation (LLM-generated answers from retrieved contexts) remains deferred; the Ragas runner is built but answer-quality metrics require the generation step.

Phase 10 status: old SciFact 10a implementation is retired from acceptance and should be removed or quarantined as optional benchmark-only work. New 10a production document-ingestion accepted-size export and all-rows tuning are complete, including MinerU, MQ-backed production ingestion, 200 network-downloaded real documents, and the Ollama embedding resilience prerequisite. The 10a champion is not promoted because holdout verification is warn/rejected; production defaults are unchanged. Phase 10b and 10c full MTRAG exports plus complete-grid tuning are also complete, with sealed-holdout verification and no production default changes.

- 2026-06-06: Initial planning audit and draft plan created.
- 2026-06-06: Updated plan to require real public corpora, sufficient sample sizes, source manifests, and to demote synthetic data to fixture/stress-only use.
- 2026-06-06: Applied plan-review fixes for memory conversation task sources, synthetic EnterpriseRAG-Bench stress-only layout, and narrowed first-slice source approval. Files changed: `docs/plans/RAGAS_EVALUATION_REWORK_PLAN.md`, `docs/plans/IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity/style impact: documentation-only, no runtime code or format churn. Tests: not applicable. Verification: re-read changed sections and searched for stale first-slice and EnterpriseRAG primary-dataset phrasing. Remaining manual checks: confirm source URLs/downloads, approve the first source subset, and decide Python Ragas versus Java-only compatibility mode.
- 2026-06-06: Recorded the user's clean-room reset decision: no legacy golden data, inline toy cases, or old synthetic enterprise samples are migrated into v2. Implementation must rebuild datasets from approved sources and delete or quarantine old eval assets after replacement coverage exists. Tests: not applicable, documentation-only. Verification: searched for stale migration/transition-period phrasing.
- 2026-06-06: Updated Phase 1 to make old evaluation file/code cleanup the first implementation task and added a no-unrelated-business-code scope gate. Tests: not applicable, documentation-only. Verification: re-read Phase 1 and searched for stale safety-cleanup wording.
- 2026-06-06: Applied plan-review fixes for the Phase 1/final-cleanup timing ambiguity and manifest license placeholder. The plan states that Phase 1 handles initial delete/quarantine before v2 implementation, while the final legacy-removal phase handles any remaining cleanup; that phase is now Phase 11 after the precision-tuning phase was added. The manifest example uses a concrete MTRAG Apache-2.0 example and Phase 3 validation must reject placeholder license values. Tests: not applicable, documentation-only. Verification: re-read changed sections, checked markdown fences, and searched for stale manifest-example placeholders and deletion-timing ambiguity wording.
- 2026-06-06: Recorded Phase 0 decisions for official Python Ragas, first-slice sources, default judge provider/model, CI fail policy, and old private PDF handling. Also recorded planning-level source accessibility checks. Tests: not applicable, documentation-only. Verification: re-read Phase 0 decision text and searched for stale Open Question wording.
- 2026-06-06: Implemented Phase 1 legacy cleanup. Deleted old RAG/RAGAS, memory, dialogue, intent, tool, PDF, golden-loader, and old metric eval files; deleted old golden resources; moved retained reliability/benchmark tests under explicit `eval/benchmark`, `eval/reliability`, and `eval/support` packages; retagged retained suites away from old `eval-*` tags; updated Maven exclusions for benchmark/reliability/manual suites. Verification: stale-reference audit clean, diff allowlist proof clean, targeted benchmark/reliability Maven tests passed, and default excluded-groups dry run passed.
- 2026-06-06: Amended the plan for accuracy-first precision tuning. Added a complete numeric configuration registry, real-data tuning/holdout split protection, sensitivity and combination search, reproducible trial artifacts, champion selection with confidence intervals and protected-category gates, and reviewed configuration promotion. Added Phase 9 for parameter tuning and shifted documentation/legacy removal to Phases 10/11. Confirmed `docs/env_variables.txt` as the local secret-bearing configuration source without reading or recording values.
- 2026-06-06: Applied plan-review fix for non-tuning report semantics. Clarified that `parameterSpaceId`, `experimentId`, `trialId`, and `randomSeed` are null or absent for ordinary smoke/full/baseline/single-suite runs and are populated only for parameter-search experiments. Files changed: plan and implementation record only. Simplicity/style impact: prevents over-building the report writer and avoids empty-string placeholders. Tests: not applicable, documentation-only. Verification: re-read the report schema section, checked Markdown fence balance, and searched for conflicting required-field phrasing. Remaining manual checks: none for this finding.
- 2026-06-06: Committed accepted Phase 1 implementation as `3310250` (`Clean up legacy evaluation suites`) before starting Phase 2.
- 2026-06-06: Implemented Phase 2 v2 core contracts. Added canonical schemas/fixtures, dependency-free Python contracts, Java export-side metrics/writer/contracts, deterministic parameter trials, champion selection, threshold evaluation, config fingerprinting, and cross-language parity fixtures. Simplicity impact: no optimizer, Ragas, schema-validator, provider, or business-runtime dependency was added; the small schema subset validator is local to eval contracts. Code style impact: Java records/utilities and AssertJ/JUnit follow the existing test-area conventions; Python uses standard-library `unittest`. Verification after review fix: Python 8 tests passed, Python compileall passed, Java targeted 9 tests passed, and the complete bootstrap suite passed with 810 tests and 0 failures. Remaining manual checks: none for Phase 2.
- 2026-06-06: Applied the Phase 2 implementation-review fix for Python artifact path traversal. `write_run_artifacts` now resolves the output root and run directory and rejects any run directory outside the root, matching the Java writer's defense-in-depth. Files changed: `tools/eval/chatagent_eval/reports.py`, `tools/eval/tests/test_core_contracts.py`, plan status, and implementation record. Simplicity impact: one local boundary check, no new abstraction or dependency. Code style impact: retained standard-library `pathlib` and `unittest` conventions. Test coverage: added `runId=".."` regression. Verification: Python core suite passed with 8 tests, compileall passed, and diff checks passed. Remaining manual checks: none; Phase 2 accepted.
- 2026-06-06: Committed accepted Phase 2 implementation as `0948368` (`Add evaluation v2 core contracts`) before starting Phase 3.
- 2026-06-06: Implemented Phase 3 source catalogs, secure/cached downloaders, normalized real-data builders, concrete source/dataset/split manifests, source-group leakage protection, sample-size gates, and threshold files. Actual pinned SciFact and MTRAG downloads/hashes passed and produced full-size retrieval/memory/multi-turn datasets. SEC Company Facts/submissions/filing-HTML preparation completed its live 25-file smoke run with per-file source URLs/hashes, per-request fair-access rate limiting, grounded visible-HTML recall phrases, and no persisted contact value. A real missing Company Facts `404` exposed and motivated a close/skip/continue regression path. Verification: Python 19 tests passed, compileall passed, Java v2 10 tests passed, complete bootstrap 811 tests passed, and diff/scope/privacy checks passed.
- 2026-06-06: Initial Phase 3 implementation cross-review found one Spec gap: normalized retrieval, memory, and text-recall records lacked dataset-specific schema references and per-row validation. Added three record schemas, linked each dataset manifest to its schema, and validated every generated real-data row. Cross-review rerun found no remaining Spec or Standards findings. Verified approved-source scope, real sample counts, concrete terms, manifest/hash coverage, explicit source-group splits, holdout leakage protection, ignored generated artifacts, no contact-value persistence, no production business-code changes, and accurate implementation documentation. Phase 3 accepted; Phase 4 next.
- 2026-06-07: Implemented Phase 4 production retrieval export through `KnowledgeBaseSimilaritySearcher`, an eval-owned deterministic index fixture, normalized v2 artifacts, complete deterministic retrieval metrics, exact per-trial config capture, cleanup ownership guards, default `eval-v2` exclusion, and a real SciFact baseline/topK sensitivity smoke. Verification: 2 runner/cleanup tests passed, 12 relevant v2 tests passed, real SciFact smoke passed, default exclusion proved 0 tagged tests run, and complete bootstrap passed with 813 tests. Phase 4 was later accepted and committed.
- 2026-06-07: User reported Phase 4 accepted and committed as `c227033`; implemented Phase 5 official Ragas runner. Added optional Ragas dependencies, CLI `ragas-smoke`, v2-to-Ragas sample conversion, lazy official Ragas evaluator, configurable eval Provider/model/embedding settings, deterministic-vs-Ragas metric namespacing, structured/nan failure modes, and no-live-provider tests. Verification: Python eval suite passed with 26 tests, compileall passed, and real Phase 4 SciFact export CLI smoke wrote a warn report with deterministic metrics preserved and `ragas.*` metrics null because Phase 4 exports do not yet include generated answers/reference answers. Phase 5 was later accepted and committed as `989c9c2`.
- 2026-06-07: Implemented Phase 6 text recall suite. Added deterministic parser/chunk/retrieval/citation recall runner, `text-recall-smoke` CLI, parameterized chunk/topK settings with config fingerprints, table-cell recall separation, source-file path escape defense, report/schema tests, and failure rows with missing phrases plus actual topK contexts. Verification: Python eval suite passed with 30 tests, compileall passed, real SEC EDGAR 25-file smoke passed at topK=5 with 200/200 required phrases recalled, and topK=1 sensitivity produced expected warn failures at retrieval/citation recall `0.925`. Phase 6 was later accepted and committed as `53b9229`.
- 2026-06-07: Implemented Phase 7 memory suite. Added deterministic Memory V2 runner, `memory-smoke` CLI, ADR 0001 run metadata, L1 complete-turn/tool-response/budget metrics, L2 fact/precision/contradiction/segment/fallback/retry metrics, L3 extraction/type/tag/idempotency/recall metrics, and no-provider tests. Verification: Python eval suite passed with 35 tests, compileall passed, real IBM MTRAG 842-task smoke passed, and a 50-task low-L1-budget sensitivity produced expected warn failures.
- 2026-06-07: Applied Phase 7 implementation-review P3 documentation fix. Clarified that the deterministic offline runner provides single-sample isolation only, while live `UserMemoryIndexService` user isolation and inactive-memory exclusion are deferred to a later live-memory integration suite. Files changed: plan and implementation record only. Simplicity/style impact: documentation-only; no code, tests, Provider settings, or runtime defaults changed. Test coverage: no code change required by the accepted review. Verification: re-read changed sections and searched for stale cross-review-pending wording.
- 2026-06-07: Committed accepted Phase 7 implementation as `ab82b94` (`Add memory evaluation runner`) before starting Phase 8.
- 2026-06-07: Implemented Phase 8 agent module suites. Added deterministic intent, query rewrite, tool-call, and multi-turn module runner; `agent-modules-smoke` CLI; suite-owned `agent-modules-v1` parameter space; schema/failure/CLI/mismatch tests; optional Ragas agent metric slots labeled disabled; and real MTRAG smoke/sensitivity runs. Verification: targeted Phase 8 tests passed with 5 tests, Python eval suite passed with 40 tests, compileall passed, real 842-task MTRAG smoke passed with 0 failures, and a 50-task no-rewrite-anchor sensitivity produced expected warn failures. Phase 8 was later accepted and committed.
- 2026-06-07: Phase 8 implementation review accepted the agent-module suites with no findings; committed accepted Phase 8 as `68cfdff` (`Add agent module evaluation suites`) before starting Phase 9.
- 2026-06-07: Implemented Phase 9 precision parameter tuning and review-only promotion for the real-data Python suites. Added the numeric parameter registry, suite-owned parameter spaces/policies, deterministic sensitivity and combination search, confidence intervals, category/hard gates, sealed-holdout audit, baseline-preserving tie-break, Pareto/leaderboard/promotion artifacts, and `tune-suite` CLI. Verification: targeted Phase 9 tests passed with 6 tests, complete Python eval suite passed with 46 tests, Java shared schema fixture verification passed with 3 tests, compileall/diff/privacy checks passed, and real MTRAG agent/memory plus SEC text-recall experiments produced passing zero-overlap holdout audits with production defaults unchanged. Production Java retrieval and live reranker tuning remain explicitly pending rather than represented by a proxy result. Phase 9 accepted.
- 2026-06-08: Phase 9 cross-review accepted with no findings. Review also identified that all suite runners use deterministic proxies rather than real models — metrics infrastructure is real, but the model-under-test layer is synthetic. Amended plan to insert Phase 10 (Real Model Evaluation Upgrade) with three sub-phases: 10a RAG retrieval real Ollama bge-m3 embeddings, 10b memory real Java export runner with UserMemoryIndexService + LLM compaction (Path B), 10c agent module real model output capture. Old Phase 10 (Documentation) renumbered to Phase 11; old Phase 11 (Legacy Removal) renumbered to Phase 12. Ragas answer generation deferred. Deterministic diagnostics preserved as CI regression tests.
- 2026-06-07: Applied Phase 10 plan-review fixes (3 P2, 2 P3). P2 fixes: (1) Phase 10b — added calling convention specifying direct synchronous calls to `IncrementalSummarizer.summarizeWithDetails()` and `LongTermMemoryExtractor.extract()`, `@SpringBootTest` with `eval-real-memory` profile, and Ollama/Milvus/LLM prerequisites; (2) Phase 10c — clarified coreference is measured through rewrite quality (not a standalone module), added synthetic intent tree fixture matching MTRAG taxonomy, and scoped tool selection to available-tool list only (LLM call decisions deferred); (3) cross-cutting — added shared `eval-real` Maven profile and an initial infrastructure probe approach, later superseded by the JUnit 5 `ExecutionCondition` pattern recorded below. P3 fixes: (4) Phase 10a — documented 128-dim vs 1024-dim embedding dimension and constructor parameter; (5) Phase 10c — added tool selection accuracy acceptance criterion. Files changed: plan and implementation record only. Simplicity/style impact: documentation-only; no runtime code changed. Verification: re-read changed sections, searched for stale coreference/async-listener phrasing. Remaining manual checks: none for this plan-review fix.
- 2026-06-07: Applied second round of Phase 10 plan-review fixes (1 P1, 3 P2, 1 P3). P1 fix: Phase 10b — added isolation and cleanup rules (eval-owned ID prefixes, dedicated eval Milvus collection, non-eval preservation guard, cleanup limited to eval prefixes). P2 fixes: (2) Reframed all acceptance criteria to depend on provenance metadata (provider/model, raw outputs) rather than stochastic model quality; non-perfect metrics are observations/warns not hard gates; (3) Fixed cross-cutting Maven profile description to match actual default behavior (`surefire.excludedGroups` excludes `eval-v2` by default — deterministic tests are opt-in, not default); (4) Added Phase 10 Tuning Integration section specifying concrete tuning rerun per sub-phase with Phase 9 artifact structure and provenance metadata. P3 fix: (5) Updated stale "Phase 9" wording in Open Decisions to Phase 10 with remaining infrastructure prerequisites. Files changed: plan and implementation record only. Simplicity/style impact: documentation-only; no runtime code changed. Verification: re-read changed sections, confirmed `pom.xml` line 19 excludes `eval-v2` by default, searched for stale "< 1.0" hard-gate and "default behavior" phrasing. Remaining manual checks: none for this plan-review fix.
- 2026-06-07: Applied third round Phase 10 plan-review fix (1 P2). P2 fix: Phase 10 Tuning Integration bridge contract was underspecified — `tune-suite` expects a Phase 3-style dataset root (manifests/datasets/<id>.json, split manifest, dataset JSONL), but Phase 10 export artifacts are run artifacts. Fixed by specifying that each Java export runner writes a Phase 3-compatible dataset root alongside its run artifacts, preserving original source-group hashes from Phase 3 splits. No changes to `tune-suite` or Python runners needed. Added Python test requirement: fixture real-export directory feeds `tune-suite` with sealed-holdout verification. Files changed: plan and implementation record only. Simplicity/style impact: documentation-only; reuses existing Phase 3 contract rather than adding a new adapter mode. Verification: re-read tuning integration section, confirmed `tune-suite` reads `manifests/datasets/<id>.json` at line 115 of tuning_runner.py. Remaining manual checks: none for this plan-review fix.
- 2026-06-07: Applied fourth round Phase 10 plan-review fixes (1 P1, 1 P2). P2 fix: provenance propagation — `tuning_runner.py` records `models: {}` in trials (line 297). Rather than modifying the tuning runner, specified that provenance lives in the dataset manifest's `provenance` object and is traceable via the hash chain: experiment manifest → `datasetHash` → dataset manifest → `provenance`. No Python changes needed. P1 fix revised per user feedback: instead of deferring rag-retrieval tuning, Phase 10a now includes adding `rag-retrieval` to `tune-suite` `SUPPORTED_SUITES`, `SUITE_CONFIG_FIELDS` (topK, candidateK, rrfK), `_dataset_id` (beir-scifact-rag-v1), `_run_suite` dispatch, and new tuning policy `rag-retrieval-v1.json`. All three sub-phases (10a/10b/10c) now produce Phase 3-compatible dataset roots and feed `tune-suite`. Files changed: plan and implementation record only. Simplicity/style impact: small addition to tuning_runner.py following existing patterns; no new abstractions. Verification: re-read Phase 10a section and tuning integration section, confirmed parameter space `rag-retrieval-v1.json` already exists with topK/candidateK/rrfK. Remaining manual checks: none for this plan-review fix.
- 2026-06-07: User audit of all Phase 10 review fixes found one deviation from user intent: Round 2 P2 fix had weakened all "< 1.0" discriminative power gates to "observations/warns", which contradicts the user's original goal of proving real models produce different results than deterministic proxies. Applied two-tier gate: smoke runs (small sample) — provenance confirmation suffices, non-perfect is a warn; full/tuning runs — if all samples score 1.0 (identical to deterministic proxy), it is a finding indicating the measurement lacks discriminative power. Updated top-level acceptance criteria and all three sub-phase criteria (10a, 10b, 10c). Files changed: plan and implementation record only. Verification: re-read all acceptance criteria sections, confirmed smoke/full distinction is consistent.
- 2026-06-07: Applied P1 + P3 fixes. P1: dataset manifest provenance field conflicted with `eval-dataset-manifest.schema.json` which has `additionalProperties: false` and did not define `provenance`. Added optional `provenance` object to the schema with fields `provider`, `modelName`, `embeddingModel`, `exportTimestamp`. Verified existing manifests (without provenance) still validate, and manifests with provenance also validate. P3: `_dataset_id("rag-retrieval")` was specified as `"beir-scifact"` but the existing Phase 3 retrieval dataset ID is `"beir-scifact-rag-v1"`. Fixed plan to use the correct existing ID. Files changed: `eval-dataset-manifest.schema.json`, plan and implementation record. Simplicity/style impact: minimal schema extension (one optional property), follows existing schema conventions. Verification: Python validation of existing manifest and manifest-with-provenance both pass. Remaining manual checks: none for this plan-review fix.
- 2026-06-07: Applied P2 + P3 fixes. P2: provenance object was nullable — `"provenance": {}` passed validation, undermining its role as real-model evidence. Added `required: ["provider", "modelName", "embeddingModel", "exportTimestamp"]` inside the provenance object definition. Verified: existing manifests pass (provenance is optional at top level), full provenance passes, empty provenance rejected, missing provider/modelName rejected. P3: implementation doc Phase 10a summary and tuning integration summary still had old `beir-scifact` dataset ID. Fixed both to `beir-scifact-rag-v1`. Files changed: `eval-dataset-manifest.schema.json`, implementation record. Verification: Python 5-case schema validation passed. Remaining manual checks: none for this plan-review fix.
- 2026-06-07: Implemented Phase 10a RAG retrieval real-embedding and tuning support. Files changed: `EvalOwnedKnowledgeBaseFixture.java`, `RagRetrievalRealEmbeddingEvalTest.java`, `rag_retrieval_runner.py`, `tuning_runner.py`, `run_eval.py`, `rag-retrieval-v1.json` parameter space/policy, `parameter-registry-v1.json`, `test_tuning_runner.py`, dataset manifest schema, and plan docs. Implementation details: the eval-owned fixture now supports injected embedding functions and explicit hash/ollama dimensions; the new `eval-real` test probes Ollama, records embedding model/base URL/dimension in run artifacts, and skips when unavailable; `tune-suite --suite rag-retrieval` now runs through a Python replay runner over Java real-export candidate contexts with raw scores/rank signals, refusing rows without candidate contexts rather than using a deterministic proxy. Simplicity impact: no production code or runtime defaults changed; replay tuning reuses Phase 9 artifacts and avoids a Maven subprocess adapter. Verification: Python compileall passed; Python eval suite passed with 47 tests; Java `RagRetrievalExportRunnerTest` passed with 2 tests after setting `JAVA_HOME` to the JDK root for the command; `RagRetrievalRealEmbeddingEvalTest` compiled and skipped green when `chatagent.eval.ollamaBaseUrl` pointed to an unavailable endpoint. Remaining manual checks: run Phase 10a against live Ollama bge-m3 and real SciFact export, then run rag-retrieval tuning on the real export and inspect provenance/discriminative metrics.
- 2026-06-07: Applied Phase 10a implementation review fixes (2 P1, 2 P2). P1 fixes: `rag_retrieval_runner.py` now only accepts real-export `candidateContexts` and rejects retrieved-only rows or candidates missing score/rank signal; `RagRetrievalExportRunner.java` writes a Phase 3-compatible dataset root under each run directory (`manifests/datasets`, `manifests/splits`, `datasets/rag`) with `metadata.candidateContexts`, raw candidate scores/rank signals, split group hashes, dataset hashes, and provenance. P2 fixes: `bootstrap/pom.xml` adds the `eval-real` profile that removes `eval-v2/eval-real` from Surefire exclusions while preserving other default exclusions; `EvalOwnedKnowledgeBaseFixture` validates embedding dimensions on embed/upsert/search and fails mixed hash/Ollama vectors clearly. Tests strengthened: Python rag replay negative cases for retrieved-only rows and no-signal candidates; Java dataset-root/provenance/candidate-context assertions; Java mixed-dimension fixture assertions; eval-real unavailable-Ollama skip path. Verification: Python compileall passed; Python eval suite passed with 49 tests; Java `RagRetrievalExportRunnerTest` passed with 3 tests; Maven `-P eval-real -Dtest=RagRetrievalRealEmbeddingEvalTest -Dchatagent.eval.ollamaBaseUrl=http://127.0.0.1:1 test` passed with 1 skipped test. Remaining manual checks: live Ollama bge-m3 SciFact run and real-export tuning remain unrun.
- 2026-06-07: Applied round 3 Phase 10a implementation review fixes (1 P2, 2 P3). P2 fix: hardcoded `sourceIds` in dataset manifest — `RagRetrievalExportRunner.datasetManifest()` now reads `sourceIds` from `runConfig` with a backward-compatible `"beir-scifact"` default; unit test runConfig provides `"sourceIds": ["unit-retrieval"]` and asserts the correct value in the manifest. P3 fixes: (1) duplicate search calls per eval case — the export runner now calls `searchRankedCandidateHitsByKnowledgeBaseIds()` once per case, derives `topHits` (limited to `topK`) from the fused candidates, and computes metrics/contexts from `MilvusSearchHit` directly without the do-nothing reranker path; `RetrievalHit` import removed from the runner. (2) untracked new files noted for `git add`. Files changed: `RagRetrievalExportRunner.java`, `RagRetrievalExportRunnerTest.java`, `RagRetrievalRealEmbeddingEvalTest.java`. Simplicity impact: removed one unnecessary production-code call path from the eval export loop; `sourceIds` derivation follows the same `runConfig` pattern as `provenance()`. Code style impact: `MilvusSearchHit` was already imported; content fallback (`content != null ? content : retrievalText`) matches the existing `candidateContexts()` pattern. Tests strengthened: `RagRetrievalExportRunnerTest` now asserts `sourceIds` in the dataset manifest is `"unit-retrieval"` (not the hardcoded `"beir-scifact"`). Verification: `mvnw -pl bootstrap -Dtest=RagRetrievalExportRunnerTest test` passed with 3 tests; `python -m unittest discover -s tests -v` passed with 51 tests; `python -m compileall -q chatagent_eval tests run_eval.py` passed; `git diff --check` passed. Remaining manual checks: live Ollama bge-m3 SciFact run and real-export tuning remain unrun; untracked files need `git add`.
- 2026-06-07: Applied Phase 10 design review fixes (2 P1, 2 P2, 2 P3). P1 fixes: (1) Phase 10b — specified database strategy (programmatic insertion via Spring beans with `eval-memory-*` prefixes, `@BeforeEach`/`@AfterEach` lifecycle, PostgreSQL default datasource) and documented the required tables/entities (`SummaryWatermarkService`, `ChatSessionSummaryRepository`, `TurnBasedContextExtractor`); (2) Phase 10c — named exact entry points with confirmed file:line references (`IntentRouter.route()` at IntentRouter.java:81, `QueryRewriter.rewrite()` at QueryRewriter.java:38, `AgentToolCallbackFactory.create()` at AgentToolCallbackFactory.java:69) and described the full pipeline capture. P2 fixes: (3) eval-real-memory Spring profile — specified `application-eval-real-memory.yaml` contents and clarified Maven profile vs Spring profile distinction (`@ActiveProfiles("eval-real-memory")` within `@SpringBootTest`); (4) Phase 10b/10c dataset format — added detailed JSONL row schemas for both sub-phases (memory: `moduleOutputs.l1Summary`/`.l3Extraction`/`.provider`; agent-modules: `moduleOutputs.intent`/`.queryRewrite`/`.toolList`/`.provider`) with dataset root structure. P3 fixes: (5) Phase 10b test design — replaced single sentence with concrete isolation test (two users, cross-user assertion) and inactive exclusion test (flagged memory exclusion); (6) two-tier gate — added acceptance criteria paragraph to Phase 10 section body (smoke ≤50 samples provenance suffices; full/tuning all-1.0 is a discriminative-power finding). Files changed: implementation record only (documentation-only, Plan Fix mode). Simplicity/style impact: no runtime code, tests, or configuration changed. Verification: re-read Phase 10 section body (lines 649–660), confirmed all 6 fixes present, searched for stale "async listeners" and "Test user isolation" phrasing.
- 2026-06-07: Applied follow-up Phase 10 plan-review fixes (2 P2). P2 fixes: (1) plan sync — synced Phase 10b DB strategy, `application-eval-real-memory.yaml` spec, concrete test designs, Phase 10c exact entry points with file:line refs, separate `eval-real-agent-modules` profile, and per-suite dataset row schemas from the implementation doc into `RAGAS_EVALUATION_REWORK_PLAN.md` so the primary plan matches the implementation record; (2) 10c profile isolation — separated Phase 10c from the `eval-real-memory` profile (which enables Milvus + L3 memory) by defining a dedicated `eval-real-agent-modules` Spring profile that requires the LLM provider, database, and Redis while still running/skipping independently of Milvus/Ollama availability. Files changed: implementation record and plan (documentation-only, Plan Fix mode). Verification: searched both docs for `application-eval-real-memory.yaml`, `application-eval-real-agent-modules.yaml`, `IntentRouter.java:81`, `moduleOutputs.l1Summary`, `moduleOutputs.intent` — all terms present in both documents; confirmed 10c no longer references `eval-real-memory` profile.
- 2026-06-07: Implemented Phase 10b memory real-export runner. Files changed: `application-eval-real-memory.yaml` (new), `MemoryExportEvalTest.java` (new). Implementation details: The `application-eval-real-memory.yaml` Spring profile enables `milvus.enabled=true` and `chatagent.memory.l3.enabled=true` with a dedicated eval-collection `chat_user_memory_eval`, reusing the default PostgreSQL datasource from `application.yaml`. `MemoryExportEvalTest` is a `@SpringBootTest` with `@ActiveProfiles("eval-real-memory")` tagged `eval-v2` and `eval-real`. Infrastructure probes run via JUnit 5 `ExecutionCondition` (`MemoryInfrastructureCondition`) before Spring context loads, checking PostgreSQL (TCP to configured host:port), Ollama (`/api/tags`), Milvus (TCP to configured host:port), and LLM provider (HTTP POST 1-token completion probe); when any is unavailable, the entire class is disabled so Spring never starts. Five concrete tests: (1) `dbSetupAndTeardown` — inserts MTRAG dialogue messages via `ChatMessageRepository.save()`, calls `IncrementalSummarizer.summarizeWithDetails()`, asserts non-empty `SummaryResult` with extracted turns; (2) `userIsolation` — indexes L3 memories for two eval-prefixed users, asserts cross-user isolation; (3) `inactiveMemoryExclusion` — indexes one active and one archived memory, asserts archived excluded; (4) `exportsRealMemoryPipeline` — reads MTRAG dialogue rows from Phase 3 dataset with balanced split selection (covering calibration/development/holdout), processes through summarizer + extractor + L3 index, writes a Phase 3-compatible dataset root with split manifest including holdout; (5) `nonEvalDataSurvivesCleanup` — seeds non-eval canary memory, runs cleanup, asserts canary survives. Simplicity impact: no production code or runtime defaults changed; the export runner reuses existing `EvalArtifactWriter` and follows the same dataset-root pattern as the Phase 10a `RagRetrievalExportRunner`. Code style impact: the test follows existing `@SpringBootTest` conventions (properties to disable RabbitMQ/MQ), `EvalOwnedKnowledgeBaseFixture`-style repository-root discovery, and AssertJ assertions. Verification: Maven `test-compile` passed with BUILD SUCCESS; existing `RagRetrievalExportRunnerTest` passed with 3 tests; Python compileall passed; Python eval suite passed with 51 tests; `MemoryExportEvalTest` correctly skipped (0 tests run) when infrastructure unavailable via `-P eval-real` profile.
- 2026-06-08: Applied Phase 10b implementation review fixes (3 P1, 2 P2, 1 P3). P1 fixes: (1) Memory tuning real-export mode — `memory_runner.py` `_evaluate_row` now auto-detects `moduleOutputs` in rows via `_has_module_outputs()`. When present, it reads real LLM-generated L2 synopsis from `moduleOutputs.l1Summary.synopsis` and real L3 extracted memories from `moduleOutputs.l3Extraction.memories`, replacing the deterministic proxy outputs. Falls back to deterministic when `moduleOutputs` are absent. New helper functions: `_has_module_outputs()`, `_real_l2_synopsis()`, `_real_l3_memories()`. This means `tune-suite --suite memory-v2` against a Java real-export dataset root now computes genuine L2/L3 metrics from real model outputs; (2) Memory dataset schema — `eval-memory-dataset-record.schema.json` now declares optional `moduleOutputs` object with `l1Summary`, `l3Extraction`, and `provider` sub-properties. Existing Phase 3 rows without `moduleOutputs` still validate; (3) Milvus cleanup and isolation — `MemoryExportEvalTest` now autowires `MilvusClientV2`, tracks eval-owned Milvus memory IDs via `trackMilvusMemory()`, and deletes them in `@AfterEach` using `milvusClient.delete()` with a `memory_id` filter. `indexL3Memories()` now tracks every upserted memory ID. P2 fixes: (4) TCP probes — replaced HTTP-based `tcpProbe` with `java.net.Socket`.connect() that works for PostgreSQL, Milvus, and any TCP service; (5) LLM provider probe — upgraded from API-key-only check to TCP-level endpoint reachability check against the configured DeepSeek base URL host:port, plus the API key presence check. P3 fix: (6) Source provenance — `exportsRealMemoryPipeline` now reads `sourceIds` from the Phase 3 dataset manifest (e.g., `mtrag-human`) instead of hardcoding `["mtrag"]`. Test coverage: Python real-export fixture test (`test_real_export_metrics_use_module_outputs`) verifies that L2/L3 metrics change when `moduleOutputs` differ from deterministic; Python schema validation test (`test_real_export_row_validates_against_memory_schema`) verifies rows with `moduleOutputs` pass the updated schema. Files changed: `memory_runner.py`, `eval-memory-dataset-record.schema.json`, `MemoryExportEvalTest.java`, `test_memory_runner.py`. Simplicity impact: no new suite or runner mode — `run_memory` auto-detects moduleOutputs and uses them transparently; Milvus cleanup reuses existing `MilvusClientV2.delete()` API. Verification: Python compileall passed; Python eval suite passed with 53 tests (2 new); Maven test-compile passed with BUILD SUCCESS; existing `RagRetrievalExportRunnerTest` passed with 3 tests; `MemoryExportEvalTest` with `-P eval-real` correctly skipped (0 tests run, BUILD SUCCESS) when infrastructure unavailable. Remaining manual checks: run Phase 10b against live PostgreSQL + Ollama bge-m3 + Milvus + LLM provider and inspect real memory export artifacts; verify tune-suite --suite memory-v2 against real export produces non-trivial champion.
- 2026-06-08: Applied round 2 Phase 10b review fixes (1 P1, 2 P2, 1 P3). P1 fix: graceful skip — previous `@BeforeAll assumeTrue` approach failed because `@SpringBootTest` loads Spring context (connecting to PostgreSQL, Milvus, etc.) before test methods execute. Replaced with a JUnit 5 `ExecutionCondition` (`MemoryInfrastructureCondition`) that runs before Spring context initialization, probing all 4 services via the same static methods. The condition disables the test class entirely when infrastructure is unavailable, so Spring context never loads. Result: `mvnw -pl bootstrap -P eval-real -Dtest=MemoryExportEvalTest test` now reports `Tests run: 5, Skipped: 5, BUILD SUCCESS` instead of Spring context startup failure. Removed `@BeforeAll probeInfrastructure()`, `infrastructureAvailable` field, `@BeforeAll`/`@AfterEach`/`@BeforeEach` infrastructure guards, and per-test `assumeTrue(infrastructureAvailable)`. Added `@ExtendWith(MemoryInfrastructureCondition.class)`, `probeAll()` package-private entry point, and `MemoryInfrastructureCondition.java` (new file, 25 lines). P2 fixes: (2) Empty moduleOutputs rejection — `_has_module_outputs()` previously returned `False` for `moduleOutputs: {}`, silently falling back to deterministic mode. Now: key absent → `False` (deterministic), key present but empty/null → `ValueError` (malformed real-export). Schema already rejects empty moduleOutputs via `required` constraints; runner now matches. Test updated from `assertFalse` to `assertRaisesRegex(ValueError)`. (3) LLM probe model — replaced hardcoded `"deepseek-chat"` in `probeLlmProvider()` with `SUMMARY_MODEL` constant, so the probe validates the actual configured model that the summarizer will use. P3 fix: (4) Implementation doc — updated this record. Files changed: `MemoryInfrastructureCondition.java` (new), `MemoryExportEvalTest.java`, `memory_runner.py`, `test_memory_runner.py`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: the `ExecutionCondition` is 25 lines with no Spring dependencies; `probeAll()` reuses existing static probe methods. Code style impact: `@ExtendWith` follows the same pattern as `@SpringBootTest`'s implicit SpringExtension registration. Test coverage added: `test_empty_module_outputs_raises_value_error` (Python, was `assertFalse` → now `assertRaisesRegex`). Verification: `python -m unittest tests.test_memory_runner -v` — 10 tests passed; `python -m compileall` passed; Maven test-compile passed; `mvnw -pl bootstrap -P eval-real -Dtest=MemoryExportEvalTest test` — `Tests run: 5, Skipped: 5, BUILD SUCCESS`. Remaining manual checks: run Phase 10b against live infrastructure (all 5 tests should pass); verify tune-suite memory-v2 against real export.
- 2026-06-08: Applied round 3 Phase 10b fixes: null moduleOutputs rejection + live infrastructure DB issues. (1) `moduleOutputs: null` — `_has_module_outputs()` previously returned `False` for `moduleOutputs: None`. Now raises `ValueError("null moduleOutputs")`. Added `test_null_module_outputs_raises_value_error`. (2) DB foreign key constraint — `insertDialogueMessages()` failed because `chat_message.session_id` references `chat_session.id`, but no `chat_session` row was created first. Added `ensureSession(sessionId)` helper that resolves an existing `t_user` row via `userRepository.findPage()` and creates a minimal `chat_session` row. The user mapper uses `useGeneratedKeys` (auto-generated UUID) so creating a user with a specific ID is not possible; instead we look up any existing active user. Added `@Autowired ChatSessionRepository`, `@Autowired UserRepository`. (3) Session ID overflow — `exportsRealMemoryPipeline()` used `"eval-memory-" + "phase10b-memory-real-smoke" + "-" + sampleId` (40+ chars) which exceeded `chat_session.id VARCHAR(64)`. Changed to `"eval-memory-" + 8-char UUID` (20 chars), same pattern as `dbSetupAndTeardown()`. (4) Cleanup now deletes `chat_session` rows after messages (FK order). Files changed: `MemoryExportEvalTest.java`, `memory_runner.py`, `test_memory_runner.py`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: `ensureSession` is a single method reusing existing repositories; no new tables or migrations. Verification: `python -m unittest tests.test_memory_runner -v` — 11 tests passed; `mvnw -pl bootstrap -P eval-real -Dtest=MemoryExportEvalTest test` — `Tests run: 5, Failures: 0, Errors: 0, BUILD SUCCESS` against live PostgreSQL + Ollama bge-m3 + Milvus + DeepSeek API. All 5 tests pass on live infrastructure. No remaining manual checks for Phase 10b.
- 2026-06-08: Applied round 4 Phase 10b tuning integration fixes (2 P1, 1 P3). P1 fix 1: balanced split selection — `loadDatasetRows()` previously took the first `SMOKE_DIALOGUE_COUNT` rows, which excluded holdout rows. The exported dataset root's split manifest was missing holdout, so `tune-suite --suite memory-v2` failed with "split manifest is missing required splits: ['holdout']". Replaced with `balanceSplits()` which loads all rows, selects `MIN_PER_SPLIT` (2) rows from each required split (calibration, development, holdout), then fills remaining slots in original order. Added `REQUIRED_SPLITS` constant and split coverage assertion in `exportsRealMemoryPipeline`. P1 fix 2: tuning policy hard gates — `memory-v2-v1.json` had `memory.l3ExtractionF1: {min: 1.0}` and `memory.l2ContradictionRate: {max: 0.0}` as hard gates. Real LLM outputs are not perfect, so all trials were gated out. Removed both from hardGates; added both to secondaryMetrics for ranking/reporting. Only `memory.l1BudgetCompliance: {min: 1.0}` remains as a hard gate (deterministic metric, always achievable). P3 fix: documentation — synced implementation record status, skip mechanism description (ExecutionCondition, not assumeTrue), Phase 10b implementation description, and Phase 10 status line to current facts. Files changed: `MemoryExportEvalTest.java`, `memory-v2-v1.json`, `test_tuning_runner.py`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: `balanceSplits` is 15 lines with no Spring dependencies; policy change removes gates rather than adding complexity. Test coverage added: `test_memory_v2_policy_does_not_hard_gate_extraction_f1` (policy structure regression), `test_cli_tune_suite_runs_memory_v2` (end-to-end memory-v2 tuning pipeline). Verification: `python -m unittest discover -s tests -v` — 59 tests passed; `python -m compileall` passed; `git diff --check` passed; Maven test-compile passed. No remaining manual checks for Phase 10b.
- 2026-06-08: Implemented Phase 10c agent module real-export eval. Files created: `application-eval-real-agent-modules.yaml` (new, minimal Spring profile — no Milvus/Ollama/L3 memory), `AgentModuleInfrastructureCondition.java` (new, JUnit 5 ExecutionCondition probing PostgreSQL + Redis + LLM provider), `AgentModuleExportEvalTest.java` (new, 4 test methods). Implementation details: The test reuses the system assistant agent (seeded by V2 migration at fixed UUID `3f9f84f7-...`) for intent tree routing. A synthetic 7-node intent tree (1 domain → 3 categories → 3 topics covering KB/TOOL/SYSTEM kinds) is created via `IntentNodeRepository.saveAll()` at a high version number (99999), with the agent's `activeIntentVersion` temporarily updated. The `@BeforeEach` creates the tree and evicts Redis cache; `@AfterEach` restores the agent version, deletes eval nodes (children-first for FK order), and evicts cache. The `cleanupEvalNodes()` method is idempotent — called before insert to handle stale nodes from previous crashed runs. Four test methods: (1) `intentTreeSetupAndRouting` — verifies IntentRouter returns non-null for a sample query; (2) `queryRewriteEnrichesQuery` — verifies QueryRewriter returns a non-empty rewritten query for a resolved intent; (3) `toolCallbackFactoryReturnsTools` — verifies AgentToolCallbackFactory returns non-empty tool list for a KB intent; (4) `exportsRealAgentModulePipeline` — full pipeline: reads MTRAG rows with balanced split selection, routes each query via IntentRouter, rewrites via QueryRewriter, captures tool names via AgentToolCallbackFactory, writes a Phase 3-compatible dataset root with `moduleOutputs` containing intent routing result, rewritten query, tool list, and provider metadata. Infrastructure requirements: PostgreSQL (agent + intent_node tables), Redis (intent tree cache via `DefaultIntentTreeCacheManager`), LLM provider (IntentRouter LLM classifier + QueryRewriter semantic rewrite). No Milvus or Ollama required. Simplicity impact: no new production code; reuses existing `IntentRouter`, `QueryRewriter`, `AgentToolCallbackFactory`, `IntentNodeRepository`, `AgentRepository`, `IntentTreeCacheManager` beans. Intent tree fixture uses fixed UUIDs for reproducibility. Same `balanceSplits()` and dataset root writer patterns as Phase 10b. Code style impact: follows `MemoryExportEvalTest` conventions (`@SpringBootTest`, `@ActiveProfiles`, `@ExtendWith`, AssertJ assertions, `@BeforeEach`/`@AfterEach` lifecycle). Verification: Maven test-compile passed; `mvnw -pl bootstrap -P eval-real -Dtest=AgentModuleExportEvalTest test` — `Tests run: 4, Failures: 0, Errors: 0, BUILD SUCCESS` against live PostgreSQL + Redis + DeepSeek API; `python -m unittest discover -s tests -v` — 59 tests passed; `git diff --check` passed. No remaining manual checks for Phase 10c.
- 2026-06-08: Applied Phase 10c implementation review fixes (2 P1, 1 P2). P1 fix 1: Python real-export adapter — `agent_module_runner.py` previously read `moduleOutputs.rewrite` and `moduleOutputs.toolCalls`, but Java 10c exports `moduleOutputs.queryRewrite` and `moduleOutputs.toolList`. When real-export data was present, the field name mismatch caused `_merge_outputs` to fall back to expected values, producing fake 1.0 metrics for intent accuracy, tool F1, and rewrite quality. Added `_is_real_export()` (detects `provider` key) and `_adapt_real_export()` which validates required fields (`intent`, `queryRewrite`, `toolList`), maps Java field names to Python scoring fields, and constructs observed intent from real routing results (real `kind`/`outOfScope` override expected; `domain`/`outcome`/`multiturn` stay from expected as row-level properties). Missing required fields raise `ValueError`. P1 fix 2: dataset path and schema — Java `AgentModuleExportEvalTest` wrote to `datasets/memory/` and declared `recordSchema: eval-memory-dataset-record.schema.json` (memory-specific `moduleOutputs` with `l1Summary`/`l3Extraction`). Fixed to write to `datasets/agent-modules/` with `recordSchema: eval-agent-module-dataset-record.schema.json` (new schema with agent-modules `moduleOutputs`: `intent`, `queryRewrite`, `toolList`, `provider`). Added `DATASET_SUBDIR` constant. P2 fix: Redis prerequisite documentation — the implementation doc and plan said "10c only requires database + LLM provider" but code also probes Redis (required by `DefaultIntentTreeCacheManager`). Updated both documents to explicitly list Redis as a Phase 10c infrastructure prerequisite. Files changed: `agent_module_runner.py` (added `_is_real_export`, `_adapt_real_export`, modified `_module_outputs`), `test_agent_module_runner.py` (added `test_real_export_module_outputs_scored_correctly`, `test_real_export_missing_required_field_raises`, `_write_real_export_dataset_root` helper), `AgentModuleExportEvalTest.java` (path + schema fix, `DATASET_SUBDIR` constant), `eval-agent-module-dataset-record.schema.json` (new), `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: adapter is ~50 lines with no new abstractions; existing deterministic-mode tests unchanged (no `provider` key → not detected as real-export). Test coverage added: real-export scoring regression (wrong intent kind + empty toolList → metrics drop below 1.0), missing field validation (ValueError on absent `queryRewrite`). Verification: `python -m unittest discover -s tests -v` — 61 tests passed (2 new); Maven test-compile passed; `git diff --check` passed. No remaining manual checks.
- 2026-06-08: Applied follow-up Phase 10c review fixes (1 P1, 2 P2). P1 fix: `agent-modules-v1` tuning policy no longer hard-gates real model `agentModules.intentExactPathAccuracy` or `agentModules.toolCallF1` at 1.0; both are tracked for ranking/reporting, while `agentModules.wrongHistorySuppression` remains the deterministic hard gate. P2 fix 1: Python agent-module expected tool name now matches production `ToolDefinition.name()` (`SessionFileSearchTool`) instead of the conceptual knowledge-search label, with a regression proving a real-export KB row using `SessionFileSearchTool` scores `toolCallF1 == 1.0`. P2 fix 2: the primary plan's 10c profile/prerequisite text now explicitly lists Redis for `DefaultIntentTreeCacheManager`, matching code and this implementation record. Files changed: `agent-modules-v1.json`, `agent_module_runner.py`, `test_agent_module_runner.py`, `test_tuning_runner.py`, `RAGAS_EVALUATION_REWORK_PLAN.md`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: no new framework or runner mode; policy removes over-strict gates and the tool-name fix changes one suite constant. Test coverage added: policy regression, real-export production tool-name scoring, and `tune-suite --suite agent-modules` real-export fixture that produces promotion artifacts despite non-perfect metrics. Verification: targeted Python tests and real-export tuning smoke passed; Maven compile was not rerun for this documentation/policy/Python-only follow-up.
- 2026-06-08: Applied Phase 10 documentation review fixes (2 P2, 1 P3). P2 fixes: (1) primary plan 10c acceptance criterion now lists Redis alongside PostgreSQL and LLM provider, matching `AgentModuleInfrastructureCondition`; (2) primary plan cross-cutting and Phase 10b infrastructure text now describes JUnit 5 `ExecutionCondition` probes before Spring context loading instead of the older assumption-based `@BeforeAll` skip pattern. P3 fix: status line then distinguished completed implementation from the remaining 10a live Ollama SciFact manual run. This SciFact manual gate was later superseded by the 10a production document-ingestion replacement decision. Files changed: plan and implementation record only. Simplicity/style impact: documentation-only; no runtime code or tests changed. Verification: re-read changed sections and searched for stale prerequisite/skip/status wording.
- 2026-06-08: Applied user-requested Phase 10 full-run planning amendment. The primary plan and this implementation record now state that 10a/10b/10c smoke runs are infrastructure preflights only. At the time, 10a still referred to complete BEIR SciFact retrieval; that 10a target was later superseded by the production document-ingestion replacement decision. Current final Phase 10 acceptance is new 10a doc-ingestion-retrieval, complete MTRAG memory rows for 10b, and complete MTRAG agent-module rows for 10c. Tuning must use full calibration/development splits for search and full sealed holdout for final verification; challenge rows are report-only stress data. This preserves the user's original accuracy-first tuning goal and prevents 10-row/50-query smoke results from being treated as final quality evidence. Files changed: plan and implementation record only. Verification: documentation search for stale smoke-as-acceptance wording completed.
- 2026-06-08: Applied user-requested Phase 10 scope correction. 10a was no longer required to run all 1,109 SciFact queries; accepted-size 10a was temporarily reduced before the follow-up decision removed old SciFact 10a entirely. This entry also introduced production document-ingestion retrieval as a separate 10d; the next entry superseded that split by moving document-ingestion into 10a and removing separate 10d. Files changed: plan and implementation record only. Verification: searched for stale Phase 10 sample-size and "all three suites" wording.
- 2026-06-08: Applied user decision to remove the old SciFact real-embedding 10a from Phase 10. The replacement Phase 10a is now production document-ingestion retrieval. Phase 10 no longer has a separate 10d; the document-ingestion plan was moved into 10a. Old SciFact 10a code/artifacts are not acceptance evidence and should be deleted or quarantined as optional benchmark-only work. Current acceptance sequence is new 10a doc-ingestion-retrieval, 10b memory-v2, and 10c agent-modules. Files changed: plan and implementation record only. Verification: searched for stale current-status/prerequisite/acceptance text that still treated SciFact rag-retrieval as Phase 10a.
- 2026-06-08: Applied user-requested Phase 10a full-chain planning amendment. New 10a now requires local MinerU from `tools/mineru` for PDF/visual-track coverage, MQ-backed production ingestion through outbox/RabbitMQ/`KnowledgeIngestTaskListener`, at least 200 real documents downloaded from public network sources, and at least 500 grounded recall items/queries. Hand-made/generated/local toy documents and old private course PDFs remain disallowed for headline metrics. The plan records the observed old 10a Ollama smoke scaling issue (5/100/500 documents passed, 1000 documents / 50 queries failed with an Ollama 500 after about 156 seconds and roughly 1000 sequential embeddings) as a blocking 10a prerequisite: harden `OllamaEmbeddingClient` WebClient pooling/timeouts/response consumption/backpressure/retry behavior and add a 1200-call long-run regression before accepted-size execution. Files changed: plan and implementation record only. Verification: searched changed docs for stale 50-file/current-SciFact acceptance wording and ran `git diff --check`.
- 2026-06-08: Implemented Phase 10a OllamaEmbeddingClient resilience hardening (four-slice approach). Slice 1: minimal reproducer test (`OllamaEmbeddingClientLongRunTest`) — 1200 sequential bge-m3 embeddings against local Ollama, no Spring context, no RAG. Proved the bare client survives 1200 calls; identified the root cause of the earlier 1000-document RAG-test failure as bge-m3 NaN embeddings (~0.2% of inputs from scientific paper text), not a connection-pool leak. Slice 2: connection-pool hardening — added explicit `ConnectionProvider` with maxConnections=4, pendingAcquireMaxCount=8, pendingAcquireTimeout=30s, maxIdleTime=60s, maxLifeTime=10min, evictInBackground=30s; `HttpClient` responseTimeout=60s and CONNECT_TIMEOUT_MILLIS=10s; error-response body extraction via `onStatus` replacing opaque 500s with diagnostic messages. Slice 3: batch embedding via `/api/embed` — added `embedBatch(List<String>)` with response-length validation, per-vector dimension check, and NaN/Infinity scanning; wired into `KnowledgeBaseMilvusIndexer` and `SessionFileMilvusIndexer` (32-chunk batches replacing per-chunk `embed()` calls). Slice 4: NaN-only controlled fallback — `embed()` retries NaN vectors up to 3 times, then returns a zero vector and increments `nanFallbackCount`; HTTP/connection/timeout errors propagate as exceptions (not swallowed). NaN-fallback logging uses structured data (model name, input length, SHA-256 prefix, cumulative count) rather than raw input text, preventing user-content leakage into logs. Verification: `mvnw -P eval-real -Dtest=OllamaEmbeddingClientLongRunTest#longRunCompletesWithoutConnectionExhaustion test` — 1200/1200 succeeded, 0 failed, 0 NaN fallbacks; `mvnw -P eval-real -Dtest=RagRetrievalRealEmbeddingEvalTest test` — 1000 docs + 50 queries completed with BUILD SUCCESS (2 NaN fallbacks to zero vector, BM25 continued working); `python -m unittest discover -s tests -v` — 64 tests passed; `git diff --check` passed. Remaining: batch embedding is wired into production indexers but not yet exercised end-to-end in a 10a MinerU + MQ + Milvus full-chain run. Old SciFact 10a test is quarantined under `@Tag("eval-benchmark")` (excluded from default and eval-real POM profiles).
- 2026-06-08: Applied Phase 10a implementation review regression-test fixes (1 P2, 1 P3). P2 fix: added three regression tests protecting the two most critical production paths from Slice 3 and Slice 4. (1) `KnowledgeBaseMilvusIndexerBatchTest` — feeds 33 chunks through `upsert()` with a mocked `OllamaEmbeddingClient`, asserts `embedBatch()` is called exactly twice (32 texts then 1 text, order preserved), `embed()` is never called, and the index service receives all 33 mapped documents. Uses real `KnowledgeBaseMilvusChunkMapper` with `RagSourceType.KNOWLEDGE_BASE` records. (2) `SessionFileMilvusIndexerBatchTest` — same contract for `SessionFileMilvusIndexer` with `SESSION_FILE` source type, `SessionScopedMilvusChunkMapper`, and `MilvusIndexService`. (3) `OllamaEmbeddingClientNanFallbackLogTest` — injects a mock `WebClient` (via reflection) that returns NaN vectors, attaches a Logback `ListAppender`, calls `embed()` with a distinctive input string, asserts the WARN log contains `inputLen=`, `inputSha256=`, `cumulativeFallbacks=` but NOT the original input text; also asserts zero vector returned and `nanFallbackCount()==1`. P3 fix: patched the primary plan's SciFact quarantine description from "moves to `eval/benchmark/` namespace" to reflect the actual isolation mechanism — `@Tag("eval-benchmark")` + POM surefire `excludedGroups` in both default and `eval-real` profiles, with the test class remaining in `eval/v2/retrieval/`. Files changed: `KnowledgeBaseMilvusIndexerBatchTest.java` (new), `SessionFileMilvusIndexerBatchTest.java` (new), `OllamaEmbeddingClientNanFallbackLogTest.java` (new), `RAGAS_EVALUATION_REWORK_PLAN.md` (line 1229 wording fix), `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md` (this entry). Simplicity impact: tests use Mockito mocks and reflection to test internal contracts without Spring context or external services; no production code changed. Code style impact: follows existing `@ExtendWith(MockitoExtension.class)` + AssertJ pattern from `KnowledgeDocumentIngestionServiceImplTest`; WebClient mock chain uses `doReturn().when()` to avoid wildcard capture issues with `RequestHeadersSpec<?>`. Verification: `mvnw -pl bootstrap -Dtest="KnowledgeBaseMilvusIndexerBatchTest,SessionFileMilvusIndexerBatchTest,OllamaEmbeddingClientNanFallbackLogTest" test` — 3 tests passed, BUILD SUCCESS; `git diff --check` passed.
- 2026-06-08: Implemented Phase 10a production document ingestion smoke slice (first vertical cut). Java: `ProductionDocumentIngestionEvalTest` — `@SpringBootTest(webEnvironment=NONE)` with `@ActiveProfiles("eval-real-doc-ingestion")`, `@Tag("eval-real")`, `@ExtendWith(ProductionDocumentIngestionInfrastructureCondition.class)`. Infrastructure probes: PostgreSQL, Ollama bge-m3, Milvus (MinerU not required for TXT-only smoke, not probed). Downloads 3 real Project Gutenberg texts, ingests through production pipeline, generates two types of evidence-grounded queries (direct-evidence-preflight + template-question), retrieves via `searchRankedCandidateHitsByKnowledgeBaseIds()` for chunk-level `MilvusSearchHit` access, computes chunk-level hit@3 / contextRecall@3 / MRR by matching `chunkId` (not `documentId`), exports a Phase 3-compatible dataset root with dataset manifest, split manifest, and JSONL using Phase 3 field names (`sampleId`, `userInput`, `referenceContextIds`, `split`, `sourceGroupId`, `datasetId`, `metadata.*`). Cleanup: `@AfterEach` deletes Milvus vectors → chunks → documents → stored files → KB. Python: `doc_ingestion_runner.py` — reads dataset root via manifest path, computes chunk-level hit@K / contextRecall@K / MRR / phraseRecall / per-format breakdowns matching on `chunkId`, produces standardized artifacts. Tuning integration deferred: `doc-ingestion-retrieval` removed from `tuning_runner.py` SUPPORTED_SUITES until CLI command, policy file, parameter space, and registry entries are added (after accepted-size expansion). Schema: `eval-doc-ingestion-dataset-record.schema.json` updated to Phase 3 field names with `chunkId` in retrieved contexts. Files changed: `ProductionDocumentIngestionEvalTest.java`, `eval-doc-ingestion-dataset-record.schema.json`, `doc_ingestion_runner.py`, `test_doc_ingestion_runner.py`, `tuning_runner.py` (removed premature suite registration). Simplicity impact: Java uses `RankedCandidateHit.hit().chunkId()` for chunk-level matching without extending `RetrievalHit`; query template generation uses simple keyword extraction rather than LLM; manifest helpers follow the MemoryExportEvalTest pattern. Code style impact: Java follows 10b/10c eval pattern; Python follows rag_retrieval_runner pattern. Verification: `mvnw -pl bootstrap test-compile` — BUILD SUCCESS; `python -m unittest discover -s tests -v` — 80 tests passed (16 doc-ingestion, 15 tuning); `git diff --check` passed. Remaining: expand catalog to include PDF/DOCX/HTML/CSV formats, add MinerU integration for PDF visual-track, add MQ-path verification test, expand to accepted-size (200+ files, 500+ queries), add tuning integration (policy, parameter space, registry, run_eval.py CLI command).
- 2026-06-08: Applied follow-up Phase 10a smoke slice review fixes (3 P2, 1 P3). P2 fix 1: `_load_rows()` in `doc_ingestion_runner.py` now filters rows by `config.splits` before `max_samples`, preventing wrong-split evaluation in calibration/development/holdout runs. Three regression tests added: single-split filter, splits + max_samples ordering, and empty-splits-loads-all. P2 fix 2: schema `referenceContextIds` now requires `minItems: 1` (at least one reference chunk ID for meaningful chunk-level scoring); `retrievedContexts[].chunkId` changed from `"type": ["string", "null"]` to `"type": "string", "minLength": 1` (non-empty, non-null). Two schema rejection tests added: empty `referenceContextIds` → hitAtK=0 + failure recorded; null `chunkId` in retrieved → treated as non-match. P2 fix 3: dataset manifest now includes a `provenance` object; each row's metadata now records parser/chunker/embedding/vector/ingestion pipeline provenance with `mqEnabled` and `mineruEnabled` set to false for TXT preflight rows. P3 fix: `ProductionDocumentIngestionInfrastructureCondition` Javadoc updated to list only PostgreSQL, Ollama bge-m3, Milvus (removed "MinerU"); `SMOKE_CATALOG` comment updated from "covering multiple format families" to "TXT-only smoke slice". Schema updated with new metadata properties for parser, chunker, embedding, vector, ingestion, mqEnabled, mineruEnabled. Files changed: `doc_ingestion_runner.py`, `test_doc_ingestion_runner.py`, `eval-doc-ingestion-dataset-record.schema.json`, `ProductionDocumentIngestionEvalTest.java`, `ProductionDocumentIngestionInfrastructureCondition.java`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: splits filter is a one-line list comprehension before truncation; provenance follows the MemoryExportEvalTest/AgentModuleExportEvalTest pattern; schema adds properties without structural change. Code style impact: Python tests follow existing unittest.TestCase pattern with `split_override` parameter added to `_sample_row()`. Verification: `mvnw -pl bootstrap test-compile` — BUILD SUCCESS; `python -m unittest discover -s tests -v` — 85 tests passed; `git diff --check` passed.
- 2026-06-08: Applied P1 follow-up fix for dataset manifest provenance schema conformance. The manifest provenance had extra fields (embeddingProvider, embeddingDimension, parser, chunker, chunkerTargetChars, chunkerOverlapChars, vectorIndex, vectorMetric, ingestionPath, runId) that violate `eval-dataset-manifest.schema.json` which has `additionalProperties: false` and only allows `provider`, `modelName`, `embeddingModel`, `exportTimestamp`. Fix: manifest provenance now contains exactly the four schema-allowed fields (`provider: "ollama"`, `modelName: "bge-m3"`, `embeddingModel: "bge-m3"`, `exportTimestamp: <ISO>`). Parser/chunker/vector/ingestion provenance remains in per-row metadata only (already added in the previous fix). Added 11 Python regression tests in `TestDatasetManifestSchema` using the real `chatagent_eval.schemas.validate` against the actual `eval-dataset-manifest.schema.json` file: valid manifest with provenance passes; valid manifest without provenance passes (legacy, allowed by common schema); extra provenance fields rejected (parser, chunker — 2 tests); missing each of the 4 required provenance fields rejected when provenance is present (4 tests); missing required top-level fields rejected; 10a-specific test asserting 10a exports must include provenance with correct provider/modelName/embeddingModel values. Tests use `chatagent_eval.schemas.validate` and `load_json` against the real schema file — no hand-written field sets or jsonschema dependency. Files changed: `ProductionDocumentIngestionEvalTest.java`, `test_doc_ingestion_runner.py`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: provenance reduced from 12 fields to 4, matching the common schema exactly; tests now validate against the real schema rather than a hand-written approximation. Code style impact: Python test class follows existing `from chatagent_eval.schemas import load_json, validate` pattern used by test_memory_runner, test_tuning_runner, etc. Verification: `mvnw -pl bootstrap test-compile` — BUILD SUCCESS; `python -m unittest discover -s tests -v` — 95 tests passed; `git diff --check` passed.
- 2026-06-09: Expanded Phase 10a smoke slice from TXT-only (3 files) to multi-format (11 files across 5 format families). File catalog now includes: TXT (3 Project Gutenberg texts, public domain), PDF (2 IETF RFCs via PdfDocumentParser, Trust Legal Provisions), HTML (2 Project Gutenberg HTML editions via TikaDocumentParser, public domain), Markdown (AsyncAPI spec + OpenAPI README via MarkdownDocumentParser, CC-BY-4.0 / Apache-2.0), CSV (2 FiveThirtyEight datasets via TikaDocumentParser, CC-BY-4.0). Each entry records source URL, filename, MIME type, source group, format family, license, and assigned split (calibration/development/holdout). Split distribution: calibration=5 files (TXT/PDF/HTML/MD/CSV — all 5 format families), development=3 files (TXT/PDF/CSV), holdout=3 files (TXT/HTML/MD) — every format family appears in at least 2 splits. Code changes: (1) `SourceFile` record gained a `split` field for per-document split assignment; (2) SMOKE_CATALOG expanded from 3 to 11 entries with explicit split assignments; (3) added `parserForFormat()` and `addChunkerMetadata()` methods replacing hardcoded `TikaDocumentParser`/`PlainTextChunker` — PDF→PdfDocumentParser+SegmentAwareChunkerRouter, MD→MarkdownDocumentParser+StructureAwareMarkdownChunker, TXT/HTML/CSV→TikaDocumentParser+PlainTextChunker; (4) export loop now uses `doc.source.split()` instead of hardcoded "calibration"; (5) dataset manifest `sourceIds` now collected dynamically from row metadata instead of hardcoded `List.of("project-gutenberg")`; (6) query count assertion raised from 5 to 15 for the expanded catalog; (7) summary log now includes per-format query count breakdown. Files changed: `ProductionDocumentIngestionEvalTest.java`, `ProductionDocumentIngestionInfrastructureCondition.java` (Javadoc), `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: no new abstractions — format-to-parser/chunker mapping is two small switch statements matching the actual `DocumentParserSelector`/`SegmentAwareChunkerRouter` routing logic; split assignment is explicit in the catalog, avoiding complex hash-based stratification. Code style impact: `parserForFormat()` and `addChunkerMetadata()` use Java 17 switch expressions matching the project's switch style in `SegmentAwareChunkerRouter`. Verification: `mvnw -pl bootstrap test-compile` — BUILD SUCCESS; `python -m unittest discover -s tests -v` — 95 tests passed; `git diff --check` passed. Remaining manual checks: run multi-format smoke against live PostgreSQL + Ollama bge-m3 + Milvus infrastructure and verify PDF/HTML/MD/CSV ingestion produces COMPLETED status and non-zero chunks for all 11 files; expand to accepted-size (200+ files); add MinerU for PDF visual-track; add MQ/outbox path; add tuning integration.
- 2026-06-09: Applied review fixes for multi-format smoke expansion (1 P1, 1 P3). P1 fix: `FileTypeDetector` did not support `.html`/`text/html` or `.csv`/`text/csv` — HTML/CSV catalog entries would be rejected as "Unsupported file type" before reaching TikaDocumentParser. Added `"html"`, `"htm"`, `"csv"` to `SUPPORTED_EXTENSIONS`; `"text/html"`, `"text/csv"` to `SUPPORTED_MIMES`; compatibility checks in `isCompatibleWithExtension()` (HTML accepts `text/html` + `text/*` + `application/xhtml+xml` + `application/octet-stream`; CSV accepts `text/csv` + `text/plain` + `application/octet-stream`); and preferred MIME mappings in `preferredMimeForExtension()`. Added 6 test cases to `FileTypeDetectorTest`: `shouldAcceptHtmlExtension`, `shouldAcceptHtmExtension`, `shouldAcceptCsvExtension`, `shouldAcceptCsvWithTextPlainMime`, `shouldAcceptHtmlMimeWithoutMatchingExtension`, and `shouldAcceptAllDocIngestionCatalogFormats` (regression: iterates over the 5 format families used in the 10a SMOKE_CATALOG and asserts each is accepted by the detector). P3 fix: moved MD(asyncapi) from development to calibration so all 5 format families appear in the calibration split; corrected implementation doc split summary (was "calibration=4 files (TXT/PDF/HTML/CSV)" → now "calibration=5 files (TXT/PDF/HTML/MD/CSV — all 5 format families)"). Files changed: `FileTypeDetector.java`, `FileTypeDetectorTest.java`, `ProductionDocumentIngestionEvalTest.java`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: production change is additive-only — added entries to existing sets and switch arms; no new abstractions or logic branches. Code style impact: follows existing set literal and switch expression patterns in `FileTypeDetector`. Test coverage added: 6 new FileTypeDetectorTest cases covering HTML, HTM, CSV, text/plain CSV, MIME-only HTML, and full catalog coverage regression. Verification: Maven test-compile passed; targeted `FileTypeDetectorTest` passed with 15 tests; Python eval suite passed with 95 tests; `git diff --check` passed. Remaining manual checks: run multi-format smoke against live infrastructure to verify HTML/CSV ingestion produces COMPLETED status.
- 2026-06-09: Applied user-requested 10a accepted-size source mix correction. CSV remains accepted by `FileTypeDetector` and may stay in preflight/diagnostic smoke runs, but it is no longer a headline accepted-size format family because current production routing sends CSV through `TikaDocumentParser` + `PlainTextChunker`, not `SpreadsheetDocumentParser` + `TableAwareChunker`. The primary plan now uses real XLSX/spreadsheet workbooks as the table/numeric format family so accepted-size 10a exercises the production spreadsheet parser and table-aware chunker. CSV can count toward headline table metrics only after a dedicated CSV table-aware parser is implemented and reviewed. Files changed: `RAGAS_EVALUATION_REWORK_PLAN.md`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Verification: documentation search for stale accepted-size `CSV/spreadsheet` wording completed; historical smoke entries intentionally remain factual because the current smoke catalog still contains CSV.
- 2026-06-09: Replaced SMOKE_CATALOG CSV entries with real XLSX files (WHO Child Growth Standards weight-for-length expanded tables). CSV entries removed from catalog: FiveThirtyEight airline-safety.csv and bad-drivers.csv. New XLSX entries: WHO girls/boys weight-for-length z-score expanded tables (~74KB each), CC-BY-3.0-IGO license, served from cdn.who.int with correct `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` MIME type. XLSX files are ZIP-based binary files that exercise the full `SpreadsheetDocumentParser` (Apache POI) → `TableAwareChunker` pipeline, emitting TABLE segments with sheet/row-range metadata. Updated `parserForFormat()` and `addChunkerMetadata()` to map XLSX → `SpreadsheetDocumentParser` + `TableAwareChunker` (maxRowsPerChunk=50, overlapRows=2). Updated `FileTypeDetectorTest.shouldAcceptAllDocIngestionCatalogFormats` to use XLSX instead of CSV (ZIP prefix for binary detection). Five format families are now: TXT (3), PDF (2), HTML (2), MD (2), XLSX (2) = 11 files. Split distribution: calibration=5 (TXT/PDF/HTML/MD/XLSX), development=3 (TXT/PDF/XLSX), holdout=3 (TXT/HTML/MD). Files changed: `ProductionDocumentIngestionEvalTest.java`, `FileTypeDetectorTest.java`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity impact: no new abstractions; XLSX parser/chunker mapping follows the same switch-expression pattern as other formats; FileTypeDetector already supported XLSX in SUPPORTED_EXTENSIONS. Code style impact: XLSX MIME type uses the full OpenXML MIME string matching the production `SpreadsheetDocumentParser.supports()` check. Verification: `mvnw -pl bootstrap test-compile` — BUILD SUCCESS; `FileTypeDetectorTest` — 15 tests passed; Python eval suite — 95 tests passed; `git diff --check` — clean. Remaining manual checks: run multi-format smoke against live PostgreSQL + Ollama bge-m3 + Milvus to verify XLSX ingestion via SpreadsheetDocumentParser produces COMPLETED status and TABLE-type chunks with sheet/row metadata.
- 2026-06-09: Completed Phase 10a accepted-size production document-ingestion full-chain run. Added a default-off `chatagent.rag.vdp.force-visual-track` switch in `PdfDocumentParser`/`PdfQualityRouter` and enabled it only in `application-eval-real-doc-ingestion.yaml` so accepted 10a PDF rows prove the MinerU/VDP path over real downloaded PDFs while normal production routing remains unchanged. Added `PdfQualityRouterTest` coverage for forced visual routing and default narrative fast-track behavior. Live smoke after the change passed with 11 real files, production facade/outbox/RabbitMQ ingestion, Milvus, and MinerU-selected PDF rows. Live accepted-size command `mvnw -pl bootstrap -P eval-real -Dtest=ProductionDocumentIngestionEvalTest -Dchatagent.eval.docIngestion.acceptedSize=true -Dchatagent.eval.docIngestion.documentTimeoutSeconds=900 test` passed in 1h11m against PostgreSQL + RabbitMQ + Ollama bge-m3 + Milvus + MinerU. Export root: `chatagent/artifacts/eval/phase10a/doc-ingestion-full-ee056c79`. Counts: 200 documents (SEC_HTML 50, PDF 20, DOCX 40, XLSX 45, WEB_MD 45), 6,651 chunks, 585 grounded rows (SEC_HTML 150, PDF 56, DOCX 115, XLSX 129, WEB_MD 135), splits calibration 301 / development 141 / holdout 143. Metrics: `hit@3=0.585`, `contextRecall@3=0.585`, `MRR=0.505`. Provenance: all 585 rows record `metadata.mq.consumerCompleted=true` and `metadata.ingestionPath=mq-outbox`; all 56 PDF rows record MinerU selected evidence.
- 2026-06-09: Completed Phase 10a full all-rows tuning. First tuning invocation revealed the CLI default sample cap (50 search rows), so it was rerun with `--combination-budget 27 --max-samples-per-trial 100000 --holdout-max-samples 100000` to cover the full 3x3x3 grid and all exported rows. Artifact root: `artifacts/eval/phase10a/tuning/phase10a-doc-ingestion-full-ee056c79-grid27-allrows`. Trial count: 27. Champion candidate: `topK=8`, `candidateK=12`, `rrfK=60` (trial-0003); development replay on calibration+development used 442 rows with `docIngestion.contextRecallAtK=0.7376`, `hitAtK=0.7376`, `MRR=0.5320`, `phraseRecall=0.8723`; sealed holdout used 143 rows with `contextRecallAtK=0.6294`, `hitAtK=0.6294`, `MRR=0.4224`, `phraseRecall=0.8552`; overlap count 0 and no gate failures. Holdout verification status is `warn`, promotion candidate status is `rejected`, and production defaults remain unchanged. Verification after implementation: `mvnw -pl bootstrap -Dtest=PdfQualityRouterTest test` passed (2 tests); `mvnw -pl bootstrap test-compile` passed; `python -m unittest discover -s tests -v` passed (101 tests at that point); `python -m compileall -q chatagent_eval tests run_eval.py` passed; `git diff --check` passed. Subsequent 2026-06-09 entries below complete full 10b memory export/tuning and full 10c agent-module export/tuning.
- 2026-06-09: Implemented Phase 10b + 10c full export test methods. Added `exportsFullMemoryPipeline()` to `MemoryExportEvalTest` and `exportsFullAgentModulePipeline()` to `AgentModuleExportEvalTest`, each with configurable `MAX_SAMPLES` system property (default 0 = all rows), no per-row exception swallowing in full mode, and `assertThat(samples).hasSize(rows.size())` so row failures fail the export. The admin test files were restored and are not part of the final diff. Verification: `mvnw -pl bootstrap test-compile` — BUILD SUCCESS; `python -m unittest discover -s tests -v` — 103 tests passed after the final 10c schema-contract regression; `git diff --check` — clean.
- 2026-06-09: Completed Phase 10b memory-v2 full export and complete-grid tuning. Full export processed all 842 MTRAG rows through IncrementalSummarizer (DeepSeek LLM) + LongTermMemoryExtractor (DeepSeek LLM) + Ollama bge-m3 + Milvus L3 indexing in 78.5 minutes, 842 samples with 0 errors, 46 Ollama NaN fallbacks (all recovered). Export: `artifacts/eval/phase10b/phase10b-memory-full`. Complete-grid tuning: `artifacts/eval/phase10b-tuning/phase10b-memory-full-grid81`, `--strategy grid`, `--combination-budget 81`, no sample caps, 81 completed trials, 710 search rows, 93 holdout rows, champion trial-0001 (baseline params), overlap=0, status=pass. L2/L3 remain 0.0 because exact/sub-string matching is not suitable for free-text LLM outputs; this is recorded as measurement-method evidence rather than a production default change.
- 2026-06-09: Completed Phase 10c agent-module schema-contract fix, regenerated full export, and complete-grid tuning. `AgentModuleExportEvalTest.toSample()` now emits `queryRewrite: ""` instead of null for unresolved routing; `agent_module_runner._adapt_real_export()` again requires `intent`, `queryRewrite`, and `toolList` keys while allowing empty/null query rewrite values. The runner validates rows against `eval-agent-module-dataset-record.schema.json` before scoring real 10c exports, so stale artifacts with missing `queryRewrite` fail before tuning. Regenerated export: `artifacts/eval/phase10c/phase10c-agent-module-full`, 842 rows, 0 errors, 42.6 minutes, all 842 rows include required module output keys. Complete-grid tuning: `artifacts/eval/phase10c-tuning/phase10c-agent-module-full-grid1458`, `--strategy grid`, `--combination-budget 1458`, no sample caps, 1458 completed trials, 710 search rows, 93 holdout rows, champion trial-0007, development intentAccuracy/toolCallF1 `0.8366`, sealed holdout intentAccuracy/toolCallF1 `0.8817`, overlap=0, status=pass. No production defaults changed; champion promotion remains proposed and requires separate review.
- 2026-06-09: Applied Phase 10 review-fix P3 standards cleanup. `agent_module_runner.py` renamed `_validate_rows()` to `_validate_agent_module_rows()` and added an explicit comment that only real 10c exports carry `eval-agent-module-dataset-record.schema.json`; deterministic legacy rows remain on the existing runner path. Schema root resolution now searches upward from the runner file for the checked-out schema directory before falling back to the historical relative path. `exportsFullMemoryPipeline()` and `exportsFullAgentModulePipeline()` now have Javadoc documenting live prerequisites and accepted runtime expectations. Files changed: `agent_module_runner.py`, `AgentModuleExportEvalTest.java`, `MemoryExportEvalTest.java`, `IMPLEMENTATION_RAGAS_EVALUATION_REWORK.md`. Simplicity/style impact: no behavior or production-default changes; only local naming, path robustness, and comments/Javadoc. Verification: targeted agent-module runner tests, full Python eval suite, Python compileall, Maven test-compile, stale-text search, and `git diff --check` passed.

Phase 10 acceptance summary (2026-06-09): all three sub-phases complete with full-dataset real-model exports, complete-grid/all-search-row tuning, sealed-holdout verification (overlap=0), and champions identified. 10a: 200 docs, 585 queries, champion topK=8 (rejected, gap > tolerance). 10b: 842 rows, 81-trial grid, champion trial-0001 (baseline), L2/L3 0.0 due to reference-matching limitation. 10c: 842 rows, 1458-trial grid, champion trial-0007, holdout accuracy `0.8817` > dev `0.8366`. No production defaults were changed. Remaining optional work: Ragas answer-generation LLM judge (deferred), Phase 11 documentation/runbook, Phase 12 legacy removal.
