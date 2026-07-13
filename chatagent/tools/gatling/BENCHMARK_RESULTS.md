# Capacity Benchmark Results

Generated from validated schema-v1 run artifacts. The generator refuses incomplete, duplicate, non-reportable, limiter-enabled, un-drained, or resource-incomplete formal evidence.

## Scope and protocol

- Synthetic in-process no-tool LLM stub; no provider credentials or real LLM quota used.
- Single-turn isolated sessions, closed 20-user, pace-0, 300-second held workload; load generator, backend, and infrastructure are co-located on one Windows desktop.
- Both entry and Agent-run limiters disabled. Results are application-path comparisons, not limiter throughput or production capacity.
- Each formal row has three reportable repetitions. Values below are median with min–max range.
- Tracked capacity attempt index: tools/gatling/CAPACITY_RUN_INDEX.json (SHA-256 ac8c1b51eba07f3ede8ac58e6238873b73ca40e2d4c200fd03e395f2fa2f6f1b). Invalid and dry-run attempts remain indexed but are excluded from accepted summaries.
- Tracked artifact-retention index: tools/gatling/ARTIFACT_RETENTION_INDEX.json (SHA-256 7f3cfa41c6da86da453e43ce0d0ff1953ee2dc3b3d01d21963f6f80704d557e0). It preserves sanitized run summaries and hashes before obsolete raw artifacts are removed.

## Calibration (range finding only)

| Closed users | turn/s | success % | Gatling KO % | P95 ms | peak backend CPU % | peak JVM MiB |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 6.78 | 100.000 | 0.000 | 688 | 16.41 | 667 |
| 10 | 13.10 | 99.746 | 0.042 | 755 | 25.78 | 830 |
| 20 | 23.75 | 99.372 | 0.105 | 952 | 38.17 | 819 |
| 40 | 28.63 | 98.115 | 0.315 | 2,095 | 46.77 | 967 |
| 60 | 29.17 | 97.929 | 0.346 | 2,733 | 46.67 | 1,037 |

Calibration identifies local range behavior only; it is not used as a resume throughput number or causal comparison.

## 300 ms causal A/B matrix

| Row | Change from prior row | turn/s | success % | Gatling KO % | P50 ms | P95 ms | P99 ms | peak backend CPU % | peak JVM MiB | min free memory MiB | peak ready | peak unack |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B0 | Baseline: Agent 5, Hikari 10, outbox 500 ms/10 | 8.84 (8.77-9.16) | 99.962 (99.855-99.962) | 0.006 (0.006-0.024) | 2,160 (2,120-2,181) | 2,578 (2,544-2,647) | 2,801 (2,788-2,866) | 20.52 (17.57-22.05) | 1,248 (1,197-1,281) | 2,137 (2,031-2,252) | 0 (0-0) | 20 (20-20) |
| B1 | Agent concurrency 5 -> 20 | 14.78 (14.71-14.84) | 99.640 (99.414-99.731) | 0.060 (0.045-0.098) | 1,213 (1,210-1,219) | 1,345 (1,344-1,351) | 1,522 (1,483-1,530) | 27.27 (26.59-27.77) | 1,506 (1,463-1,531) | 1,983 (1,807-2,738) | 0 (0-0) | 10 (10-12) |
| B2 | Hikari pool 10 -> 30 | 14.74 (14.64-14.80) | 99.552 (99.457-99.819) | 0.075 (0.030-0.091) | 1,213 (1,211-1,214) | 1,363 (1,330-1,393) | 1,593 (1,490-1,762) | 28.19 (28.13-29.31) | 1,597 (1,547-1,645) | 2,863 (2,638-2,916) | 0 (0-0) | 10 (10-12) |
| B3 | Outbox bundle 500 ms/10 -> 100 ms/50 | 14.88 (14.74-15.15) | 99.584 (99.443-99.640) | 0.190 (0.184-0.216) | 710 (704-711) | 857 (819-865) | 1,070 (1,005-1,159) | 40.06 (38.35-40.10) | 1,800 (1,631-1,830) | 2,551 (2,523-2,814) | 0 (0-0) | 20 (17-20) |

All twelve causal runs reconciled 100% of submitted turns, achieved at least 99% successful completion, remained below 1% KO, met E2E P95 <= 3000 ms, and drained RabbitMQ ready/unacknowledged plus Redis active permits to zero.
B1 materially improved latency and throughput versus B0. B2 did not improve median P95 or throughput on this machine. B3 materially improved latency versus B2, but the experiment attributes that change only to the combined outbox poll/batch bundle.

## Latency sensitivity at B3

| Stub latency | turn/s | success % | Gatling KO % | P50 ms | P95 ms | P99 ms | peak backend CPU % | peak JVM MiB |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1200 ms | 10.14 (10.14-10.18) | 99.836 (99.770-99.902) | 0.038 (0.016-0.436) | 1,842 (1,837-1,843) | 1,951 (1,949-1,952) | 2,219 (2,212-2,266) | 21.81 (20.22-24.55) | 1,346 (1,300-1,411) |
| 3000 ms | 4.62 (4.62-4.62) | 99.856 (99.784-100.000) | 0.024 (0.000-0.036) | 4,203 (4,202-4,204) | 4,401 (4,381-4,462) | 5,535 (5,266-5,847) | 20.46 (19.97-20.53) | 839 (784-944) |

Sensitivity rows are descriptive only and do not inherit the 300 ms P95 headline gate.

## Resume and interview wording

Safe claim: "Designed and validated a two-layer rate-limit/circuit-breaker load harness with deterministic LLM stubs, reconciled SSE turn outcomes, queue/permit drain gates, fault injection, and a three-repeat B0-B3 ablation. On a co-located 20-user synthetic workload, the accepted baseline-to-final configuration reduced median E2E P95 while preserving >=99% completion and <1% KO; the DB-pool-only step showed no improvement."

Do not claim real-provider throughput, production capacity, universal QPS, provider cost savings, or causality for either individual outbox knob. The experiment measures this repository and machine under synthetic no-tool latency.
