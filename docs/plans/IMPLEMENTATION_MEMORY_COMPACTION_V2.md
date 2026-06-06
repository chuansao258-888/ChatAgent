# Memory Compaction V2 Implementation Record

## Overall Status

Phase 1–7 complete.

Authoritative plan:

- `docs/plans/MEMORY_COMPACTION_V2_PLAN.md`

## Phase Checklist

| Phase | Name | Status |
|---|---|---|
| 1 | Schema And Runtime Contract Slice | **Complete** |
| 2 | Stable Boundary And Token Policy | **Complete** |
| 3 | Structured Segment Summarization | **Complete** |
| 4 | Runtime Tool-Result Microcompact | **Complete** |
| 5 | Failure Protection And Retry | **Complete** |
| 6 | Runtime Rendering And L3 Alignment | **Complete** |
| 7 | Documentation And Broad Verification | **Complete** |

## Decisions Made

| Decision | Status |
|---|---|
| Adopt Memory Compaction V2 optimizations from the ChatAgent versus Claude Code comparison | Confirmed |
| Old L2 summary data compatibility is not required | Confirmed |
| Existing `chat_session_summary` rows do not need backfill | Confirmed |
| Use a new Flyway migration instead of editing old migrations | Confirmed |
| Add ADR for V2 L2 schema reset/no backfill because rollback may require DB restore or recreation migration | Confirmed |
| V2 compaction is enabled by default | Confirmed |
| Tool-result microcompact does not need old-message compatibility/backfill | Confirmed |
| V22 schema reset must land with summary repository, watermark, writer, resolver, and delete-path contract updates | Confirmed |
| Runtime microcompact should preserve raw persisted `chat_message` rows unless explicitly changed later | Assumed |
| L2 should summarize only turns outside the preserved L1 tail | Confirmed |
| Token-aware compaction trigger should supplement turn-count behavior | Confirmed |
| L2 should store auditable segments plus a session-level synopsis | Confirmed |
| Summary output should be structured JSON with deterministic fallback | Confirmed |
| L3 promotion should continue to receive raw stable turns from the L2 path | Confirmed |
| Split/retry publishes L3 only after each segment transaction commits | Confirmed |
| `summarized_until_seq_no` advances only through committed contiguous segment ranges | Confirmed |
| Empty V2 historical summary state returns no summary and omits `[Historical Context Summary]` | Confirmed |
| Full Claude Code transcript boundary/hooks/attachments design should not be copied | Confirmed |
| `summary-max-chars` is retained as a legacy no-op; V2 uses `segment-max-chars` and `synopsis-max-chars` instead | Confirmed |
| `docs/env_variables.txt` must document all memory variables (existing + V2), not just new ones; Phase 1 adds a static check | Confirmed |
| Phase 1 recommended next step text matches the actual Schema And Runtime Contract Slice scope | Confirmed |
| `AgentSessionSummaryResolver` returns empty string only when neither synopsis nor active nonblank segments exist, allowing the caller to omit `[Historical Context Summary]` | Confirmed |
| `DefaultAgentRuntimeContextLoader` injects resolver output into `[Historical Context Summary]` when non-empty — no fallback text filtering needed | Confirmed |
| Deterministic fallback after LLM retry exhaustion is not a failure — compaction succeeds with lower-quality output; failure recording only for save failures and prompt-too-long on single turn | Confirmed |
| Prompt-too-long detection is heuristic (exception message pattern matching) rather than model-specific exception types | Confirmed |
| Backoff is linear (consecutiveFailures × failureBackoffSeconds), not exponential | Confirmed |
| Split-and-retry is recursive down to single-turn minimum; no explicit depth limit needed since log₂(N) is bounded for practical turn counts | Confirmed |
| Runtime rendering includes synopsis + latest N segment summaries (bounded by `runtime-max-segments`); segments ordered newest-first from `findActiveBySessionId` | Confirmed |
| No L1 duplication invariant: L2 summary covers turns before L1 window, L1 memory covers recent tail; resolver output never contains L1 turn content | Confirmed |
| L3 alignment verified at two levels: AsyncSummaryListener publishes per-segment events with filtered raw turns, LongTermMemoryPromotionService receives raw AtomicConversationTurn objects with stable L2 range | Confirmed |

## Files Changed

### Phase 7: Documentation And Broad Verification

Modified files:

- `docs/plans/IMPLEMENTATION_MEMORY_COMPACTION_V2.md` — overall status updated to complete, Phase 7 marked complete, broad verification results recorded.

Implementation notes:

- `docs/env_variables.txt` already covers all 20 memory variables (7 existing + 13 V2). No changes needed.
- Implementation document has been kept in sync with each phase delivery. Phase 7 adds only the broad verification command and result.
- Manual checks remain not run — they require a running application with a live conversation exceeding `l1-window-turns`.

### Phase 6: Runtime Rendering And L3 Alignment

