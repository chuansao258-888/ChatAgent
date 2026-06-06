"""Suite-owned threshold evaluation."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any


def evaluate_thresholds(
    metrics: Mapping[str, float | None],
    thresholds: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    overall = "pass"
    for metric_name, rule in thresholds.items():
        value = metrics.get(metric_name)
        passed = value is not None
        if passed and "min" in rule:
            passed = value >= rule["min"]
        if passed and "max" in rule:
            passed = value <= rule["max"]
        severity = rule.get("severity", "fail")
        if severity not in {"fail", "warn"}:
            raise ValueError(f"invalid threshold severity for {metric_name}: {severity}")
        result_status = "pass" if passed else severity
        results.append(
            {
                "metric": metric_name,
                "value": value,
                "min": rule.get("min"),
                "max": rule.get("max"),
                "severity": severity,
                "status": result_status,
            }
        )
        if result_status == "fail":
            overall = "fail"
        elif result_status == "warn" and overall == "pass":
            overall = "warn"
    return {"status": overall, "results": results}
