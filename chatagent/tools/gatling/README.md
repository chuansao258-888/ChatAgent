# ChatAgent Gatling Evidence Harness

This standalone Maven module exercises real ChatAgent HTTP/SSE, RabbitMQ,
PostgreSQL, and Redis paths. It never belongs to the main reactor test lifecycle.

## Evidence profiles

- `capacity-test`: deterministic in-process no-tool LLM stub; entry and Agent-run
  limiters default off. Use only for application-capacity and limiter evidence.
- `resilience-test`: real `RoutingLLMService` with repository-owned loopback
  provider fixture and placeholder credentials. Use only for first-packet,
  fallback, and circuit evidence.

The profiles are mutually exclusive. Capacity results are not routing evidence,
real-provider throughput, provider pricing, or production capacity.

## Authoritative runners

Run from the `chatagent/` Maven root with JDK 17 configured:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gatling\scripts\run-entry-rate-limit.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gatling\scripts\run-agent-capacity.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gatling\scripts\run-agent-redis-failure.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gatling\scripts\run-routing-resilience.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gatling\scripts\run-capacity-matrix.ps1 -Mode All
```

Validate shell/harness contracts without starting a backend or provider:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gatling\scripts\test-harness-contract.ps1
```

Regenerate the tracked capacity summary only when the complete accepted matrix
exists:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gatling\scripts\publish-capacity-results.ps1
```

The publisher rejects missing/duplicate repetitions, non-reportable formal
runs, limiter-enabled capacity runs, incomplete outcome reconciliation, failed
queue/permit drain, a 300 ms causal P95 above 3 seconds, and missing CPU/JVM/
memory evidence.

## Fixed capacity protocol

- Calibration: 300 ms stub, B3, closed 5/10/20/40/60 users, pace 0, 60 seconds.
- Causal A/B: 300 ms stub, closed 20 users, pace 0, 300 seconds, three runs per
  B0-B3 row.
- Sensitivity: B3 with 1.2 s and 3 s stubs, same 20-user/300-second shape, three
  runs per latency.

`run-capacity-matrix.ps1` owns these values. Arbitrary overrides cannot enter
the accepted summary. Each injected virtual user executes one isolated turn;
the closed injection owns the held clock and allows only the current turn to
finish after injection stops.

See [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md) for accepted results, limits,
resume-safe wording, and hashes for the tracked
`CAPACITY_RUN_INDEX.json` and `ARTIFACT_RETENTION_INDEX.json` evidence.

## Artifacts and safety

Each run writes a manifest, reconciled result, turn samples, RabbitMQ ready and
unacknowledged samples, Redis observations, CPU/JVM/memory samples, process
ownership, sanitized logs, and SHA-256 inventory beneath
`tools/gatling/artifacts/`.

The publisher writes the sanitized capacity attempt index beside this README.
After publishing, the cleanup script writes the tracked historical retention
index and removes obsolete raw directories; publish once more to bind both
tracked index hashes into `BENCHMARK_RESULTS.md`.

The environment importer reads only DB/JWT infrastructure names. Capacity runs
do not read provider credentials or consume real LLM quota. The routing fixture
must resolve exclusively to loopback addresses.