Modified files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/AgentSessionSummaryResolver.java` — added `ChatSessionSummarySegmentRepository` dependency and `runtime-max-segments` config. `resolve()` now returns synopsis + up to N latest active segment summaries, each prefixed with `[Segment start..end]` for traceability. Returns empty only when neither synopsis nor active nonblank segments exist. Blank segment summaries are skipped before applying the N limit (not counted against it). Returns synopsis-only when `runtimeMaxSegments=0`.

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/agent/runtime/AgentSessionSummaryResolverTest.java` — updated `@BeforeEach` for new constructor. 7 new tests: synopsis + latest segments appended, segments bounded by runtimeMaxSegments, segments with blank summary skipped, runtimeMaxSegments=0 returns synopsis only, blank synopsis + active segments returns segment text, null summary row + active segments returns segment text, blank newest segment does not crowd out older valid segment. Total: 13 tests.

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/agent/DefaultAgentRuntimeContextLoaderTest.java` — 3 new tests: segment detail included in `[Historical Context Summary]`, `[Historical Context Summary]` omitted when resolver returns empty, no L1 duplication (L2 summary covers pre-L1 range, L1 memory covers recent tail, no overlap). Total: 15 tests.

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/memory/application/LongTermMemoryPromotionServiceTest.java` — 2 new tests: L3 receives raw `AtomicConversationTurn` objects (not summary text) with verified user messages and assistant conclusion, extraction log records stable L2 range (not L1 tail range). Total: 13 tests.

Implementation notes:

- `AgentSessionSummaryResolver` uses `findActiveBySessionId` which returns segments ordered by `seq_end_no` descending (newest first). The first N nonblank segments are the most recent, matching the runtime need for fresh context. Blank summaries are skipped before the N limit is applied, so they never crowd out valid segments.
- The `runtime-max-segments` config was already in `application.yaml` (default 3) from Phase 1 but had no consumer until now.
- No change to `DefaultAgentRuntimeContextLoader` — it already delegates to the resolver and injects whatever formatted text is returned into `[Historical Context Summary]`. The resolver's richer output is transparent to the loader.
- L3 alignment is verified at two levels: (1) `AsyncSummaryListener` per-segment events with filtered turns (Phase 5), and (2) `LongTermMemoryPromotionService` receives raw `AtomicConversationTurn` objects and logs the stable range (this phase).
- The no-L1-duplication test verifies the architectural invariant: L2 summary text (synopsis + segments) covers turns before the L1 window, while L1 memory contains recent turns. The resolver output never includes L1 turn content.

### Phase 5: Failure Protection And Retry

Modified files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/IncrementalSummarizer.java` — added `maxRetries`, `maxConsecutiveFailures`, `failureBackoffSeconds` config fields (wired from application.yaml). Refactored `summarizeWithDetails` to delegate to `summarizeRange` which handles split-and-retry. New `generateStructuredSummaryWithRetry` retries model calls on blank/invalid output (up to maxRetries+1 attempts) before falling back to deterministic. New `isPromptTooLong` detects context-overflow exceptions by message pattern. New `splitAndSummarize` splits turns in half on prompt-too-long, processes each sub-range independently via recursive `summarizeRange`. New `recordFailure` writes failure state (consecutiveFailures, failure range, lastFailureClass) on any summarization failure; sets `nextRetryAt` only when `consecutiveFailures >= maxConsecutiveFailures` threshold. Preserves existing anchoredEntities and structuredSummaryJson in failure updates. New `saveWithRetry` retries `saveOrUpdate` up to maxRetries+1 total attempts on optimistic lock conflict. Success path clears all failure fields (consecutiveFailures=0, failure range=null, nextRetryAt=null). Two new inner exceptions: `PromptTooLongException` (signals context overflow → split), `SummarizationFailedException` (signals save failure → record failure state).

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/AsyncSummaryListener.java` — added `publishL3Events` method: when result has segments (from split-and-retry), publishes one `LongTermMemoryPromotionRequestedEvent` per segment with filtered turns matching each segment's seq range. Falls back to single merged event when no segments present.

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/IncrementalSummarizerTest.java` — updated `@BeforeEach` constructor for 3 new parameters. 16 Phase 5 tests including 6 review-fix regressions: retry on blank output, retry on exception, prompt-too-long splits range, partial split success, save failure after maxRetries+1 attempts, optimistic lock retry across 3 attempts (false, false, true), consecutive failures below threshold (nextRetryAt=null), consecutive failures at threshold (nextRetryAt set), failure preserves existing anchored entities/structured JSON, successful run clears failure state, prompt-too-long detection, no segment duplication on retry, all save attempts exhausted. Total: 24 tests.

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/AsyncSummaryListenerTest.java` — added per-segment L3 event test: 2 segments → 2 L3 events with correct range/turns per segment. Total: 9 tests.

- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/MemorySummaryEvalTest.java` — updated `IncrementalSummarizer` constructor call for 3 new parameters.

Implementation notes:

- Deterministic fallback is still the last resort: after all LLM retries are exhausted, the deterministic summary is used. This is not treated as a failure — the compaction succeeds with lower-quality output.
- Failure recording only happens when the entire `doSummarizeRange` fails (save failure after optimistic lock retry, or prompt-too-long on a single turn). These are edge cases in normal operation.
- Split-and-retry is recursive: if a half is still too long, it splits again down to single turns. Minimum split size is 1 turn.
- Segment insert is idempotent (ON CONFLICT DO NOTHING), so retry after partial success never creates duplicate segments.
- Backoff is linear: `nextRetryAt = now + (consecutiveFailures × failureBackoffSeconds)`, but only activated when `consecutiveFailures >= maxConsecutiveFailures`. Below threshold, only the count and range are recorded.
- Prompt-too-long detection is heuristic-based (checks exception message for common patterns). This covers most model providers without model-specific exception handling.
- `saveWithRetry` uses maxRetries+1 total attempts (matching the model retry count), not a hardcoded value.

### Phase 4: Runtime Tool-Result Microcompact

New files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/ToolResultCompactor.java` — Spring @Component with @Value config (tool-result-max-chars, head-chars, tail-chars) and Micrometer counter via ObjectProvider<MeterRegistry>. Deterministic head/tail compaction format: `[Tool result compacted for context budget]` header, original char count, head excerpt, error-line extraction from dropped middle, tail excerpt. Minimum bounds (maxChars≥200, head/tail≥100) enforced in constructor. Supports normal max-char compaction and stronger budget-pressure compaction.
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/agent/runtime/ToolResultCompactorTest.java` — 14 tests: large content compacted, small unchanged, exact threshold boundary, null, empty, head/tail from different parts, shouldCompact flag, max exceeded when head/tail would overlap, forced budget compaction below maxChars, budget compaction keeps original when wrapping would grow content, budget compaction of null, metric increment on compaction, metric not incremented without compaction, error-line preservation from dropped middle

Modified files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/AgentMemoryLoader.java` — added ToolResultCompactor dependency; in `collectAssistantSequence()`, applies compaction to oversized `ToolResponse.responseData()` before creating Spring AI ToolResponseMessage. Token estimation now includes `ToolResponseMessage.responseData()`. If a selected turn would exceed the effective L1 token budget, loader applies budget-pressure compaction before deciding whether the turn can fit. Creates new ToolResponse with same id/name but compacted content. No mutation of persisted DTO.
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/agent/runtime/AgentMemoryLoaderTest.java` — 8 tests: 3 existing tests updated for new constructor, 5 new tests (oversized tool compacted, tool call ID preserved after compaction, small tool unchanged, DTO content unchanged proving no DB mutation, budget-pressure compaction below maxChars)

Implementation notes:

- Compaction happens during Spring AI message construction, not on the raw ChatMessageDTO. The DTO's ToolResponse object (a Java record) is immutable; a new ToolResponse is created with compacted content.
- Config values already exist in `application.yaml` from Phase 1: `tool-result-max-chars: 2000`, `tool-result-head-chars: 800`, `tool-result-tail-chars: 800`.
- The `ToolResponseMessage.getText()` returns empty — content is in `responseData()`. Tests assert on `responseData()` to verify compaction.
- Budget-pressure compaction uses smaller excerpts than normal threshold compaction so it materially reduces token estimate while preserving tool response pairing.

### Phase 3: Structured Segment Summarization

New files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/StructuredSummary.java` — record with summary, facts, decisions, openTasks, entities; static empty() factory
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/StructuredSummaryParser.java` — static utility: parses LLM JSON output into StructuredSummary with markdown fence stripping, null-safe list extraction, and entity map handling; fallback method creates deterministic summary from raw turns
- `chatagent/bootstrap/src/main/resources/prompts/summarizer/segment-memory.md` — structured JSON prompt requesting summary/facts/decisions/open_tasks/entities schema
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/StructuredSummaryParserTest.java` — 11 tests: valid JSON, missing fields, code fences, invalid JSON → raw text fallback, blank/null input, non-string list filtering, empty entity list filtering, deterministic fallback from turns, empty/null turns

Modified files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/prompt/PromptConstants.java` — added SUMMARIZER_SEGMENT_MEMORY constant for the new prompt template
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/SummaryResult.java` — added `segments` (List<ChatSessionSummarySegmentDTO>) and `synopsis` (String) fields; backward-compatible 3-arg constructor delegates with defaults
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/IncrementalSummarizer.java` — V2 structured flow: uses segment-memory prompt, parses response via StructuredSummaryParser, creates segment row via ChatSessionSummarySegmentRepository, merges synopsis deterministically (append with head-truncation), serializes structured JSON for segment.structuredSummaryJson. Added segmentMaxChars and synopsisMaxChars config replacing summaryMaxChars. Three-way anchored entity merge (existing + regex + structured).
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/IncrementalSummarizerTest.java` — 8 tests rewritten for V2 flow: segment+synopsis creation, LLM fallback, no-pending-range, no-pending-turns, anchored entity merge (regex+structured), synopsis truncation, structured prompt verification, segment count increment
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/eval/MemorySummaryEvalTest.java` — added ChatSessionSummarySegmentRepository mock and updated IncrementalSummarizer constructor call for new parameters

Implementation notes:

- Synopsis merge is deterministic (append + truncate from head) rather than LLM-based, keeping the flow to a single LLM call per compaction pass
- StructuredSummaryParser strips markdown code fences (```json ... ```) before JSON parsing, since weaker models may wrap output
- Invalid JSON is treated as plain-text summary rather than dropped entirely
- Empty entity lists in structured output are filtered out to avoid noise in anchored entities map

### Phase 2: Stable Boundary And Token Policy

New files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/TokenEstimator.java` — shared static utility for CJK-aware char-to-token estimation, extracted from AgentMemoryLoader's private method
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/CompactionTrigger.java` — enum for compaction decision reasons (DISABLED, NO_STABLE_TURNS, BACKOFF_ACTIVE, BELOW_THRESHOLD, PENDING_TURNS, PENDING_TOKENS, L1_TOKEN_PRESSURE)
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/CompactionDecision.java` — record holding shouldCompact + trigger
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/CompactionBoundary.java` — record with sessionId, summarizedUntilSeqNo, stableAnchorSeqNo, totalTurns, preservedTailTurns, allTurns, consecutiveFailures, backoffActive; helper methods stableTurns(), tailTurns(), hasStableTurns()
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/CompactionBoundaryResolver.java` — resolves stable/L1-tail boundary by extracting all turns and computing the endSeqNo of the newest turn outside the L1 window
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/MemoryCompactionPolicy.java` — evaluates whether to trigger compaction based on V2 config (enabled, min-pending-turns, min-pending-tokens, l1-token-warning-ratio); priority: PENDING_TURNS > PENDING_TOKENS > L1_TOKEN_PRESSURE
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/TokenEstimatorTest.java` — 10 tests: ASCII, CJK, mixed, null, empty, blank, turns, empty/null turn list, null userMessages
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/CompactionBoundaryResolverTest.java` — 8 tests: below L1 window, stable anchor computation, watermark from summary, backoff active/expired, consecutive failures, stable/tail split, all-turns-in-tail
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/MemoryCompactionPolicyTest.java` — 10 tests: disabled, no stable turns, backoff, pending turns trigger, token trigger (independent of turn count), L1 pressure trigger (independent of both), below threshold, priority ordering

