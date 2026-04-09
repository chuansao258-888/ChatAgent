# ChatAgent Routing Monitoring Rollout Record

Use this file as a copy-and-fill template when importing Phase 8 monitoring into a real environment.

## Environment

- Environment:
- Cluster/namespace:
- ChatAgent service name:
- Prometheus URL:
- Grafana URL:
- Alertmanager URL:
- Import owner:
- Import date:

## Prometheus

- Rule file imported: `docs/observability/chat-routing-alerts.prometheus.yml`
- Rule group name: `chatagent-routing`
- Rule validation command/result:
- Scrape job name:
- Confirmed metrics:
  - `chatagent_llm_routing_attempts_total`:
  - `chatagent_llm_circuit_events_total`:
  - `chatagent_llm_circuit_decisions_total`:
  - `chatagent_llm_routing_latency_seconds_count`:

## Grafana

- Dashboard file imported: `docs/observability/chat-routing-grafana-dashboard.json`
- Dashboard URL:
- Data source:
- Variables checked:
  - `job`:
  - `model`:
- Panels with data:
  - Routing Attempts:
  - Routing Latency Average:
  - First Packet Failure Ratio:
  - Circuit Events:
  - Circuit Decisions:

## Health Check

- Health endpoint:
- Healthy response checked:
- Synthetic check created:
- Health details access policy:

## Alert Routing

- Critical receiver:
- Warning receiver:
- Muted/staging-only window:
- Notification test result:

## Degraded Scenario Validation

- Scenario used:
- Time triggered:
- Dashboard observation:
- Health observation:
- Cleanup action:
- Recovery confirmed:

## Follow-ups

- Threshold tuning needed:
- Missing labels:
- Dashboard adjustments:
- Alert route adjustments:
