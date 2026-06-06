"""Normalized evaluation artifact writing."""

from __future__ import annotations

import json
import re
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Any

SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9._-]+$")


def build_manifest(
    *,
    run_id: str,
    suite: str,
    mode: str,
    timestamp: str,
    git_branch: str,
    git_sha: str,
    dataset_id: str,
    dataset_hash: str,
    config: Mapping[str, Any],
    config_fingerprint: str,
    models: Mapping[str, str] | None = None,
    thresholds: Mapping[str, Any] | None = None,
    artifact_files: Iterable[str] = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl"),
    tuning: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    manifest = {
        "runId": run_id,
        "suite": suite,
        "mode": mode,
        "timestamp": timestamp,
        "gitBranch": git_branch,
        "gitSha": git_sha,
        "datasetId": dataset_id,
        "datasetHash": dataset_hash,
        "config": dict(config),
        "configFingerprint": config_fingerprint,
        "models": dict(models or {}),
        "thresholds": dict(thresholds or {}),
        "artifactFiles": list(artifact_files),
    }
    if tuning is not None:
        manifest["tuning"] = dict(tuning)
    return manifest


def write_run_artifacts(
    output_root: Path,
    manifest: Mapping[str, Any],
    metrics: Mapping[str, Any],
    samples: Iterable[Mapping[str, Any]] = (),
    failures: Iterable[Mapping[str, Any]] = (),
) -> Path:
    run_id = str(manifest["runId"])
    if not SAFE_RUN_ID.fullmatch(run_id):
        raise ValueError(f"unsafe run id: {run_id}")
    resolved_output_root = output_root.resolve()
    run_dir = (resolved_output_root / run_id).resolve()
    if not run_dir.is_relative_to(resolved_output_root):
        raise ValueError(f"run directory escapes output root: {run_id}")
    run_dir.mkdir(parents=True, exist_ok=True)
    _write_json(run_dir / "manifest.json", manifest)
    _write_json(run_dir / "metrics.json", metrics)
    _write_jsonl(run_dir / "samples.jsonl", samples)
    _write_jsonl(run_dir / "failures.jsonl", failures)
    return run_dir


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _write_jsonl(path: Path, rows: Iterable[Mapping[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