Modified files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/TurnBasedContextExtractor.java` — added `extractAllTurns(sessionId)` method; extracted shared `groupIntoTurns(messages)` private method from `extractPendingTurns`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/AsyncSummaryListener.java` — replaced simple turn-count check with CompactionBoundaryResolver + MemoryCompactionPolicy; passes stable anchor (not event.lastSeqNo) to summarizer; constructor now takes CompactionBoundaryResolver and MemoryCompactionPolicy instead of TurnBasedContextExtractor
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/conversation/summary/AsyncSummaryListenerTest.java` — updated all 8 tests to mock CompactionBoundaryResolver and MemoryCompactionPolicy instead of TurnBasedContextExtractor; added tests for policy-skip and stable-anchor-verification

### Phase 1: Schema And Runtime Contract Slice

New files:

- `chatagent/bootstrap/src/main/resources/db/migration/V22__memory_compaction_v2.sql` — drops and recreates `chat_session_summary` with V2 columns, creates `chat_session_summary_segment` table with unique range constraint
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/dto/ChatSessionSummarySegmentDTO.java` — segment DTO with seq range, turn count, token estimate, summary, structured summary, anchored entities, status
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/persistence/mapper/ChatSessionSummarySegmentMapper.java` — MyBatis mapper interface for segments (insert, select by id, active by session ordered/unordered, delete by session)
- `chatagent/bootstrap/src/main/resources/mapper/ChatSessionSummarySegmentMapper.xml` — segment SQL with UUID-to-text casting, JSONB text extraction, status filter, ordering
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/port/ChatSessionSummarySegmentRepository.java` — port interface for segment persistence
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/persistence/adapter/conversation/MyBatisChatSessionSummarySegmentRepository.java` — MyBatis adapter delegating to mapper
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/agent/runtime/AgentSessionSummaryResolverTest.java` — 6 tests: empty when no summary, empty when blank synopsis, returns synopsis, trimmed, blank/null session ID
- `chatagent/bootstrap/src/test/java/com/yulong/chatagent/support/persistence/adapter/conversation/MyBatisChatSessionSummarySegmentRepositoryTest.java` — 6 tests: insert, conflict, find active, find ordered, delete, delete empty

Modified files:

- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/dto/ChatSessionSummaryDTO.java` — renamed `lastSeqNo` → `summarizedUntilSeqNo`, `summary` → `synopsis`; added `structuredSummaryJson`, `segmentCount`, `consecutiveFailures`, `failedStartSeqNo`, `failedEndSeqNo`, `lastFailureClass`, `nextRetryAt`
- `chatagent/bootstrap/src/main/resources/mapper/ChatSessionSummaryMapper.xml` — all column mappings updated for V2 schema; insert/update include new fields
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/persistence/adapter/conversation/MyBatisChatSessionSummaryRepository.java` — field name updates, null-safe defaults for `segmentCount` and `consecutiveFailures` on insert and update (update path inherits from existing row), `structuredSummaryJson` defaulted to `{}` on insert and update
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/support/persistence/adapter/conversation/MyBatisChatSessionSummarySegmentRepository.java` — added `ObjectMapper` dependency, JSON serialization/deserialization for `anchoredEntities`, defaults `structuredSummaryJson`/`anchoredEntitiesJson` to `{}`, defaults `status` to `active`, sets timestamps on insert, hydrates `anchoredEntities` on read
- `chatagent/bootstrap/src/main/resources/mapper/ChatSessionSummarySegmentMapper.xml` — added `ON CONFLICT ON CONSTRAINT uk_chat_session_summary_segment_range DO NOTHING` for idempotent segment insert
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/SummaryWatermarkService.java` — reads `getSummarizedUntilSeqNo()` instead of `getLastSeqNo()`
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/summary/IncrementalSummarizer.java` — writes `synopsis` instead of `summary`, `summarizedUntilSeqNo` instead of `lastSeqNo`; preserves `segmentCount` from existing row with null guard; resets `consecutiveFailures` to 0 on successful compaction
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/AgentSessionSummaryResolver.java` — removed `PromptLoader` dependency and fallback prompt; returns empty string when neither synopsis nor active nonblank segments exist (V2 contract)
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/DefaultAgentRuntimeContextLoader.java` — removed stale `contains("No historical context summary available")` string filter from historical summary check
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/conversation/application/ChatSessionFacadeServiceImpl.java` — injects `ChatSessionSummarySegmentRepository`; deletes segments on session deletion
- `chatagent/bootstrap/src/main/resources/application.yaml` — added `chatagent.memory.compaction.v2.*` config block (13 properties, all with env var overrides and defaults)
- `docs/env_variables.txt` — added all 20 memory-related env variable names (existing 7 + V2 13)
- Test updates: `SummaryWatermarkServiceTest`, `IncrementalSummarizerTest`, `MyBatisChatSessionSummaryRepositoryTest`, `MemorySummaryEvalTest` — all field name references updated to V2

