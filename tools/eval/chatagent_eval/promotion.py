"""Sealed-holdout policy and review-only promotion artifacts."""

from __future__ import annotations

import csv
import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from chatagent_eval.reports import SAFE_RUN_ID
from chatagent_eval.tuning import category_regression_failures

SEARCH_SPLITS = {"train", "calibration", "development"}


def validate_search_splits(search_splits: Sequence[str]) -> tuple[str, ...]:
    normalized = tuple(dict.fromkeys(str(split) for split in search_splits))
    if not normalized:
        raise ValueError("parameter search requires at least one search split")
    forbidden = [split for split in normalized if split not in SEARCH_SPLITS]
    if forbidden:
        raise ValueError(f"parameter search cannot access sealed or non-tuning splits: {forbidden}")
    return normalized


def split_audit(
    split_manifest: Mapping[str, Any],
    *,
    search_splits: Sequence[str],
    holdout_split: str,
    challenge_split: str | None,
) -> dict[str, Any]:
    validated_search = validate_search_splits(search_splits)
    splits = split_manifest.get("splits") or {}
    missing = [split for split in (*validated_search, holdout_split) if split not in splits]
    if missing:
        raise ValueError(f"split manifest is missing required splits: {missing}")
    search_groups = {
        str(group_id)
        for split in validated_search
        for group_id in splits[split].get("groupIds", [])
    }
    holdout_groups = {str(group_id) for group_id in splits[holdout_split].get("groupIds", [])}
    overlap = sorted(search_groups & holdout_groups)
    if overlap:
        raise ValueError(f"sealed holdout groups overlap tuning groups: {overlap[:5]}")
    challenge_groups: set[str] = set()
    if challenge_split and challenge_split in splits:
        challenge_groups = {str(group_id) for group_id in splits[challenge_split].get("groupIds", [])}
        challenge_overlap = sorted(search_groups & challenge_groups)
        if challenge_overlap:
            raise ValueError(f"challenge groups overlap tuning groups: {challenge_overlap[:5]}")
    return {
        "searchSplits": list(validated_search),
        "searchSplitHashes": {split: splits[split]["groupHash"] for split in validated_search},
        "searchGroupCount": len(search_groups),
        "holdoutSplit": holdout_split,
        "holdoutSplitHash": splits[holdout_split]["groupHash"],
        "holdoutGroupCount": len(holdout_groups),
        "challengeSplit": challenge_split if challenge_split in splits else None,
        "challengeSplitHash": splits[challenge_split]["groupHash"] if challenge_split in splits else None,
        "challengeGroupCount": len(challenge_groups),
        "overlapCount": 0,
    }


def gate_failures(
    metrics: Mapping[str, float | None],
    *,
    category_metrics: Mapping[str, float],
    baseline_category_metrics: Mapping[str, float] | None,
    policy: Mapping[str, Any],
    latency_p95_ms: float | None,
    cost_usd: float | None,
) -> list[str]:
    failures: list[str] = []
    for metric, threshold in (policy.get("hardGates") or {}).items():
        value = metrics.get(metric)
        if value is None:
            failures.append(f"missing-metric:{metric}")
        elif "min" in threshold and value < float(threshold["min"]):
            failures.append(f"hard-gate-min:{metric}")
        elif "max" in threshold and value > float(threshold["max"]):
            failures.append(f"hard-gate-max:{metric}")
    if latency_p95_ms is not None and latency_p95_ms > float(policy.get("latencyP95MaxMs", float("inf"))):
        failures.append("latency-p95")
    if cost_usd is not None and cost_usd > float(policy.get("costMaxUsd", float("inf"))):
        failures.append("cost")
    if baseline_category_metrics is not None:
        failures.extend(
            category_regression_failures(
                baseline_category_metrics,
                category_metrics,
                tolerance=float(policy.get("categoryRegressionTolerance", 0.0)),
            )
        )
    return failures


def build_promotion_candidate(
    *,
    experiment_id: str,
    suite: str,
    champion: Mapping[str, Any],
    holdout_verification: Mapping[str, Any],
    registry_id: str,
) -> dict[str, Any]:
    holdout_passed = holdout_verification.get("status") == "pass"
    return {
        "experimentId": experiment_id,
        "suite": suite,
        "registryId": registry_id,
        "status": "proposed" if holdout_passed else "rejected",
        "reviewStatus": "pending",
        "trialId": champion["trialId"],
        "configFingerprint": champion["configFingerprint"],
        "parameters": dict(champion["parameters"]),
        "developmentMetrics": dict(champion["metrics"]),
        "holdoutVerificationStatus": holdout_verification.get("status"),
        "productionDefaultsChanged": False,
        "requiresSeparateReviewedChange": True,
    }


def write_experiment_artifacts(
    *,
    output_root: Path,
    experiment_manifest: Mapping[str, Any],
    parameter_space: Mapping[str, Any],
    trials: Sequence[Mapping[str, Any]],
    leaderboard: Sequence[Mapping[str, Any]],
    pareto_frontier: Sequence[Mapping[str, Any]],
    champion_candidate: Mapping[str, Any],
    holdout_verification: Mapping[str, Any],
) -> Path:
    experiment_id = str(experiment_manifest["experimentId"])
    if not SAFE_RUN_ID.fullmatch(experiment_id):
        raise ValueError(f"unsafe experiment id: {experiment_id}")
    resolved_root = output_root.resolve()
    experiment_dir = (resolved_root / experiment_id).resolve()
    if not experiment_dir.is_relative_to(resolved_root):
        raise ValueError(f"experiment directory escapes output root: {experiment_id}")
    experiment_dir.mkdir(parents=True, exist_ok=True)
    _write_json(experiment_dir / "experiment-manifest.json", experiment_manifest)
    _write_json(experiment_dir / "parameter-space.yaml", parameter_space)
    _write_jsonl(experiment_dir / "trials.jsonl", trials)
    _write_leaderboard(experiment_dir / "leaderboard.csv", leaderboard)
    _write_json(experiment_dir / "pareto-frontier.json", {"trials": list(pareto_frontier)})
    _write_json(experiment_dir / "champion-candidate.yaml", champion_candidate)
    _write_json(experiment_dir / "holdout-verification.json", holdout_verification)
    _write_promotion_decision(experiment_dir / "promotion-decision.md", champion_candidate, holdout_verification)
    return experiment_dir


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def _write_leaderboard(path: Path, rows: Sequence[Mapping[str, Any]]) -> None:
    fields = [
        "rank",
        "trialId",
        "stage",
        "status",
        "primaryMetric",
        "confidenceLower",
        "confidenceUpper",
        "latencyP95Ms",
        "executionElapsedMs",
        "costUsd",
        "gateFailures",
        "configFingerprint",
        "parameters",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field) for field in fields})


def _write_promotion_decision(
    path: Path,
    candidate: Mapping[str, Any],
    holdout: Mapping[str, Any],
) -> None:
    lines = [
        "# Promotion Decision",
        "",
        f"- Candidate status: `{candidate['status']}`",
        f"- Review status: `{candidate['reviewStatus']}`",
        f"- Holdout verification: `{holdout.get('status')}`",
        f"- Trial: `{candidate['trialId']}`",
        "- Production defaults changed: `false`",
        "- A separate reviewed promotion change is required before runtime defaults may change.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")
