"""RAG retrieval tuning replay over real-export candidate rows."""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.deterministic_metrics import hit_at_k, ndcg_at_k, precision_at_k, recall_at_k, reciprocal_rank
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import build_manifest, build_report, write_json_artifact, write_run_artifacts

DEFAULT_RAG_RETRIEVAL_DATASET_ID = "beir-scifact-rag-v1"
ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")


@dataclass(frozen=True)
class RagRetrievalConfig:
    run_id: str
    mode: str = "rag-retrieval-tuning"
    dataset_id: str = DEFAULT_RAG_RETRIEVAL_DATASET_ID
    top_k: int = 3
    candidate_k: int = 12
    rrf_k: int = 60
    max_samples: int | None = None
    splits: tuple[str, ...] = ()
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.top_k <= 0:
            raise ValueError("top_k must be positive")
        if self.candidate_k <= 0:
            raise ValueError("candidate_k must be positive")
        if self.rrf_k <= 0:
            raise ValueError("rrf_k must be positive")
        if self.top_k > self.candidate_k:
            raise ValueError("top_k must not exceed candidate_k")
        if self.max_samples is not None and self.max_samples <= 0:
            raise ValueError("max_samples must be positive when provided")

    def as_dict(self) -> dict[str, Any]:
        return {
            "datasetId": self.dataset_id,
            "topK": self.top_k,
            "candidateK": self.candidate_k,
            "rrfK": self.rrf_k,
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
            "replaySource": "real-export-candidate-contexts",
        }


def run_rag_retrieval(*, dataset_root: Path, output_root: Path, config: RagRetrievalConfig) -> Path:
    dataset_manifest = _read_json(dataset_root / "manifests" / "datasets" / f"{config.dataset_id}.json")
    rows = _select_rows(_read_jsonl(dataset_root / dataset_manifest["localPath"]), config)
    provenance = dict(dataset_manifest.get("provenance") or {})
    config_dict = config.as_dict() | {
        "datasetManifestHash": dataset_manifest["datasetHash"],
        "recordSchema": dataset_manifest["recordSchema"],
        "provenanceAvailable": bool(provenance),
    }
    fingerprint = config_fingerprint(config_dict)

    evaluated = [_evaluate_row(row, config) for row in rows]
    samples = [item["sample"] for item in evaluated]
    failures = [failure for item in evaluated for failure in item["failures"]]
    metric_values = _aggregate_metrics(evaluated, config)
    status = "warn" if failures else "pass"

    manifest = build_manifest(
        run_id=config.run_id,
        suite="rag-retrieval",
        mode=config.mode,
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=dataset_manifest["datasetId"],
        dataset_hash=dataset_manifest["datasetHash"],
        config=config_dict,
        config_fingerprint=fingerprint,
        models=provenance,
        artifact_files=ARTIFACT_FILES,
    )
    metrics = {
        "status": status,
        "retrieval": {
            "hitAtK": metric_values["ragRetrieval.hitAtK"],
            "recallAtK": metric_values["ragRetrieval.recallAtK"],
            "precisionAtK": metric_values["ragRetrieval.precisionAtK"],
            "mrr": metric_values["ragRetrieval.mrr"],
            "ndcgAtK": metric_values["ragRetrieval.ndcgAtK"],
            "sourceCoverage": metric_values["ragRetrieval.sourceCoverage"],
            "topK": config.top_k,
            "candidateK": config.candidate_k,
            "rrfK": config.rrf_k,
        },
        "merged": metric_values,
    }
    report = build_report(
        run_id=config.run_id,
        suite="rag-retrieval",
        mode=config.mode,
        status=status,
        dataset_id=dataset_manifest["datasetId"],
        dataset_hash=dataset_manifest["datasetHash"],
        config_fingerprint=fingerprint,
        metrics=metric_values,
        threshold_results=[],
    )
    run_dir = write_run_artifacts(output_root, manifest, metrics, samples, failures)
    write_json_artifact(run_dir / "report.json", report)
    return run_dir


def _select_rows(rows: Sequence[Mapping[str, Any]], config: RagRetrievalConfig) -> list[Mapping[str, Any]]:
    selected = [row for row in rows if not config.splits or str(row["split"]) in config.splits]
    if config.max_samples is not None:
        selected = selected[: config.max_samples]
    if not selected:
        raise ValueError("rag-retrieval selection produced no rows")
    return selected


def _evaluate_row(row: Mapping[str, Any], config: RagRetrievalConfig) -> dict[str, Any]:
    candidates = _candidate_contexts(row)
    ranked_contexts = _rank_contexts(candidates, config)
    retrieved_ids = [str(context["id"]) for context in ranked_contexts]
    relevant_ids = [str(context_id) for context_id in row.get("referenceContextIds", [])]

    metrics = {
        "hitAtK": hit_at_k(retrieved_ids, relevant_ids, config.top_k),
        "recallAtK": recall_at_k(retrieved_ids, relevant_ids, config.top_k),
        "precisionAtK": precision_at_k(retrieved_ids, relevant_ids, config.top_k),
        "mrr": reciprocal_rank(retrieved_ids, relevant_ids),
        "ndcgAtK": ndcg_at_k(retrieved_ids, relevant_ids, config.top_k),
    }
    source_metadata = dict(row.get("metadata") or {})
    source_metadata.pop("candidateContexts", None)
    source_metadata.pop("moduleOutputs", None)
    source_metadata.pop("retrievedContexts", None)
    sample = {
        "sampleId": row["sampleId"],
        "datasetId": row["datasetId"],
        "split": row["split"],
        "userInput": row["userInput"],
        "retrievedContexts": ranked_contexts[: config.top_k],
        "referenceContextIds": relevant_ids,
        "metadata": {
            "sourceGroupId": row.get("sourceGroupId"),
            "sourceMetadata": source_metadata,
            "candidateCount": len(candidates),
            "candidateK": config.candidate_k,
            "topK": config.top_k,
            "rrfK": config.rrf_k,
            **metrics,
        },
    }
    failures = []
    if metrics["hitAtK"] < 1.0:
        failures.append(
            {
                "sampleId": row["sampleId"],
                "metric": "ragRetrieval.hitAtK",
                "errorCategory": "missing_relevant_context",
                "referenceContextIds": relevant_ids,
                "topKContexts": ranked_contexts[: config.top_k],
            }
        )
    return {"sample": sample, "failures": failures, "metrics": metrics}