Planning/review-fix documents changed:

- `docs/plans/MEMORY_COMPACTION_V2_PLAN.md`
- `docs/plans/IMPLEMENTATION_MEMORY_COMPACTION_V2.md`
- `docs/adr/0001-memory-compaction-v2-l2-schema-reset.md`

## Tests Added Or Updated

Phase 6 new tests (12):

- `AgentSessionSummaryResolverTest`: 7 new tests — synopsis + latest segments appended and formatted with range prefixes, segments bounded by runtimeMaxSegments config, segments with blank summary skipped, runtimeMaxSegments=0 returns synopsis-only, blank synopsis + active segments returns segment text, null summary row + active segments returns segment text, blank newest segment does not crowd out older valid segment
- `DefaultAgentRuntimeContextLoaderTest`: 3 new tests — segment detail included in `[Historical Context Summary]` prompt section, `[Historical Context Summary]` omitted when resolver returns empty string, no L1 tail duplication in summary output (L2 covers pre-L1 turns, L1 memory covers recent tail, no content overlap)
- `LongTermMemoryPromotionServiceTest`: 2 new tests — L3 promotion receives raw `AtomicConversationTurn` objects with original user messages and assistant conclusion (not summary text), extraction log records the stable L2 range (not L1 tail range)

Phase 5 new tests (16):

- `IncrementalSummarizerTest`: 14 new tests — retry on blank output then succeeds with valid JSON on second attempt, retry on exception then succeeds, prompt-too-long splits 4-turn range into 2+2 with two segments created, partial split success (first half OK, second half save fails) advances watermark only through first half and returns effective range, save failure after all maxRetries+1 attempts records failure state and returns updated=false, optimistic lock conflict (false, false, true) retries 3 times and succeeds on third attempt, consecutive failures below maxConsecutiveFailures threshold keep nextRetryAt=null, consecutive failures at threshold set nextRetryAt, failure recording preserves existing anchored entities and structuredSummaryJson, successful run after previous failure clears all failure fields (consecutiveFailures=0, failedStartSeqNo=null, failedEndSeqNo=null, lastFailureClass=null, nextRetryAt=null), prompt-too-long detection from exception message patterns, no segment duplication on retry after partial success (watermark ensures only remaining range processed), all maxRetries+1 save attempts exhausted returns failure

Phase 5 updated tests (2):

- `MemorySummaryEvalTest`: constructor call updated for 3 new IncrementalSummarizer parameters (maxRetries, maxConsecutiveFailures, failureBackoffSeconds)
- `AsyncSummaryListenerTest`: added per-segment L3 event test (2 segments → 2 L3 events with correct range/turns per segment). Total: 9 tests.

Phase 4 new tests (14):

- `ToolResultCompactorTest`: 14 tests covering large content compacted with head/tail format, small content unchanged, exact threshold boundary, null/empty input, head/tail from distinct content regions, shouldCompact flag, max exceeded when configured head/tail would overlap, forced budget compaction below maxChars, budget compaction preserving original content when the compacted wrapper would be larger, budget compaction of null, Micrometer counter incremented on compaction, Micrometer counter not incremented when no compaction, error-line preservation from dropped middle region

Phase 4 updated tests (5):

- `AgentMemoryLoaderTest`: 3 existing tests updated for new ToolResultCompactor constructor parameter; 5 new tests — oversized tool result compacted in returned Spring AI messages, tool call ID preserved after compaction, small tool result unchanged, DTO content unchanged proving no DB mutation, budget-pressure compaction for a below-maxChars tool result

Phase 3 new tests (11):

- `StructuredSummaryParserTest`: 11 tests covering valid JSON, missing optional fields, markdown code fence stripping, invalid JSON → raw text fallback, blank input, null input, non-string list filtering, empty entity list filtering, deterministic fallback from turns, empty turns, null turns

Phase 3 updated tests (8+1):

- `IncrementalSummarizerTest`: 8 tests rewritten for V2 structured flow — segment+synopsis creation with anchored entity merge, LLM fallback to deterministic summary, no-pending-range, no-pending-turns, three-way anchored entity merge (regex+structured), synopsis head-truncation, structured prompt verification, segment count increment
- `MemorySummaryEvalTest`: constructor call updated for new IncrementalSummarizer parameters (added segmentRepository mock)

Phase 2 new tests (28):

- `TokenEstimatorTest`: 10 tests covering ASCII, CJK, mixed content, null/empty/blank input, turns estimation, null/empty turn lists, null userMessages
- `CompactionBoundaryResolverTest`: 8 tests covering below-L1-window, stable anchor computation, watermark from summary, backoff active/expired, consecutive failures, stable/tail split, all-turns-in-tail
- `MemoryCompactionPolicyTest`: 10 tests covering disabled, no stable turns, backoff, pending turns trigger, token trigger independent of turns, L1 pressure trigger independent of both, below threshold, priority ordering (turns > tokens > pressure)

Phase 2 updated tests (8):

- `AsyncSummaryListenerTest`: 8 tests rewritten for V2 boundary + policy flow — no-stable-turns skip, policy-skip, anchor-covered, stable-anchor-not-event-anchor, L3 publish/unpublished paths, L3-disabled

Phase 1 new tests (12):

- `AgentSessionSummaryResolverTest`: 6 tests covering empty/null synopsis, non-empty synopsis, trimming, blank/null session ID
- `MyBatisChatSessionSummarySegmentRepositoryTest`: 6 tests covering insert (with JSON/status/timestamp defaults), conflict (ON CONFLICT returns 0), anchored entities serialization, anchored entities hydration on find, find ordered, delete

Phase 1 review-fix new tests (4):

- `MyBatisChatSessionSummaryRepositoryTest`: 2 tests — `shouldDefaultStructuredSummaryJsonToEmptyObjectOnInsert` and `shouldDefaultStructuredSummaryJsonToEmptyObjectOnUpdate` proving null `structuredSummaryJson` is defaulted to `{}` before mapper call
- `MyBatisChatSessionSummarySegmentRepositoryTest`: updated from 6 to 7 tests — `shouldInsertSegment` now verifies `anchoredEntitiesJson={}`, `structuredSummaryJson={}`, `status=active`, timestamps set; added `shouldSerializeAnchoredEntitiesOnInsert` and `shouldHydrateAnchoredEntitiesOnFind`

Phase 1 SQL-backed smoke test (7):

- `V22PostgresIntegrationTest`: Testcontainers + PostgreSQL 16 integration test validating V22 migration + MyBatis XML + repository behavior together. Tests: summary insert/read without `structuredSummaryJson`, optimistic-lock update, summary delete, segment insert/read with anchored entities, segment idempotent duplicate insert (ON CONFLICT), segment delete, nonzero counter preservation on update. This test caught a real `segmentCount` NULL-on-update bug that mock tests missed.

Phase 1 updated tests (16 across 4 files):

- `SummaryWatermarkServiceTest`: 6 tests updated for `summarizedUntilSeqNo` field name
- `IncrementalSummarizerTest`: 4 tests updated for `summarizedUntilSeqNo`/`synopsis` field names
- `MyBatisChatSessionSummaryRepositoryTest`: 5 tests updated for `summarizedUntilSeqNo`/`synopsis` field names
- `MemorySummaryEvalTest`: 4 references updated for `summarizedUntilSeqNo`/`synopsis`/`getSynopsis()` field names

## Verification Commands

Phase 7 broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 796 tests, 0 failures, 0 errors
```

Phase 6 targeted verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=DefaultAgentRuntimeContextLoaderTest,AsyncSummaryListenerTest,LongTermMemoryPromotionServiceTest,AgentSessionSummaryResolverTest"
# Result: 50 tests, 0 failures, 0 errors
```

Phase 5 targeted verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=IncrementalSummarizerTest,AsyncSummaryListenerTest"
# Result: 33 tests, 0 failures, 0 errors
```

Phase 5 broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 784 tests, 0 failures, 0 errors
```

Phase 2 targeted verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=StructuredSummaryParserTest,IncrementalSummarizerTest,AsyncSummaryListenerTest"
# Result: 27 tests, 0 failures, 0 errors
```

Phase 4 targeted verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=ToolResultCompactorTest,AgentMemoryLoaderTest"
# Result: 18 tests, 0 failures, 0 errors
```

Phase 4 broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 766 tests, 0 failures, 0 errors
```

Phase 3 broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 748 tests, 0 failures, 0 errors
```

Phase 2 targeted verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=TokenEstimatorTest,CompactionBoundaryResolverTest,MemoryCompactionPolicyTest,AsyncSummaryListenerTest"
# Result: 36 tests, 0 failures, 0 errors
```

Phase 2 broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 729 tests, 0 failures, 0 errors
```

Phase 2 review-fix verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=CompactionBoundaryResolverTest,MemoryCompactionPolicyTest,AsyncSummaryListenerTest,AgentMemoryLoaderTest,TokenEstimatorTest"
# Result: 44 tests, 0 failures, 0 errors
```

Phase 2 review-fix broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 734 tests, 0 failures, 0 errors
```

Phase 2 review-fix 2 (no-stable policy path):

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=AsyncSummaryListenerTest,MemoryCompactionPolicyTest,CompactionBoundaryResolverTest"
# Result: 30 tests, 0 failures, 0 errors
```

Phase 2 review-fix 2 broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 733 tests, 0 failures, 0 errors
```

Phase 1 targeted verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=*ChatSessionSummary*Test,*SummarySegment*Test,*AgentSessionSummaryResolver*Test"
# Result: 17 tests, 0 failures, 0 errors

.\mvnw.cmd -pl bootstrap test "-Dtest=AsyncSummaryListenerTest,SummaryWatermarkServiceTest,IncrementalSummarizerTest"
# Result: 17 tests, 0 failures, 0 errors
```

Phase 1 static checks:

```powershell
# All 20 memory env vars present in docs/env_variables.txt
for var in CHATAGENT_MEMORY_L1_WINDOW_TURNS ... ; do grep -q "$var" docs/env_variables.txt && echo "OK" || echo "MISSING"; done
# Result: 20/20 OK

# All 13 V2 config keys present in application.yaml
for key in enabled: min-pending-turns: ... ; do grep -q "$key" bootstrap/src/main/resources/application.yaml && echo "OK" || echo "MISSING"; done
# Result: 13/13 OK
```

Phase 1 broad verification:

```powershell
.\mvnw.cmd -pl bootstrap test
# Result: 699 tests, 0 failures, 0 errors (after SQL-backed smoke test)
```

Review fix verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=*ChatSessionSummary*Test,*AgentSessionSummaryResolver*Test,IncrementalSummarizerTest"
# Result: 24 tests, 0 failures, 0 errors
```

