"""Legacy 10d-B2 joint candidate selection from control-matrix evidence.

Superseded for the active B3.4 closeout by the 2026-06-16 one-run full-rag
RAGAS decision. Retained only for explicit historical/diagnostic reruns.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.control_analysis import (
    compute_citation_support_recall,
    compute_control_deltas,
    compute_latency_summary,
    evaluate_10d_b2_gates,
    gate_summary,
    require_10d_b2_gate_policy,
)
from chatagent_eval.promotion_report import (
    _baseline_per_format,
    _extract_retrieval_metrics,
    _is_number,
    _manifest_split_hashes,
    _validate_paired_controls,
)
from chatagent_eval.reports import SAFE_RUN_ID, write_json_artifact


def write_10d_b2_joint_selection(
    *,
    candidate_artifacts: Sequence[Path],
    output_root: Path,
    run_id: str,
    expected_dataset_hash: str,
    expected_search_split_hashes: Mapping[str, str],
) -> Path:
    """Select one eligible candidate without accessing sealed holdout rows."""
    if not SAFE_RUN_ID.fullmatch(run_id):
        raise ValueError(f"unsafe run id: {run_id}")
    if not candidate_artifacts:
        raise ValueError("joint selection requires at least one candidate artifact")
    if not expected_search_split_hashes or not set(expected_search_split_hashes) <= {"calibration", "development"}:
        raise ValueError("joint selection search split hashes must be calibration/development only")

    candidates = [
        _evaluate_candidate(
            artifact=artifact,
            expected_dataset_hash=expected_dataset_hash,
            expected_search_split_hashes=expected_search_split_hashes,
        )
        for artifact in candidate_artifacts
    ]
    search_source_groups = set(candidates[0]["sourceGroupIds"])
    if any(set(candidate["sourceGroupIds"]) != search_source_groups for candidate in candidates[1:]):
        raise ValueError("joint selection candidates do not use the same calibration/development source groups")
    search_source_samples = set(candidates[0]["sourceSampleIds"])
    if any(set(candidate["sourceSampleIds"]) != search_source_samples for candidate in candidates[1:]):
        raise ValueError("joint selection candidates do not use the same calibration/development source samples")
    eligible = [candidate for candidate in candidates if candidate["gateResults"]["overall"] == "pass"]
    if not eligible:
        raise ValueError("joint selection has no candidate that passes retrieval, answer, control, citation, and cost gates")
    selected = min(eligible, key=_selection_key)

    selection = {
        "runId": run_id,
        "suite": "doc-ingestion-rag-effectiveness",
        "mode": "joint-selection",
        "status": "pass",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "datasetHash": expected_dataset_hash,
        "searchSplits": sorted(expected_search_split_hashes),
        "searchSplitHashes": dict(expected_search_split_hashes),
        "searchSourceGroupIds": sorted(search_source_groups),
        "sealedHoldoutAccessed": False,
        "selectedArtifact": selected["artifact"],
        "selectedConfigFingerprint": selected["configFingerprint"],
        "candidates": candidates,
    }
    resolved_root = output_root.resolve()
    run_dir = (resolved_root / run_id).resolve()
    if not run_dir.is_relative_to(resolved_root):
        raise ValueError(f"run directory escapes output root: {run_id}")
    if run_dir.exists():
        raise ValueError(f"joint selection artifact root already exists: {run_dir}")
    run_dir.mkdir(parents=True)
    write_json_artifact(run_dir / "selection.json", selection)
    return run_dir


def _evaluate_candidate(
    *,
    artifact: Path,
    expected_dataset_hash: str,
    expected_search_split_hashes: Mapping[str, str],
) -> dict[str, Any]:
    manifest = _read_json(artifact / "manifest.json")
    metrics = _read_json(artifact / "metrics.json")
    rows = _read_jsonl(artifact / "samples.jsonl")
    if manifest.get("datasetHash") != expected_dataset_hash:
        raise ValueError(f"joint selection candidate dataset hash mismatch: {artifact}")
    split_hashes = _manifest_split_hashes(manifest)
    for split, expected_hash in expected_search_split_hashes.items():
        if split_hashes.get(split) != expected_hash:
            raise ValueError(f"joint selection candidate split hash mismatch for {split}: {artifact}")
    row_splits = {str(row.get("split") or "") for row in rows}
    if row_splits != set(expected_search_split_hashes):
        raise ValueError(f"joint selection candidate must contain exactly the calibration/development search splits: {artifact}")
    source_group_ids = {str(row.get("sourceGroupId") or "") for row in rows}
    if not source_group_ids or "" in source_group_ids:
        raise ValueError(f"joint selection candidate rows must record sourceGroupId: {artifact}")
    source_sample_ids = {
        str((row.get("metadata") or {}).get("sourceSampleId") or "")
        for row in rows
    }
    if not source_sample_ids or "" in source_sample_ids:
        raise ValueError(f"joint selection candidate rows must record sourceSampleId: {artifact}")
    paired_count = _validate_paired_controls(rows)
    if metrics.get("status") != "pass":
        raise ValueError(f"joint selection candidate metrics status must be pass: {artifact}")

    retrieval = _extract_retrieval_metrics(metrics)
    candidate_k = retrieval.get("candidateK")
    reranker_max_candidates = retrieval.get("rerankerMaxCandidates")
    if (
        candidate_k is None
        or reranker_max_candidates is None
        or reranker_max_candidates < candidate_k
    ):
        raise ValueError(f"joint selection candidate does not rerank the full candidate pool: {artifact}")
    answer_metrics = (
        "faithfulness",
        "response_relevancy",
        "context_precision",
        "context_recall",
        "factual_correctness",
        "semantic_similarity",
    )
    control_deltas = [
        *compute_control_deltas(rows, metrics=answer_metrics, baseline_mode="no-rag", comparison_mode="full-rag"),
        *compute_control_deltas(rows, metrics=answer_metrics, baseline_mode="full-rag", comparison_mode="wrong-context"),
        *compute_control_deltas(rows, metrics=answer_metrics, baseline_mode="full-rag", comparison_mode="oracle-context"),
        *compute_control_deltas(rows, metrics=answer_metrics, baseline_mode="reranker-off", comparison_mode="reranker-on"),
    ]
    latency = compute_latency_summary(rows, mode="full-rag")
    limits = metrics.get("limits") or {}
    gate_policy = require_10d_b2_gate_policy(metrics.get("gatePolicy"))
    if (
        not _is_number(limits.get("maxLatencyP95Ms"))
        or not _is_number(limits.get("maxCostUsd"))
        or float(limits["maxLatencyP95Ms"]) != gate_policy["maxLatencyP95Ms"]
        or float(limits["maxCostUsd"]) != gate_policy["maxCostUsd"]
    ):
        raise ValueError(f"joint selection candidate limits do not match its frozen gate policy: {artifact}")
    gates = evaluate_10d_b2_gates(
        context_recall=retrieval.get("contextRecallAtK"),
        phrase_recall=retrieval.get("phraseRecall"),
        control_deltas=control_deltas,
        per_format_metrics=metrics.get("perFormat") or {},
        baseline_per_format=_baseline_per_format(),
        citation_support_recall=compute_citation_support_recall(rows),
        latency_p95_ms=latency.p95_total_ms,
        cost_usd=float(metrics["costUsd"]) if _is_number(metrics.get("costUsd")) else None,
        max_latency_p95_ms=float(limits["maxLatencyP95Ms"]) if _is_number(limits.get("maxLatencyP95Ms")) else None,
        max_cost_usd=float(limits["maxCostUsd"]) if _is_number(limits.get("maxCostUsd")) else None,
        pre_rerank_mrr=retrieval.get("preRerankMrr"),
        post_rerank_mrr=retrieval.get("mrr"),
        baseline_context_recall=gate_policy["baselineContextRecall"],
        baseline_phrase_recall=gate_policy["baselinePhraseRecall"],
        min_citation_support=gate_policy["minCitationSupport"],
        min_full_rag_faithfulness=gate_policy["minFullRagFaithfulness"],
        min_negative_control_degradation=gate_policy["minNegativeControlDegradation"],
    )
    summary = gate_summary(gates)
    return {
        "artifact": str(artifact),
        "configFingerprint": manifest.get("configFingerprint"),
        "pairedSourceSamples": paired_count,
        "sourceGroupIds": sorted(source_group_ids),
        "sourceSampleIds": sorted(source_sample_ids),
        "retrieval": retrieval,
        "citationSupport": compute_citation_support_recall(rows),
        "latencyP95Ms": latency.p95_total_ms,
        "costUsd": metrics.get("costUsd"),
        "gateResults": summary,
    }


def _selection_key(candidate: Mapping[str, Any]) -> tuple[Any, ...]:
    retrieval = candidate["retrieval"]
    return (
        -float(retrieval["contextRecallAtK"]),
        -float(retrieval["mrr"]),
        -float(candidate["citationSupport"]),
        float(candidate["latencyP95Ms"]),
        float(candidate["costUsd"]),
        str(candidate["configFingerprint"]),
    )


def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(path)
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise FileNotFoundError(path)
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
