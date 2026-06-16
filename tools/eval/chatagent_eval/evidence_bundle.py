"""Compose retrieval and scored answer artifacts for legacy 10d-B2 diagnostics.

The active B3.4 closeout no longer runs the old control matrix. This helper is
kept only for explicitly requested historical/diagnostic evidence assembly.
"""

from __future__ import annotations

import json
import shutil
from collections.abc import Mapping
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.control_analysis import (
    BASELINE_CONTEXT_RECALL,
    BASELINE_PHRASE_RECALL,
    GATE_CITATION_SUPPORT,
    GATE_FULL_RAG_FAITHFULNESS,
    GATE_NEGATIVE_CONTROL_DEGRADATION,
    GATE_PER_FORMAT_REGRESSION,
    GATE_PRIMARY_RECALL_DELTA,
    GATE_PRIMARY_RECALL_TARGET,
)
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import SAFE_RUN_ID, write_json_artifact


def write_10d_b2_evidence_bundle(
    *,
    retrieval_metrics_path: Path,
    answer_artifact: Path,
    scored_answer_artifact: Path,
    cost_evidence_path: Path,
    output_root: Path,
    run_id: str,
    max_latency_p95_ms: float,
    max_cost_usd: float,
) -> Path:
    """Write one immutable candidate bundle consumed by joint selection and promotion."""
    if not SAFE_RUN_ID.fullmatch(run_id):
        raise ValueError(f"unsafe run id: {run_id}")
    if min(max_latency_p95_ms, max_cost_usd) < 0:
        raise ValueError("cost and latency limits must be non-negative")

    answer_manifest = _read_json(answer_artifact / "manifest.json")
    scored_manifest = _read_json(scored_answer_artifact / "manifest.json")
    scored_metrics = _read_json(scored_answer_artifact / "metrics.json")
    cost_evidence = _read_json(cost_evidence_path)
    retrieval_source = _read_json(retrieval_metrics_path)
    samples_path = scored_answer_artifact / "samples.jsonl"
    if not samples_path.exists():
        raise FileNotFoundError(f"scored answer artifact missing samples.jsonl: {scored_answer_artifact}")
    cost_usd = _validated_cost_usd(cost_evidence, scored_manifest)

    retrieval = _normalize_retrieval_metrics(retrieval_source)
    retrieval_config = _retrieval_candidate_config(retrieval_source, retrieval)
    per_format = retrieval_source.get("perFormat")
    if not isinstance(per_format, Mapping) or not per_format:
        raise ValueError("retrieval metrics must include non-empty perFormat evidence")

    config = dict(scored_manifest.get("config") or {})
    split_hashes = config.get("inputSplitHashes") or config.get("splitHashes") or {}
    if not isinstance(split_hashes, Mapping) or not split_hashes:
        raise ValueError("scored answer artifact must preserve input split hashes")
    dataset_hash = str(scored_manifest.get("datasetHash") or "")
    if not dataset_hash:
        raise ValueError("scored answer artifact must preserve datasetHash")
    answer_config = dict(answer_manifest.get("config") or {})
    if (
        config.get("inputRunId") != answer_manifest.get("runId")
        or config.get("inputConfigFingerprint") != answer_manifest.get("configFingerprint")
    ):
        raise ValueError("scored answer artifact does not identify the supplied answer artifact")
    if answer_manifest.get("datasetHash") != dataset_hash:
        raise ValueError("answer and scored answer artifacts use different dataset hashes")
    if retrieval_source.get("datasetHash") != dataset_hash:
        raise ValueError("retrieval metrics and scored answers use different dataset hashes")
    rows = _read_jsonl(samples_path)
    row_splits = {str(row.get("split") or "") for row in rows}
    retrieval_splits = {str(split) for split in retrieval_source.get("splits") or []}
    if not row_splits or retrieval_splits != row_splits:
        raise ValueError("retrieval metrics splits must exactly match scored answer row splits")
    retrieval_split_hashes = retrieval_source.get("splitHashes") or {}
    if any(retrieval_split_hashes.get(split) != split_hashes.get(split) for split in row_splits):
        raise ValueError("retrieval metrics and scored answers use different split hashes")
    _validate_answer_retrieval_provenance(rows, answer_config, retrieval, retrieval_config)

    gate_policy = {
        "baselineContextRecall": BASELINE_CONTEXT_RECALL,
        "baselinePhraseRecall": BASELINE_PHRASE_RECALL,
        "primaryRecallTarget": GATE_PRIMARY_RECALL_TARGET,
        "primaryRecallDelta": GATE_PRIMARY_RECALL_DELTA,
        "perFormatRegressionTolerance": GATE_PER_FORMAT_REGRESSION,
        "minCitationSupport": GATE_CITATION_SUPPORT,
        "minFullRagFaithfulness": GATE_FULL_RAG_FAITHFULNESS,
        "minNegativeControlDegradation": GATE_NEGATIVE_CONTROL_DEGRADATION,
        "maxLatencyP95Ms": max_latency_p95_ms,
        "maxCostUsd": max_cost_usd,
    }
    candidate_config = {
        "retrieval": retrieval_config,
        "answer": _stable_config(
            answer_config,
            excluded={"datasetHash", "maxSamples", "sourceArtifact", "splitHashes", "splits"},
        ),
        "ragas": _stable_config(
            config,
            excluded={"batchSize", "experimentName", "inputConfigFingerprint", "inputRunId", "inputSplitHashes", "inputSuite", "maxSamples"},
        ),
        "gatePolicy": gate_policy,
    }
    candidate_config_fingerprint = config_fingerprint(candidate_config)

    metrics = {
        "status": scored_metrics.get("status"),
        "retrieval": retrieval,
        "perFormat": dict(per_format),
        "ragas": scored_metrics.get("ragas") or {},
        "costUsd": cost_usd,
        "gatePolicy": gate_policy,
        "limits": {
            "maxLatencyP95Ms": max_latency_p95_ms,
            "maxCostUsd": max_cost_usd,
        },
    }
    manifest = {
        "runId": run_id,
        "suite": "doc-ingestion-rag-effectiveness",
        "mode": "evidence-bundle",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "datasetId": scored_manifest.get("datasetId"),
        "datasetHash": dataset_hash,
        "configFingerprint": candidate_config_fingerprint,
        "config": config | {
            "candidateConfig": candidate_config,
            "inputSplitHashes": dict(split_hashes),
        },
        "sourceArtifacts": {
            "retrievalMetrics": str(retrieval_metrics_path),
            "answers": str(answer_artifact),
            "scoredAnswers": str(scored_answer_artifact),
            "costEvidence": str(cost_evidence_path),
        },
        "sourceFingerprints": {
            "answer": answer_manifest.get("configFingerprint"),
            "scoredAnswer": scored_manifest.get("configFingerprint"),
        },
    }

    resolved_root = output_root.resolve()
    run_dir = (resolved_root / run_id).resolve()
    if not run_dir.is_relative_to(resolved_root):
        raise ValueError(f"run directory escapes output root: {run_id}")
    if run_dir.exists():
        raise ValueError(f"evidence bundle root already exists: {run_dir}")
    run_dir.mkdir(parents=True)
    write_json_artifact(run_dir / "manifest.json", manifest)
    write_json_artifact(run_dir / "metrics.json", metrics)
    shutil.copy2(samples_path, run_dir / "samples.jsonl")
    failures_path = scored_answer_artifact / "failures.jsonl"
    if failures_path.exists():
        shutil.copy2(failures_path, run_dir / "failures.jsonl")
    else:
        (run_dir / "failures.jsonl").write_text("", encoding="utf-8")
    write_json_artifact(
        run_dir / "report.json",
        {
            "runId": run_id,
            "suite": "doc-ingestion-rag-effectiveness",
            "mode": "evidence-bundle",
            "status": metrics["status"],
            "datasetHash": dataset_hash,
            "configFingerprint": manifest["configFingerprint"],
        },
    )
    return run_dir