Phase 1 SQL-backed verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=V22PostgresIntegrationTest"
# Result: 7 tests, 0 failures, 0 errors (Testcontainers + PostgreSQL 16)
```

## Known Failures

None.

## Manual Checks

Not run.

Planned manual checks:

1. Run a conversation longer than `chatagent.memory.l1-window-turns`.
2. Confirm L2 summarizes only older turns outside the L1 tail.
3. Confirm recent turns remain raw in L1.
4. Confirm a large tool output is compacted in runtime context.
5. Confirm the large tool output remains intact in persisted `chat_message`.
6. Confirm `[Historical Context Summary]` renders from V2 synopsis/segments.
7. Confirm L3 extraction still receives stable raw ranges and can create memory items.
8. Confirm logs do not contain raw turns, summaries, prompts, or tool outputs.

## Review History

| Date | Reviewer | Notes |
|---|---|---|
| 2026-06-05 | Planning | User confirmed old L2 data compatibility is not required and V2 should be default-enabled. |
| 2026-06-05 | Review fix | Fixed P1 Phase 1 scope boundary by making Phase 1 a schema + runtime contract vertical slice. Added V22 phase gate for app context and summary load/write/delete paths. |
| 2026-06-05 | Review fix | Fixed P2 split/retry ambiguity by adding per-segment transaction, watermark advancement, failure range, retry, and L3 publication contracts. |
| 2026-06-05 | Review fix | Fixed ADR standards gap by adding ADR 0001 for L2 schema reset/no backfill. |
| 2026-06-05 | Review fix | Fixed token-trigger test gap by requiring pending-token and L1-pressure tests where turn threshold is not satisfied. |
| 2026-06-05 | Review fix | Fixed empty historical-summary ambiguity: V2 resolver returns empty and caller omits `[Historical Context Summary]`. |
| 2026-06-05 | Review fix | Resolved `summary-max-chars` contract: retained as legacy no-op, V2 ignores it. Expanded env-doc acceptance to cover all memory variables (existing + V2). Corrected recommended next step to match Phase 1 Schema And Runtime Contract Slice scope. |
| 2026-06-05 | Implementation | Phase 1 complete: V22 migration, V2 DTO/mapper/repository, service field renames, resolver empty-state, loader fallback cleanup, facade segment delete, config properties, env doc, 12 new tests, 690 total tests passing. |
| 2026-06-05 | Review fix | Fixed P1: `structuredSummaryJson` null → `{}` in summary repo insert/update. Fixed P1: segment repo now serializes anchored entities, defaults JSON/status/timestamps. Fixed P2: segment insert uses `ON CONFLICT DO NOTHING` for idempotency. 3 new regression tests. 693 total tests passing. |
| 2026-06-05 | Review fix | Added V22PostgresIntegrationTest (6 SQL-backed tests with Testcontainers). Caught and fixed `segmentCount`/`consecutiveFailures` NULL-on-update bug that mock tests missed. 699 total tests passing. |
| 2026-06-05 | Review fix | Added nonzero counter preservation test to V22PostgresIntegrationTest (7 SQL-backed tests total). Verifies `segmentCount=2` and `consecutiveFailures=1` survive update when caller omits them. |
| 2026-06-06 | Implementation | Phase 2 complete: CompactionBoundaryResolver resolves stable/L1-tail boundary, MemoryCompactionPolicy adds token-aware trigger with three independent reasons (PENDING_TURNS, PENDING_TOKENS, L1_TOKEN_PRESSURE), TokenEstimator shared utility, AsyncSummaryListener uses stable anchor instead of event.lastSeqNo, TurnBasedContextExtractor gains extractAllTurns. 36 new/updated tests. 729 total tests passing. |
| 2026-06-06 | Review fix | Fixed P2: CompactionBoundary now provides pendingStableTurns()/pendingStableTurnCount() that filter by (summarizedUntilSeqNo, stableAnchorSeqNo] range. Policy and listener use pending values, not all-stable values. Added 4 regression tests covering watermark scenario (20 turns, L1 tail 8, watermark at turn 11). Fixed P2: Added Micrometer counter `chatagent.memory.compaction.v2.policy.decisions` with outcome=run|skip and reason=trigger tags. Added 2 metric verification tests using SimpleMeterRegistry. Fixed P3: AgentMemoryLoader now delegates to shared TokenEstimator, removing private estimateTokens/isChinese. 734 total tests passing. |
| 2026-06-06 | Review fix | Fixed P2: Removed listener early return on no-stable-turns so policy always evaluates and records decision metric (including NO_STABLE_TURNS). Test `shouldDelegateToPolicyWhenNoStableTurns` uses real no-stable boundary, verifies policy call, metric count, and no lock/summarizer invocation. 733 total tests passing. |
| 2026-06-06 | Implementation | Phase 3 complete: StructuredSummary record + StructuredSummaryParser with JSON parse/deterministic fallback, structured segment prompt (segment-memory.md), IncrementalSummarizer refactored to V2 flow — structured prompt → parse → segment row → deterministic synopsis merge, three-way anchored entity merge (existing + regex + structured entities), SummaryResult extended with segments/synopsis, MemorySummaryEvalTest updated for new constructor. 11 new + 9 updated tests. Targeted 59 tests passing. |
| 2026-06-06 | Review fix | Fixed P2: Added `shouldHandleDuplicateSegmentInsert` test covering ON CONFLICT path — segment insert returns false, no segments in result, segmentCount unchanged, synopsis still merged, watermark advanced. Fixed P3: `toStructuredJson` now reuses static `OBJECT_MAPPER` instead of creating new ObjectMapper per call. Fixed P3: Added `shouldFallbackWhenLlmReturnsBlankContent` test covering blank LLM response path. 2 new tests. |
| 2026-06-06 | Review fix | Fixed P2: `StructuredSummaryParser.parse()` now returns empty on JSON parse failure instead of wrapping raw model text, so `IncrementalSummarizer` falls through to `StructuredSummaryParser.fallback(turns)` for deterministic output. Updated `shouldReturnEmptyWhenJsonIsInvalid` parser test. Added `shouldFallbackWhenLlmReturnsInvalidJson` summarizer test proving segment summary comes from turns, not unstructured model text. Fixed P2: `warnIfAnchorsMissing` now logs only `missingCount` and `buckets=[dates:1, amounts:3]` — no raw entity values in logs. |
| 2026-06-06 | Review fix | Fixed P2: `StructuredSummaryParser` parse failure log now uses `errorClass=JsonParseException, inputChars=79` instead of `e.getMessage()` to avoid leaking model response tokens into logs. |
| 2026-06-06 | Implementation | Phase 4 complete: ToolResultCompactor with deterministic head/tail format for oversized tool responses, integrated into AgentMemoryLoader's collectAssistantSequence (compact on read, no DB mutation). Tool call ID and response pairing preserved. 7 new + 4 updated tests. Targeted 14 tests passing. |
| 2026-06-06 | Review fix | Fixed P2: `compactIfNeeded()` now still compacts when content exceeds maxChars even if configured head/tail would overlap the content, dynamically shrinking excerpts so the result is shorter than the original. Fixed P2: `AgentMemoryLoader` now counts `ToolResponseMessage.responseData()` in token estimates and applies budget-pressure compaction before dropping a selected over-budget turn. Fixed P3: verified the stray `chatagent/org/` generated class directory is absent from `git status`. Added 4 regression tests. Targeted 18 tests and full 766-test suite passing. |
| 2026-06-06 | Review fix | Fixed P3: Added Micrometer counter `chatagent.memory.compaction.v2.tool_results_compacted` to `ToolResultCompactor`, following `ObjectProvider<MeterRegistry>` pattern from AsyncSummaryListener. 2 metric verification tests added. Fixed P3: `doCompact` now scans the dropped middle region for lines matching error/stack-trace/URL/path patterns and preserves up to 3 such lines within 25% of the excerpt budget. Safety check reverts to head/tail-only if error lines would make the result longer than the original. 1 test for error-line preservation. Fixed P3: Added `shouldReturnNullWhenBudgetCompactingNull` test covering `compactForBudget(null)` contract. Targeted 22 tests passing. |
| 2026-06-06 | Implementation | Phase 5 complete: model retry on blank/invalid output (up to maxRetries+1 attempts), prompt-too-long split-and-retry (recursive half-split with per-sub-range segments), optimistic lock retry (saveOrUpdate retried once), failure state recording (consecutiveFailures, failure range, nextRetryAt, lastFailureClass), success clears failure state. 10 new tests, 1 updated test. 780 total tests passing. |
| 2026-06-06 | Review fix | Fixed P2: `saveWithRetry` now loops maxRetries+1 total attempts (was hardcoded 2). Fixed P2: `recordFailure` only sets `nextRetryAt` when `consecutiveFailures >= maxConsecutiveFailures` threshold (was set on every failure). Fixed P2: `recordFailure` now preserves existing `anchoredEntities`, `structuredSummaryJson` from existing summary (was dropping them). Fixed P2: `AsyncSummaryListener.publishL3Events` publishes one L3 event per segment when result has segments, with filtered turns per segment range (was publishing one merged event). 6 new regression tests. 784 total tests passing. |
| 2026-06-06 | Implementation | Phase 6 complete: `AgentSessionSummaryResolver` now returns synopsis + up to N latest segment summaries (bounded by `runtime-max-segments` config), each with `[Segment start..end]` prefix. No change to `DefaultAgentRuntimeContextLoader` (transparent resolver output). L3 alignment verified: raw `AtomicConversationTurn` objects and stable L2 range. No-L1-duplication invariant tested. 9 new tests across 3 test classes. Targeted 47 tests passing. |
| 2026-06-06 | Review fix | Fixed P2: `AgentSessionSummaryResolver` now queries segments before checking synopsis — returns segment text when synopsis is blank/null but active nonblank segments exist (was returning empty). Fixed P3: blank segments are filtered before the N limit is applied, so a blank newest segment never crowds out an older valid segment. 3 new regression tests. Targeted 50 tests passing. |
| 2026-06-06 | Review fix | Fixed P3: segment-only summary no longer has leading blank lines — `\n\n` separator only appended when result is non-empty. Tests strengthened to exact `isEqualTo` assertions. Fixed P3: updated stale comments in `DefaultAgentRuntimeContextLoader` and decision table in implementation doc to reflect segment-only summary path. Targeted 50 tests passing. |
| 2026-06-06 | Implementation | Phase 7 complete: documentation finalized, env variables already covered (20/20), broad verification 796 tests 0 failures. Manual checks remain not run. |

## Deferred Items

- LLM-based tool-result microcompact.
- L2 segment vector recall.
- User-facing memory management.
- Deterministic sensitive-info scanner.
- Migration/backfill of old L2 summary data.
- Full Claude Code-style transcript boundary and attachment reinjection.
