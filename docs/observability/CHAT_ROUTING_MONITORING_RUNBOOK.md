# ChatAgent Routing Monitoring Runbook

This runbook is the Phase 8 operational entry point for ChatAgent LLM routing monitoring.

## Scope

Phase 8 covers real monitoring platform integration only:

1. Prometheus scrape and alert rule import for ChatAgent routing metrics.
2. Grafana dashboard import/provisioning for routing trends.
3. `chatRouting` Actuator health check integration.
4. Alert routing and triage guidance.

Phase 8 does not cover cross-provider independent HTTP transport or OkHttp `Call.cancel()` equivalent hard cancellation.

## Local Validation Stack

Start ChatAgent locally on port `8080`, then run:

```powershell
docker compose -f docker-compose-observability.yml up -d
```

Open:

1. Prometheus: `http://localhost:9090`
2. Grafana: `http://localhost:3000`
3. ChatAgent metrics: `http://localhost:8080/actuator/prometheus`
4. ChatAgent routing health: `http://localhost:8080/actuator/health/chatRouting`

Default Grafana credentials are `admin` / `admin` unless overridden by `CHATAGENT_GRAFANA_ADMIN_USER` and `CHATAGENT_GRAFANA_ADMIN_PASSWORD`.

## Platform Import Checklist

1. Confirm the target environment exposes `/actuator/prometheus`.
2. Confirm the target environment exposes `/actuator/health/chatRouting` to the internal monitoring network.
3. Import `docs/observability/chat-routing-alerts.prometheus.yml` into the target Prometheus rule loader.
4. Import `docs/observability/chat-routing-grafana-dashboard.json` into Grafana.
5. Configure the dashboard data source to the target Prometheus instance.
6. Configure Alertmanager routing using `docs/observability/chat-routing-alertmanager-routing.example.yml` as the template.
7. Record the environment, dashboard URL, rule group name, and alert receiver in `docs/observability/chat-routing-monitoring-rollout.example.md`.

## Prometheus Queries

Use these queries to confirm ingestion:

```promql
chatagent_llm_routing_attempts_total
```

```promql
chatagent_llm_circuit_events_total
```

```promql
chatagent_llm_circuit_decisions_total
```

```promql
histogram_quantile(0.95, sum by (le, model, mode) (rate(chatagent_llm_routing_latency_seconds_bucket[5m])))
```

## Alert Triage

When `ChatAgentFirstPacketFailuresHigh` fires:

1. Open the Grafana dashboard and check whether failures are global or model-specific.
2. Open `/actuator/health/chatRouting`.
3. Open `/api/admin/chat-routing/state` with an admin token.
4. Check provider availability, API key/base URL, recent config changes, and circuit state.

When `ChatAgentAllRoutingCandidatesSkipped` fires:

1. Treat the incident as user-impacting until proven otherwise.
2. Check whether all candidates are `OPEN`, disabled, unregistered, or overridden.
3. Temporarily clear bad runtime overrides or re-enable a known-good fallback candidate.
4. Keep the alert open until at least one candidate returns to routable state and traffic succeeds.

When `ChatAgentModelCircuitOpened` fires:

1. Check whether the circuit is isolated to one provider/model.
2. Confirm fallback traffic is succeeding.
3. If fallback is succeeding, keep severity at warning and watch for repeated reopen loops.
4. If fallback also fails, escalate to the primary backend/model channel.

## Rollback

1. Mute the `chatagent-routing` rule group if alert noise is too high.
2. Remove the Grafana dashboard import if the dashboard causes provisioning issues.
3. Disable the synthetic health check if it blocks deployment incorrectly.
4. No application rollback should be needed for Phase 8 unless metrics labels or Actuator exposure were changed incorrectly.