def _normalize_retrieval_metrics(metrics: Mapping[str, Any]) -> dict[str, float]:
    source = metrics.get("retrieval") if isinstance(metrics.get("retrieval"), Mapping) else metrics
    keys = (
        "hitAtK",
        "contextRecallAtK",
        "mrr",
        "preRerankMrr",
        "phraseRecall",
        "topK",
        "candidateK",
        "rerankerMaxCandidates",
        "rrfK",
    )
    normalized = {
        key: float(source[key])
        for key in keys
        if isinstance(source.get(key), (int, float)) and not isinstance(source.get(key), bool)
    }
    missing = sorted(set(keys) - set(normalized))
    if missing:
        raise ValueError(f"retrieval metrics missing selection evidence: {missing}")
    if normalized["rerankerMaxCandidates"] < normalized["candidateK"]:
        raise ValueError("retrieval metrics do not prove the full candidate pool was reranked")
    return normalized


def _validated_cost_usd(cost_evidence: Mapping[str, Any], scored_manifest: Mapping[str, Any]) -> float:
    value = cost_evidence.get("costUsd")
    if not isinstance(value, (int, float)) or isinstance(value, bool) or value < 0:
        raise ValueError("cost evidence must include non-negative numeric costUsd")
    if (
        cost_evidence.get("sourceRunId") != scored_manifest.get("runId")
        or cost_evidence.get("sourceConfigFingerprint") != scored_manifest.get("configFingerprint")
    ):
        raise ValueError("cost evidence does not identify the supplied scored answer artifact")
    if not str(cost_evidence.get("method") or "").strip():
        raise ValueError("cost evidence must record its measurement method")
    return float(value)


def _retrieval_candidate_config(metrics: Mapping[str, Any], retrieval: Mapping[str, float]) -> dict[str, Any]:
    config = metrics.get("retrievalConfig")
    if not isinstance(config, Mapping) or not config:
        raise ValueError("retrieval metrics must include a stable retrievalConfig snapshot")
    required = {
        "topK",
        "candidateK",
        "rrfK",
        "embeddingProvider",
        "embeddingModel",
        "vectorProvider",
        "rerankerProvider",
        "rerankerModel",
        "rerankerMaxCandidates",
        "rerankerMaxChunkChars",
        "rerankerConfidenceFilterEnabled",
        "rerankerScoreThreshold",
    }
    missing = sorted(required - set(config))
    if missing:
        raise ValueError(f"retrievalConfig missing candidate configuration: {missing}")
    for key in ("topK", "candidateK", "rrfK", "rerankerMaxCandidates"):
        if not _same_number(config.get(key), retrieval.get(key)):
            raise ValueError(f"retrievalConfig.{key} does not match retrieval metrics")
    return dict(config)


def _same_number(left: Any, right: Any) -> bool:
    return (
        isinstance(left, (int, float))
        and not isinstance(left, bool)
        and isinstance(right, (int, float))
        and not isinstance(right, bool)
        and float(left) == float(right)
    )


def _validate_answer_retrieval_provenance(
    rows: list[Mapping[str, Any]],
    answer_config: Mapping[str, Any],
    retrieval: Mapping[str, float],
    retrieval_config: Mapping[str, Any],
) -> None:
    if not _same_number(answer_config.get("finalTopK"), retrieval.get("topK")):
        raise ValueError("answer finalTopK does not match retrieval metrics topK")
    expected_numbers = {
        "candidateK": retrieval["candidateK"],
        "finalTopK": retrieval["topK"],
        "rrfK": retrieval["rrfK"],
        "rerankerMaxCandidates": retrieval["rerankerMaxCandidates"],
    }
    expected_strings = {
        "rerankerProvider": retrieval_config["rerankerProvider"],
        "rerankerModel": retrieval_config["rerankerModel"],
        "candidatePath": "KnowledgeBaseSimilaritySearcher.searchRankedCandidateHitsByKnowledgeBaseIds",
        "finalPath": "KnowledgeBaseSimilaritySearcher.searchByKnowledgeBaseIds",
        "primaryMetricsSource": "post-rerank-final-hits",
    }
    required_modes = {"full-rag", "reranker-off", "reranker-on"}
    found_modes: set[str] = set()
    for row in rows:
        metadata = row.get("metadata") or {}
        control = metadata.get("controlRun") or {}
        mode = str(control.get("mode") or "") if isinstance(control, Mapping) else ""
        if mode not in required_modes:
            continue
        found_modes.add(mode)
        provenance = metadata.get("retrievalProvenance")
        if not isinstance(provenance, Mapping):
            raise ValueError(f"{mode} answer row missing retrievalProvenance")
        for key, expected in expected_numbers.items():
            if not _same_number(provenance.get(key), expected):
                raise ValueError(f"{mode} answer retrievalProvenance.{key} does not match retrieval metrics")
        for key, expected in expected_strings.items():
            if provenance.get(key) != expected:
                raise ValueError(f"{mode} answer retrievalProvenance.{key} does not match retrieval configuration")
    if found_modes != required_modes:
        raise ValueError("scored answers must include full-rag, reranker-off, and reranker-on retrieval provenance")


def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(path)
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _stable_config(config: Mapping[str, Any], *, excluded: set[str]) -> dict[str, Any]:
    return {key: value for key, value in config.items() if key not in excluded}