def _candidate_contexts(row: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    metadata = row.get("metadata") or {}
    module_outputs = row.get("moduleOutputs") or metadata.get("moduleOutputs") or {}
    sources = (
        metadata.get("candidateContexts"),
        module_outputs.get("candidateContexts") if isinstance(module_outputs, Mapping) else None,
        row.get("candidateContexts"),
    )
    for value in sources:
        if isinstance(value, list) and value:
            return [_normalize_context(context, index) for index, context in enumerate(value)]
    raise ValueError(f"rag-retrieval row has no real-export candidate contexts: {row.get('sampleId')}")


def _normalize_context(context: Mapping[str, Any], index: int) -> Mapping[str, Any]:
    context_id = context.get("id") or context.get("documentId") or context.get("sourceId")
    if not context_id:
        raise ValueError("rag-retrieval candidate context is missing id/documentId/sourceId")
    normalized = {
        "id": str(context_id),
        "text": str(context.get("text") or context.get("content") or ""),
        "sourceId": context.get("sourceId"),
        "score": _optional_float(context.get("score")),
        "denseRank": _optional_int(_rank_value(context, "denseRank")),
        "bm25Rank": _optional_int(_rank_value(context, "bm25Rank")),
        "candidateRank": _optional_int(_rank_value(context, "candidateRank")),
        "originalRank": _optional_int(context.get("rank")) or index + 1,
    }
    if (
        normalized["denseRank"] is None
        and normalized["bm25Rank"] is None
    ):
        raise ValueError("rag-retrieval candidate context is missing denseRank/bm25Rank rank signal")
    return normalized


def _rank_contexts(contexts: Sequence[Mapping[str, Any]], config: RagRetrievalConfig) -> list[Mapping[str, Any]]:
    pool = list(contexts[: config.candidate_k])
    scored = []
    for index, context in enumerate(pool):
        scored.append((_fusion_score(context, config.rrf_k), index, context))
    ordered = sorted(scored, key=lambda item: (-item[0], item[1], str(item[2]["id"])))
    return [
        {
            "id": context["id"],
            "text": context["text"],
            "sourceId": context["sourceId"],
            "score": score,
        }
        for score, _, context in ordered
    ]


def _fusion_score(context: Mapping[str, Any], rrf_k: int) -> float:
    ranks = [context.get("denseRank"), context.get("bm25Rank")]
    present_ranks = [int(rank) for rank in ranks if isinstance(rank, int) and rank > 0]
    if present_ranks:
        return sum(1.0 / (rrf_k + rank) for rank in present_ranks)
    raise ValueError("rag-retrieval candidate context is missing denseRank/bm25Rank rank signal")


def _aggregate_metrics(evaluated: Sequence[Mapping[str, Any]], config: RagRetrievalConfig) -> dict[str, float | int | None]:
    retrieved_relevant = {
        context["id"]
        for item in evaluated
        for context in item["sample"]["retrievedContexts"]
        if context["id"] in set(item["sample"]["referenceContextIds"])
    }
    expected_relevant = {
        context_id
        for item in evaluated
        for context_id in item["sample"]["referenceContextIds"]
    }
    latency_values = [
        float((item["sample"].get("metadata") or {}).get("sourceMetadata", {}).get("latencyMs"))
        for item in evaluated
        if isinstance((item["sample"].get("metadata") or {}).get("sourceMetadata", {}).get("latencyMs"), (int, float))
    ]
    values = {
        "ragRetrieval.sampleCount": len(evaluated),
        "ragRetrieval.hitAtK": _average(evaluated, "hitAtK"),
        "ragRetrieval.recallAtK": _average(evaluated, "recallAtK"),
        "ragRetrieval.precisionAtK": _average(evaluated, "precisionAtK"),
        "ragRetrieval.mrr": _average(evaluated, "mrr"),
        "ragRetrieval.ndcgAtK": _average(evaluated, "ndcgAtK"),
        "ragRetrieval.sourceCoverage": len(retrieved_relevant) / len(expected_relevant) if expected_relevant else 0.0,
        "ragRetrieval.topK": config.top_k,
        "ragRetrieval.candidateK": config.candidate_k,
        "ragRetrieval.rrfK": config.rrf_k,
        "p95LatencyMs": _percentile95(latency_values) if latency_values else None,
    }
    return values


def _average(evaluated: Sequence[Mapping[str, Any]], metric: str) -> float:
    values = [float(item["metrics"][metric]) for item in evaluated]
    return sum(values) / len(values) if values else 0.0


def _percentile95(values: Sequence[float]) -> float:
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((len(ordered) * 0.95) + 0.999999) - 1))
    return ordered[index]


def _rank_value(context: Mapping[str, Any], key: str) -> Any:
    rank_signals = context.get("rankSignals")
    if isinstance(rank_signals, Mapping) and key in rank_signals:
        return rank_signals[key]
    return context.get(key)


def _optional_float(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _optional_int(value: Any) -> int | None:
    return int(value) if isinstance(value, int) and not isinstance(value, bool) else None


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
