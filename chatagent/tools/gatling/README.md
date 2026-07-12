# ChatAgent Gatling Load Tests

This directory contains system-level load tests that run outside the main
Spring Boot build. They exercise real HTTP/SSE endpoints against a running
ChatAgent environment.

## Evidence-specific harness

The replacement harness has two mutually exclusive application profiles:

- `capacity-test` installs only local capacity stubs and defaults both the
  entry and Agent-run limiters to disabled.
- `resilience-test` keeps `RoutingLLMService` active and points existing
  provider clients at the repository-owned loopback fixture with placeholder
  credentials.

PHASE-03 runners support `-DryRun` only. They generate schema-v1 manifests and
validate the authoritative limiter mode before any later-phase load process is
allowed to start:

```powershell
& .\tools\gatling\scripts\run-capacity-matrix.ps1 -DryRun -Protocol formal-v1
& .\tools\gatling\scripts\run-entry-rate-limit.ps1 -DryRun
& .\tools\gatling\scripts\run-agent-capacity.ps1 -DryRun
& .\tools\gatling\scripts\run-routing-resilience.ps1 -DryRun
& .\tools\gatling\scripts\test-harness-contract.ps1
```

Dry-run artifacts are written beneath `tools/gatling/artifacts/` and contain no
prompt, token, credential, or provider payload. The routing runner rejects any
fixture URL that does not resolve exclusively to loopback addresses. The
entry, Agent-capacity, and routing classes are intentionally non-executable
shells until their owning PHASE-04/05 implementation is accepted. The chat-turn
workload and `TurnOutcomeRecorder` are present, but the formal matrix runner
remains dry-run-only until PHASE-06 accepts its execution and reporting gates.

## Prerequisites

- Start ChatAgent and its required infrastructure first.
- Use JDK 17+.
- Run commands from the repository root: `chatagent/`.

## Chat API Load

Targets:

- `POST /api/auth/register`
- `POST /api/chat-sessions`
- `POST /api/chat-messages`

Default shape:

- 200 concurrent virtual users
- 60 second ramp-up
- 300 second hold
- one chat message per user every 400 ms, roughly 500 requests/s at steady state
- `Create chat message` P95 target: 5000 ms
- failed request target: < 1%

Run:

```powershell
.\mvnw.cmd -f tools\gatling\pom.xml gatling:test `
  -Dgatling.simulationClass=com.yulong.chatagent.load.ChatApiLoadSimulation `
  -DbaseUrl=http://localhost:8080 `
  -DconcurrentUsers=200 `
  -DrampSeconds=60 `
  -DholdSeconds=300 `
  -DpaceMillis=400
```

## SSE Capacity

Targets:

- `POST /api/auth/register`
- `POST /api/chat-sessions`
- `GET /api/sse/connect/{chatSessionId}?access_token=...`

Default shape:

- 500 concurrent SSE connections
- 60 second ramp-up
- 300 second hold
- failed request target: < 1%

Run:

```powershell
.\mvnw.cmd -f tools\gatling\pom.xml gatling:test `
  -Dgatling.simulationClass=com.yulong.chatagent.load.SseCapacitySimulation `
  -DbaseUrl=http://localhost:8080 `
  -DsseConnections=500 `
  -DrampSeconds=60 `
  -DholdSeconds=300
```

## Tunables

All simulations accept either Java system properties or matching environment
variables:

| System Property | Environment Variable | Default |
| --- | --- | --- |
| `baseUrl` | `CHATAGENT_BASE_URL` | `http://localhost:8080` |
| `userPrefix` | `CHATAGENT_LOAD_USER_PREFIX` | `load-user` |
| `password` | `CHATAGENT_LOAD_PASSWORD` | `LoadTest@123456` |
| `concurrentUsers` | `CHATAGENT_LOAD_CONCURRENT_USERS` | `200` |
| `sseConnections` | `CHATAGENT_SSE_CONNECTIONS` | `500` |
| `rampSeconds` | `CHATAGENT_LOAD_RAMP_SECONDS` | `60` |
| `holdSeconds` | `CHATAGENT_LOAD_HOLD_SECONDS` | `300` |
| `paceMillis` | `CHATAGENT_LOAD_PACE_MILLIS` | `400` |
| `chatP95TargetMs` | `CHATAGENT_CHAT_P95_TARGET_MS` | `5000` |
| `maxFailedPercent` | `CHATAGENT_LOAD_MAX_FAILED_PERCENT` | `1.0` |

Gatling writes HTML reports under `tools/gatling/target/gatling/`.

## Legacy Chat API End-to-End (requires `capacity-test` profile)

`ChatApiE2eSimulation` measures the **end-to-end chat turn** time (POST send →
matching `AI_DONE` event on SSE) against a backend started with the `capacity-test`
profile (in-process stub LLM providers, simulated latency).

Each virtual user: register → loop { create fresh chat session → open SSE for
that session → record turn start → POST chat message with a client-generated
`turnId` → wait on SSE for `AI_DONE` with matching `turnId` → record e2e →
close SSE }. The per-turn session/SSE connection prevents stale buffered
terminal events from satisfying later checks; the headline metric still starts
at POST send and ends at matching `AI_DONE`.

The e2e P50/P95/P99 are computed post-run from the collected samples (Gatling
assertions cannot cover session-derived percentiles). The simulation writes
`tools/gatling/target/gatling/e2e-report/e2e-gate.txt` and fails the run with a
normal exception if e2e P95 exceeds `e2eP95TargetMs` or no samples arrive. It
does not use `System.exit`, which can surface as a Gatling fork failure.

Start the backend first:

```powershell
$env:JAVA_HOME='C:\Users\guany\.jdks\ms-17.0.18'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$jar = Get-ChildItem -LiteralPath 'bootstrap\target' -Filter '*.jar' |
  Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
$argString = '-jar "' + $jar.FullName + '" --spring.profiles.active=capacity-test'
Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList $argString `
  -WorkingDirectory (Resolve-Path '.').Path -WindowStyle Hidden
```

Run the simulation:

```powershell
.\mvnw.cmd -f tools\gatling\pom.xml gatling:test `
  -Dgatling.simulationClass=com.yulong.chatagent.load.ChatApiE2eSimulation `
  -DbaseUrl=http://localhost:8080 `
  -DconcurrentUsers=20 -DholdSeconds=300 `
  -DpaceMillis=0 -De2eP95TargetMs=3000 -DmaxFailedPercent=1.0
```

For a tuned local backend, set these before starting the application:

```powershell
$env:CHATAGENT_MQ_CONSUMERS_AGENT_CONCURRENCY='20'
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='30'
$env:CHATAGENT_MQ_OUTBOX_POLL_INTERVAL_MS='100'
$env:CHATAGENT_MQ_OUTBOX_BATCH_SIZE='50'
```

Additional e2e tunables:

| System Property | Environment Variable | Default |
| --- | --- | --- |
| `e2eP95TargetMs` | `CHATAGENT_E2E_P95_TARGET_MS` | `3000` |
| `e2eAwaitSeconds` | `CHATAGENT_E2E_AWAIT_SECONDS` | `30` |

E2E samples CSV is written to
`tools/gatling/target/gatling/e2e-report/e2e-samples.csv`; samples are also
incrementally appended to `e2e-samples-live.csv` while the simulation runs.
