# Memory Compaction V2 Implementation Record

## Overall Status

Phase 1–3 complete. Phases 4–7 pending.

Authoritative plan:

- `docs/plans/MEMORY_COMPACTION_V2_PLAN.md`

## Phase Checklist

| Phase | Name | Status |
|---|---|---|
| 1 | Schema And Runtime Contract Slice | **Complete** |
| 2 | Stable Boundary And Token Policy | **Complete** |
| 3 | Structured Segment Summarization | **Complete** |
| 4 | Runtime Tool-Result Microcompact | Pending |
| 5 | Failure Protection And Retry | Pending |
| 6 | Runtime Rendering And L3 Alignment | Pending |
| 7 | Documentation And Broad Verification | Pending |

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
| `AgentSessionSummaryResolver` no longer loads a fallback prompt — returns empty string when no synopsis exists, allowing the caller to omit `[Historical Context Summary]` | Confirmed |
| `DefaultAgentRuntimeContextLoader` removed stale `contains("No historical context summary available")` string filter — V2 resolver returns empty string directly | Confirmed |

## Files Changed

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
- `chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/runtime/AgentSessionSummaryResolver.java` — removed `PromptLoader` dependency and fallback prompt; returns empty string when no synopsis exists (V2 contract)
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

Phase 2 targeted verification:

```powershell
.\mvnw.cmd -pl bootstrap test "-Dtest=StructuredSummaryParserTest,IncrementalSummarizerTest,AsyncSummaryListenerTest"
# Result: 27 tests, 0 failures, 0 errors
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

## Deferred Items

- LLM-based tool-result microcompact.
- L2 segment vector recall.
- User-facing memory management.
- Deterministic sensitive-info scanner.
- Migration/backfill of old L2 summary data.
- Full Claude Code-style transcript boundary and attachment reinjection.
