# ChatAgent Gatling Load Tests

This directory contains system-level load tests that run outside the main
Spring Boot build. They exercise real HTTP/SSE endpoints against a running
ChatAgent environment.

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
