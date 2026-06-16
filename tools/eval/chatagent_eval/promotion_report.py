"""Legacy 10d-B2 promotion report.

The active 2026-06-16 B3.4 closeout does not use this control-matrix promotion
path. It remains available only for explicit historical/diagnostic reruns and
must not be cited as completed current acceptance evidence.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.answer_harness import LEGACY_CONTROL_MATRIX_MODES
from chatagent_eval.control_analysis import (
    BASELINE_CONTEXT_RECALL,
    BASELINE_PHRASE_RECALL,
    GATE_PER_FORMAT_REGRESSION,
    GATE_PRIMARY_RECALL_DELTA,
    GATE_PRIMARY_RECALL_TARGET,
    ALL_CONTROLS,
    ControlDelta,
    compute_citation_support_recall,
    compute_control_deltas,
    compute_latency_summary,
    compute_token_summary,
    evaluate_10d_b2_gates,
    gate_summary,
    require_10d_b2_gate_policy,
)
from chatagent_eval.reports import SAFE_RUN_ID, write_json_artifact


def write_10d_b2_promotion_report(
    *,
    candidate_artifact: Path,
    selection_artifact: Path,
    preflight_artifact: Path,
    output_root: Path,
    run_id: str,
    expected_dataset_hash: str,
    expected_search_split_hashes: Mapping[str, str],
    expected_holdout_split_hash: str,
    baseline_context_recall: float = BASELINE_CONTEXT_RECALL,
    baseline_phrase_recall: float = BASELINE_PHRASE_RECALL,
    git_branch: str = "unknown",
    git_sha: str = "unknown",
) -> Path:
    """Read a candidate answer artifact and produce a 10d-B2 promotion report.

    The candidate artifact directory must contain ``samples.jsonl`` (answer rows
    with control labels and metrics), ``metrics.json`` (optional aggregate
    metrics), and ``manifest.json`` (optional provenance metadata).

    Returns the promotion report directory.
    """
    if not SAFE_RUN_ID.fullmatch(run_id):
        raise ValueError(f"unsafe run id: {run_id}")

    samples_path = candidate_artifact / "samples.jsonl"
    if not samples_path.exists():
        raise FileNotFoundError(f"candidate artifact missing samples.jsonl: {candidate_artifact}")
    metrics_path = candidate_artifact / "metrics.json"
    manifest_path = candidate_artifact / "manifest.json"
    for required_path in (metrics_path, manifest_path):
        if not required_path.exists():
            raise FileNotFoundError(f"candidate artifact missing {required_path.name}: {candidate_artifact}")

    rows = _read_jsonl(samples_path)
    candidate_metrics = _read_json(metrics_path)
    candidate_manifest = _read_json(manifest_path)
    evidence = _validate_promotion_evidence(
        rows=rows,
        metrics=candidate_metrics,
        manifest=candidate_manifest,
        selection_artifact=selection_artifact,
        preflight_artifact=preflight_artifact,
        expected_dataset_hash=expected_dataset_hash,
        expected_search_split_hashes=expected_search_split_hashes,
        expected_holdout_split_hash=expected_holdout_split_hash,
    )
    gate_policy = require_10d_b2_gate_policy(candidate_metrics.get("gatePolicy"))
    limits = candidate_metrics.get("limits") or {}
    if (
        float(limits["maxLatencyP95Ms"]) != gate_policy["maxLatencyP95Ms"]
        or float(limits["maxCostUsd"]) != gate_policy["maxCostUsd"]
    ):
        raise ValueError("promotion candidate limits do not match its frozen gate policy")
    if (
        baseline_context_recall != gate_policy["baselineContextRecall"]
        or baseline_phrase_recall != gate_policy["baselinePhraseRecall"]
    ):
        raise ValueError("promotion baseline arguments do not match the selected candidate's frozen gate policy")

    # Extract aggregate metrics from the candidate artifact
    retrieval_metrics = _extract_retrieval_metrics(candidate_metrics)
    context_recall = retrieval_metrics.get("contextRecallAtK")
    phrase_recall = retrieval_metrics.get("phraseRecall")

    # Compute control deltas
    answer_metrics = ("faithfulness", "response_relevancy", "context_precision", "context_recall",
                      "factual_correctness", "semantic_similarity")
    full_rag_vs_no_rag = compute_control_deltas(rows, metrics=answer_metrics, baseline_mode="no-rag", comparison_mode="full-rag")
    wrong_vs_full = compute_control_deltas(rows, metrics=answer_metrics, baseline_mode="full-rag", comparison_mode="wrong-context")
    oracle_vs_full = compute_control_deltas(rows, metrics=answer_metrics, baseline_mode="full-rag", comparison_mode="oracle-context")
    reranker_on_vs_off = compute_control_deltas(
        rows, metrics=answer_metrics, baseline_mode="reranker-off", comparison_mode="reranker-on"
    )
    all_deltas = list(full_rag_vs_no_rag) + list(wrong_vs_full) + list(oracle_vs_full) + list(reranker_on_vs_off)

    # Compute token and latency summaries
    token_summary = compute_token_summary(rows)
    latency_summary = compute_latency_summary(rows)
    production_latency_summary = compute_latency_summary(rows, mode="full-rag")
    citation_support = compute_citation_support_recall(rows, mode="full-rag")

    # Extract per-format metrics from candidate
    per_format = candidate_metrics.get("perFormat") or {}

    # Evaluate promotion gates
    gates = evaluate_10d_b2_gates(
        context_recall=context_recall,
        phrase_recall=phrase_recall,
        control_deltas=all_deltas,
        per_format_metrics=per_format,
        baseline_per_format=_baseline_per_format(),
        citation_support_recall=citation_support,
        latency_p95_ms=production_latency_summary.p95_total_ms,
        cost_usd=float(candidate_metrics["costUsd"]),
        max_latency_p95_ms=float(candidate_metrics["limits"]["maxLatencyP95Ms"]),
        max_cost_usd=float(candidate_metrics["limits"]["maxCostUsd"]),
        pre_rerank_mrr=retrieval_metrics.get("preRerankMrr"),
        post_rerank_mrr=retrieval_metrics.get("mrr"),
        baseline_context_recall=baseline_context_recall,
        baseline_phrase_recall=baseline_phrase_recall,
        min_citation_support=gate_policy["minCitationSupport"],
        min_full_rag_faithfulness=gate_policy["minFullRagFaithfulness"],
        min_negative_control_degradation=gate_policy["minNegativeControlDegradation"],
    )
    summary = gate_summary(gates)

    # Build the promotion report
    report = {
        "runId": run_id,
        "suite": "doc-ingestion-rag-effectiveness",
        "mode": "promotion",
        "status": summary["overall"],
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "git": {"branch": git_branch, "sha": git_sha},
        "candidate": {
            "artifact": str(candidate_artifact),
            "configFingerprint": candidate_manifest.get("configFingerprint"),
            "datasetId": candidate_manifest.get("datasetId"),
            "datasetHash": candidate_manifest.get("datasetHash"),
        },
        "evidenceValidation": evidence,
        "baselines": {
            "contextRecallAtK": gate_policy["baselineContextRecall"],
            "phraseRecall": gate_policy["baselinePhraseRecall"],
            "phase10Artifact": "phase10a/doc-ingestion-full-ee056c79",
            "phase10dArtifact": "phase10d/accepted-size-rerun/doc-ingestion-full-35ed4ead",
        },
        "retrievalMetrics": retrieval_metrics,
        "controlDeltas": {
            "fullRagVsNoRag": [_delta_dict(d) for d in full_rag_vs_no_rag],
            "wrongContextVsFullRag": [_delta_dict(d) for d in wrong_vs_full],
            "oracleContextVsFullRag": [_delta_dict(d) for d in oracle_vs_full],
            "rerankerOnVsOff": [_delta_dict(d) for d in reranker_on_vs_off],
        },
        "tokenSummary": {
            "totalPrompt": token_summary.total_prompt,
            "totalCompletion": token_summary.total_completion,
            "totalTokens": token_summary.total_tokens,
            "meanPrompt": token_summary.mean_prompt,
            "meanCompletion": token_summary.mean_completion,
            "byControl": token_summary.by_control,
        },
        "latencySummary": {
            "meanTotalMs": latency_summary.mean_total_ms,
            "p95TotalMs": latency_summary.p95_total_ms,
            "fullRagMeanTotalMs": production_latency_summary.mean_total_ms,
            "fullRagP95TotalMs": production_latency_summary.p95_total_ms,
            "byControl": latency_summary.by_control,
        },
        "citationSupport": {
            "fullRagReferenceCitationRecall": citation_support,
        },
        "perFormat": per_format,
        "gateResults": summary,
        "gatePolicy": gate_policy,
        "productionDefaultsChanged": False,
        "requiresSeparateReviewedCommit": True,
        "rollbackInstructions": (
            "Restore production defaults: topK=3, candidateK=12, rrfK=60. "
            "Do not apply any candidate parameters without a separate reviewed promotion commit."
        ),
    }

    # Write artifacts
    resolved_output_root = output_root.resolve()
    run_dir = (resolved_output_root / run_id).resolve()
    if not run_dir.is_relative_to(resolved_output_root):
        raise ValueError(f"run directory escapes output root: {run_id}")
    if run_dir.exists():
        raise ValueError(f"promotion artifact root already exists: {run_dir}")
    run_dir.mkdir(parents=True)

    write_json_artifact(run_dir / "promotion-report.json", report)
    (run_dir / "promotion-decision.md").write_text(_markdown_decision(report), encoding="utf-8")
    return run_dir


# ── Helpers ─────────────────────────────────────────────────────────────────


def _delta_dict(delta: ControlDelta) -> dict[str, Any]:
    return {
        "metric": delta.metric,
        "baselineMode": delta.baseline_mode,
        "comparisonMode": delta.comparison_mode,
        "baselineValue": delta.baseline_value,
        "comparisonValue": delta.comparison_value,
        "delta": delta.delta,
        "direction": delta.direction,
    }


def _extract_retrieval_metrics(metrics: Mapping[str, Any]) -> dict[str, float | None]:
    retrieval = metrics.get("retrieval") or {}
    per_format: dict[str, float | None] = {}
    if isinstance(retrieval, Mapping):
        for key in (
            "contextRecallAtK",
            "hitAtK",
            "mrr",
            "preRerankMrr",
            "phraseRecall",
            "topK",
            "candidateK",
            "rerankerMaxCandidates",
            "rrfK",
        ):
            val = retrieval.get(key)
            if isinstance(val, (int, float)) and not isinstance(val, bool):
                per_format[key] = float(val)
            else:
                per_format[key] = None
    return per_format


def _validate_promotion_evidence(
    *,
    rows: Sequence[Mapping[str, Any]],
    metrics: Mapping[str, Any],
    manifest: Mapping[str, Any],
    selection_artifact: Path,
    preflight_artifact: Path,
    expected_dataset_hash: str,
    expected_search_split_hashes: Mapping[str, str],
    expected_holdout_split_hash: str,
) -> dict[str, Any]:
    if not rows:
        raise ValueError("promotion candidate contains no answer rows")
    if metrics.get("status") != "pass":
        raise ValueError("promotion candidate metrics status must be pass")
    if manifest.get("datasetHash") != expected_dataset_hash:
        raise ValueError("promotion candidate dataset hash does not match accepted dataset")
    split_hashes = _manifest_split_hashes(manifest)
    if split_hashes.get("holdout") != expected_holdout_split_hash:
        raise ValueError("promotion candidate holdout split hash does not match accepted sealed split")
    splits = {str(row.get("split") or "") for row in rows}
    if splits != {"holdout"}:
        raise ValueError(f"promotion candidate must contain sealed holdout rows only: {sorted(splits)}")

    paired = _validate_paired_controls(rows)
    retrieval = _extract_retrieval_metrics(metrics)
    missing_retrieval = [
        key
        for key in ("contextRecallAtK", "phraseRecall", "mrr", "preRerankMrr", "topK", "candidateK", "rerankerMaxCandidates")
        if retrieval.get(key) is None
    ]
    if missing_retrieval:
        raise ValueError(f"promotion candidate missing retrieval metrics: {missing_retrieval}")
    if retrieval["rerankerMaxCandidates"] < retrieval["candidateK"]:
        raise ValueError("promotion candidate rerankerMaxCandidates must cover the full candidateK pool")
    per_format = metrics.get("perFormat")
    missing_formats = sorted(set(_baseline_per_format()) - set(per_format or {}))
    if missing_formats:
        raise ValueError(f"promotion candidate missing per-format metrics: {missing_formats}")
    limits = metrics.get("limits") or {}
    missing_limits = [
        key for key in ("maxLatencyP95Ms", "maxCostUsd") if not _is_number(limits.get(key))
    ]
    if missing_limits or not _is_number(metrics.get("costUsd")):
        raise ValueError("promotion candidate missing frozen latency/cost limits or measured cost")

    selection = _read_selection(selection_artifact)
    if selection.get("datasetHash") != expected_dataset_hash:
        raise ValueError("joint selection dataset hash does not match accepted dataset")
    if selection.get("selectedConfigFingerprint") != manifest.get("configFingerprint"):
        raise ValueError("promotion candidate was not selected by the supplied joint selection artifact")
    if selection.get("sealedHoldoutAccessed") is not False:
        raise ValueError("joint selection must explicitly prove sealed holdout was not accessed")
    selection_splits = set(selection.get("searchSplits") or [])
    if selection_splits != {"calibration", "development"}:
        raise ValueError("joint selection must use exactly calibration and development rows")
    selection_split_hashes = selection.get("searchSplitHashes") or {}
    if (
        not isinstance(selection_split_hashes, Mapping)
        or set(selection_split_hashes) != selection_splits
        or any(not str(value) for value in selection_split_hashes.values())
    ):
        raise ValueError("joint selection must record hashes for every calibration/development search split")
    if dict(selection_split_hashes) != dict(expected_search_split_hashes):
        raise ValueError("joint selection search split hashes do not match accepted frozen splits")
    if "holdout" in selection_splits:
        raise ValueError("joint selection accessed sealed holdout")
    search_source_groups = {str(value) for value in selection.get("searchSourceGroupIds") or [] if str(value)}
    holdout_source_groups = {str(row.get("sourceGroupId") or "") for row in rows}
    if not search_source_groups or not holdout_source_groups or "" in holdout_source_groups:
        raise ValueError("selection and holdout evidence must record non-empty source groups")
    overlap = sorted(search_source_groups & holdout_source_groups)
    if overlap:
        raise ValueError(f"sealed holdout source-group overlap detected: {overlap[:5]}")
    preflight = _read_preflight(preflight_artifact)
    if preflight.get("status") != "pass":
        raise ValueError("promotion requires a passing 10d-B2 preflight artifact")
    preflight_config = preflight.get("config") or {}
    if preflight_config.get("datasetId") != manifest.get("datasetId"):
        raise ValueError("preflight dataset does not match promotion candidate")
    budget_check = next(
        (check for check in preflight.get("checks") or [] if check.get("id") == "budget.candidateK"),
        None,
    )
    approved_candidate_k = (
        ((budget_check or {}).get("candidateK") or {}).get("approved") or []
        if isinstance(budget_check, Mapping)
        else []
    )
    if retrieval["candidateK"] not in approved_candidate_k:
        raise ValueError("preflight budget did not approve the promotion candidateK")
    return {
        "datasetHash": expected_dataset_hash,
        "holdoutSplitHash": expected_holdout_split_hash,
        "pairedSourceSamples": paired,
        "requiredControlModes": list(LEGACY_CONTROL_MATRIX_MODES),
        "selectionRunId": selection.get("runId"),
        "selectionSearchSplits": sorted(selection_splits),
        "sourceGroupOverlapCount": 0,
        "preflightRunId": preflight.get("runId"),
    }


def _validate_paired_controls(rows: Sequence[Mapping[str, Any]]) -> int:
    grouped: dict[str, dict[str, Mapping[str, Any]]] = {}
    for row in rows:
        metadata = row.get("metadata") or {}
        source_sample_id = str(metadata.get("sourceSampleId") or "")
        control = metadata.get("controlRun") or {}
        mode = str(control.get("mode") or "") if isinstance(control, Mapping) else ""
        if not source_sample_id or mode not in LEGACY_CONTROL_MATRIX_MODES:
            raise ValueError("promotion row is missing sourceSampleId or a required control mode")
        if mode in grouped.setdefault(source_sample_id, {}):
            raise ValueError(f"duplicate control mode for source sample {source_sample_id}: {mode}")
        grouped[source_sample_id][mode] = row
    required = set(LEGACY_CONTROL_MATRIX_MODES)
    for source_sample_id, by_mode in grouped.items():
        if set(by_mode) != required:
            raise ValueError(f"incomplete paired controls for source sample {source_sample_id}")
        identities = {
            (str(row.get("sourceGroupId") or ""), str(row.get("userInput") or ""), str(row.get("split") or ""))
            for row in by_mode.values()
        }
        if len(identities) != 1:
            raise ValueError(f"paired controls disagree on source identity for {source_sample_id}")
        wrong_context = by_mode["wrong-context"]
        reference_ids = {str(value) for value in wrong_context.get("referenceContextIds") or []}
        wrong_ids = {
            str(context.get("chunkId") or context.get("id") or "")
            for context in wrong_context.get("retrievedContexts") or []
            if isinstance(context, Mapping)
        }
        if reference_ids & wrong_ids:
            raise ValueError(f"wrong-context control overlaps reference contexts for {source_sample_id}")
    return len(grouped)


def _manifest_split_hashes(manifest: Mapping[str, Any]) -> Mapping[str, Any]:
    config = manifest.get("config") or {}
    if not isinstance(config, Mapping):
        return {}
    value = config.get("splitHashes") or config.get("inputSplitHashes") or {}
    return value if isinstance(value, Mapping) else {}


def _read_selection(path: Path) -> dict[str, Any]:
    selection_path = path / "selection.json" if path.is_dir() else path
    if not selection_path.exists():
        raise FileNotFoundError(f"joint selection artifact missing selection.json: {path}")
    return _read_json(selection_path)


def _read_preflight(path: Path) -> dict[str, Any]:
    preflight_path = path / "preflight.json" if path.is_dir() else path
    if not preflight_path.exists():
        raise FileNotFoundError(f"preflight artifact missing preflight.json: {path}")
    return _read_json(preflight_path)


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _baseline_per_format() -> dict[str, dict[str, float]]:
    """Phase 10 baseline per-format contextRecall values."""
    return {
        "SEC_HTML": {"contextRecallAtK": 0.60},
        "PDF": {"contextRecallAtK": 0.66},
        "DOCX": {"contextRecallAtK": 0.65},
        "XLSX": {"contextRecallAtK": 0.61},
        "WEB_MD": {"contextRecallAtK": 0.63},
    }


def _markdown_decision(report: Mapping[str, Any]) -> str:
    gates = report["gateResults"]
    lines = [
        "# 10d-B2 Promotion Decision",
        "",
        f"Status: `{gates['overall']}`",
        f"Pass: {gates['pass']}  Fail: {gates['fail']}  Warn: {gates['warn']}  Skip: {gates['skip']}",
        "",
        "## Gate Results",
        "",
        "| Gate | Status | Passed |",
        "|---|---|---|",
    ]
    for gate in gates["gates"]:
        check = "Y" if gate["passed"] else "N"
        lines.append(f"| {gate['gate']} | `{gate['status']}` | {check} |")

    lines.extend([
        "",
        "## Control Deltas",
        "",
        "| Metric | Full RAG vs No-RAG | Wrong vs Full RAG | Oracle vs Full RAG | Reranker On vs Off |",
        "|---|---:|---:|---:|---:|",
    ])
    fr = {d["metric"]: d for d in report["controlDeltas"]["fullRagVsNoRag"]}
    wr = {d["metric"]: d for d in report["controlDeltas"]["wrongContextVsFullRag"]}
    oc = {d["metric"]: d for d in report["controlDeltas"]["oracleContextVsFullRag"]}
    ro = {d["metric"]: d for d in report["controlDeltas"]["rerankerOnVsOff"]}
    for metric in sorted(set(list(fr) + list(wr) + list(oc) + list(ro))):
        fv = fr.get(metric, {}).get("delta")
        wv = wr.get(metric, {}).get("delta")
        ov = oc.get(metric, {}).get("delta")
        rv = ro.get(metric, {}).get("delta")
        lines.append(f"| {metric} | {_fmt_delta(fv)} | {_fmt_delta(wv)} | {_fmt_delta(ov)} | {_fmt_delta(rv)} |")

    lines.extend([
        "",
        "## Retrieval Metrics",
        "",
        f"- contextRecall@K: {report['retrievalMetrics'].get('contextRecallAtK', 'N/A')}",
        f"- phraseRecall: {report['retrievalMetrics'].get('phraseRecall', 'N/A')}",
        f"- Baseline contextRecall@K: {report['baselines']['contextRecallAtK']}",
        "",
        "## Production Defaults",
        "",
        "- Changed: `false`",
        "- A separate reviewed promotion commit is required.",
        "- Rollback: " + report["rollbackInstructions"],
        "",
    ])
    return "\n".join(lines)


def _fmt_delta(value: Any) -> str:
    if value is None:
        return "N/A"
    return f"{float(value):+.4f}"


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    return rows
