"""Doc-ingestion retrieval metrics over real-export dataset records.

Reads the Phase 3-compatible dataset root exported by
``ProductionDocumentIngestionEvalTest``, validates against the schema,
computes chunk-level retrieval metrics (hit@K, contextRecall@K, MRR,
phraseRecall, per-format breakdowns), and writes standardized run artifacts.
"""

from __future__ import annotations

import json
from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.deterministic_metrics import hit_at_k, recall_at_k, reciprocal_rank
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import build_manifest, build_report, write_json_artifact, write_run_artifacts

ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")


@dataclass(frozen=True)
class DocIngestionConfig:
    run_id: str
    mode: str = "doc-ingestion-retrieval"
    dataset_id: str = "doc-ingestion-retrieval-v1"
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

    def as_dict(self) -> dict[str, Any]:
        return {
            "datasetId": self.dataset_id,
            "topK": self.top_k,
            "candidateK": self.candidate_k,
            "rrfK": self.rrf_k,
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
            "replaySource": "real-export-doc-ingestion",
        }


def run_doc_ingestion_retrieval(
    *, dataset_root: Path, output_root: Path, config: DocIngestionConfig
) -> Path:
    """Run doc-ingestion retrieval metrics on exported JSONL and produce run artifacts."""
    rows = _load_rows(dataset_root, config)
    if not rows:
        raise ValueError("doc-ingestion-retrieval selection produced no rows")

    config_dict = config.as_dict()
    fingerprint = config_fingerprint(config_dict)

    evaluated = [_evaluate_row(row, config) for row in rows]
    samples = [item["sample"] for item in evaluated]
    failures = [failure for item in evaluated for failure in item["failures"]]
    metric_values = _aggregate_metrics(evaluated, config)
    per_format = _per_format_metrics(evaluated, config)
    status = "warn" if failures else "pass"

    manifest = build_manifest(
        run_id=config.run_id,
        suite="doc-ingestion-retrieval",
        mode=config.mode,
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=config.dataset_id,
        dataset_hash=fingerprint[:16],
        config=config_dict,
        config_fingerprint=fingerprint,
        models={},
        artifact_files=ARTIFACT_FILES,
    )
    metrics = {
        "status": status,
        "retrieval": {
            "hitAtK": metric_values["docIngestion.hitAtK"],
            "contextRecallAtK": metric_values["docIngestion.contextRecallAtK"],
            "mrr": metric_values["docIngestion.mrr"],
            "phraseRecall": metric_values["docIngestion.phraseRecall"],
            "topK": config.top_k,
            "candidateK": config.candidate_k,
            "rrfK": config.rrf_k,
        },
        "perFormat": per_format,
        "merged": metric_values,
    }
    report = build_report(
        run_id=config.run_id,
        suite="doc-ingestion-retrieval",
        mode=config.mode,
        status=status,
        dataset_id=config.dataset_id,
        dataset_hash=fingerprint[:16],
        config_fingerprint=fingerprint,
        metrics=metric_values,
        threshold_results=[],
    )
    run_dir = write_run_artifacts(output_root, manifest, metrics, samples, failures)
    write_json_artifact(run_dir / "report.json", report)
    return run_dir


# ── Internal helpers ────────────────────────────────────────────────────────


def _load_rows(dataset_root: Path, config: DocIngestionConfig) -> list[Mapping[str, Any]]:
    """Load rows via the Phase 3 dataset manifest."""
    manifest_path = dataset_root / "manifests" / "datasets" / f"{config.dataset_id}.json"
    if not manifest_path.exists():
        raise ValueError(f"dataset manifest not found: {manifest_path}")
    manifest = _read_json(manifest_path)
    local_path = manifest.get("localPath")
    if not local_path:
        raise ValueError(f"dataset manifest missing localPath: {manifest_path}")
    jsonl_path = dataset_root / local_path
    if not jsonl_path.exists():
        raise ValueError(f"dataset JSONL not found: {jsonl_path}")

    rows: list[Mapping[str, Any]] = _read_jsonl(jsonl_path)

    if config.splits:
        rows = [r for r in rows if r.get("split") in config.splits]

    if config.max_samples is not None:
        rows = rows[: config.max_samples]
    return rows


def _evaluate_row(row: Mapping[str, Any], config: DocIngestionConfig) -> dict[str, Any]:
    """Compute chunk-level retrieval metrics for a single query row."""
    metadata = dict(row.get("metadata") or {})
    candidates = _candidate_contexts(metadata)
    ranked = _rank_contexts(candidates, config)
    reference_context_ids = list(row.get("referenceContextIds", []))
    reference_chunk_id = reference_context_ids[0] if reference_context_ids else ""
    reference_content = str(metadata.get("referenceContent", ""))
    fmt = str(metadata.get("format", "unknown"))

    # Build chunk ID lists for chunk-level metric computation
    retrieved_chunk_ids = [
        str(ctx.get("chunkId", "") or ctx.get("id", "")) for ctx in ranked[: config.candidate_k]
    ]
    retrieved_chunk_text = [
        str(ctx.get("content", "") or ctx.get("text", "")) for ctx in ranked[: config.top_k]
    ]

    # hit@K: does any top-K retrieved context match the reference chunk ID?
    chunk_hit = hit_at_k(retrieved_chunk_ids, [reference_chunk_id], config.top_k)
    # contextRecall: same as hit for single-reference rows
    context_recall = recall_at_k(retrieved_chunk_ids, [reference_chunk_id], config.top_k)
    # MRR: reciprocal rank of the reference chunk
    mrr = reciprocal_rank(retrieved_chunk_ids, [reference_chunk_id])
    # phraseRecall: fraction of meaningful reference words present in top-K retrieved text
    phrase_recall = _phrase_recall(reference_content, retrieved_chunk_text)

    metrics = {
        "hitAtK": chunk_hit,
        "contextRecallAtK": context_recall,
        "mrr": mrr,
        "phraseRecall": phrase_recall,
    }

    sample = {
        "sampleId": row.get("sampleId", ""),
        "userInput": row.get("userInput", ""),
        "referenceContextIds": reference_context_ids,
        "retrievedContexts": ranked[: config.top_k],
        "metadata": {
            "sourceGroupId": row.get("sourceGroupId"),
            "format": fmt,
            "generationMethod": metadata.get("generationMethod"),
            "sourceMetadata": {
                "domain": fmt,
                "sourceUrl": metadata.get("sourceUrl"),
                "mqEnabled": metadata.get("mqEnabled"),
                "mineruEnabled": metadata.get("mineruEnabled"),
            },
            "docIngestion": metrics,
            **metrics,
        },
    }

    failures = []
    if chunk_hit < 1.0:
        failures.append(
            {
                "sampleId": row.get("sampleId", ""),
                "metric": "docIngestion.hitAtK",
                "errorCategory": "missing_reference_chunk",
                "referenceChunkId": reference_chunk_id,
                "retrievedChunkIds": retrieved_chunk_ids[: config.top_k],
            }
        )

    return {"sample": sample, "failures": failures, "metrics": metrics, "format": fmt}


def _candidate_contexts(metadata: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    """Return replayable candidate contexts, falling back to legacy top-K rows.

    Accepted-size Phase 10a exports write ``candidateContexts`` with dense/BM25
    rank signals. Older smoke artifacts only have ``retrievedContexts``; those
    remain evaluable but are not useful for real tuning beyond topK slicing.
    """
    value = metadata.get("candidateContexts")
    if isinstance(value, list) and value:
        return [_normalize_context(ctx, index, require_rank_signals=True) for index, ctx in enumerate(value)]
    legacy = metadata.get("retrievedContexts")
    if isinstance(legacy, list) and legacy:
        return [_normalize_context(ctx, index, require_rank_signals=False) for index, ctx in enumerate(legacy)]
    return []


def _normalize_context(context: Mapping[str, Any], index: int, *, require_rank_signals: bool) -> Mapping[str, Any]:
    context_id = context.get("id") or context.get("chunkId") or context.get("documentId")
    if not context_id:
        raise ValueError("doc-ingestion candidate context is missing id/chunkId/documentId")
    rank_signals = context.get("rankSignals") if isinstance(context.get("rankSignals"), Mapping) else {}
    dense_rank = _optional_int(rank_signals.get("denseRank"))
    bm25_rank = _optional_int(rank_signals.get("bm25Rank"))
    if require_rank_signals and dense_rank is None and bm25_rank is None:
        raise ValueError("doc-ingestion candidate context is missing denseRank/bm25Rank rank signal")
    return {
        "id": str(context_id),
        "chunkId": str(context.get("chunkId") or context_id),
        "documentId": context.get("documentId"),
        "content": str(context.get("content") or context.get("text") or ""),
        "score": _optional_float(context.get("score")),
        "denseRank": dense_rank,
        "bm25Rank": bm25_rank,
        "candidateRank": _optional_int(rank_signals.get("candidateRank")) or index + 1,
        "originalRank": index + 1,
    }


def _rank_contexts(contexts: Sequence[Mapping[str, Any]], config: DocIngestionConfig) -> list[Mapping[str, Any]]:
    pool = list(contexts[: config.candidate_k])
    scored: list[tuple[float, int, Mapping[str, Any]]] = []
    for index, context in enumerate(pool):
        scored.append((_fusion_score(context, config.rrf_k), index, context))
    ordered = sorted(scored, key=lambda item: (-item[0], item[1], str(item[2]["id"])))
    return [
        {
            "id": context["id"],
            "chunkId": context["chunkId"],
            "documentId": context["documentId"],
            "content": context["content"],
            "score": score,
        }
        for score, _, context in ordered
    ]


def _fusion_score(context: Mapping[str, Any], rrf_k: int) -> float:
    ranks = [context.get("denseRank"), context.get("bm25Rank")]
    present_ranks = [int(rank) for rank in ranks if isinstance(rank, int) and rank > 0]
    if present_ranks:
        return sum(1.0 / (rrf_k + rank) for rank in present_ranks)
    score = context.get("score")
    if isinstance(score, (int, float)) and not isinstance(score, bool):
        return float(score)
    return 1.0 / (rrf_k + int(context.get("candidateRank", 1)))


def _aggregate_metrics(
    evaluated: Sequence[Mapping[str, Any]], config: DocIngestionConfig
) -> dict[str, float]:
    """Average metrics across all evaluated rows."""
    if not evaluated:
        return {
            "docIngestion.hitAtK": 0.0,
            "docIngestion.contextRecallAtK": 0.0,
            "docIngestion.mrr": 0.0,
            "docIngestion.phraseRecall": 0.0,
            "docIngestion.queryCount": 0,
        }

    keys = ["hitAtK", "contextRecallAtK", "mrr", "phraseRecall"]
    sums: dict[str, float] = {k: 0.0 for k in keys}
    for item in evaluated:
        for key in keys:
            sums[key] += item["metrics"].get(key, 0.0)

    n = len(evaluated)
    return {
        "docIngestion.hitAtK": sums["hitAtK"] / n,
        "docIngestion.contextRecallAtK": sums["contextRecallAtK"] / n,
        "docIngestion.mrr": sums["mrr"] / n,
        "docIngestion.phraseRecall": sums["phraseRecall"] / n,
        "docIngestion.queryCount": n,
    }


def _per_format_metrics(
    evaluated: Sequence[Mapping[str, Any]], config: DocIngestionConfig
) -> dict[str, dict[str, float]]:
    """Break down metrics by file format family."""
    by_format: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for item in evaluated:
        by_format[item["format"]].append(item)

    result: dict[str, dict[str, float]] = {}
    for fmt, items in sorted(by_format.items()):
        agg = _aggregate_metrics(items, config)
        result[fmt] = {
            "hitAtK": agg["docIngestion.hitAtK"],
            "contextRecallAtK": agg["docIngestion.contextRecallAtK"],
            "mrr": agg["docIngestion.mrr"],
            "phraseRecall": agg["docIngestion.phraseRecall"],
            "queryCount": float(agg["docIngestion.queryCount"]),
        }
    return result


def _phrase_recall(reference: str, retrieved_texts: list[str]) -> float:
    """Fraction of non-trivial reference words present in any retrieved text."""
    if not reference:
        return 0.0
    ref_words = set(reference.lower().split())
    if not ref_words:
        return 0.0
    # Filter out very short/common words
    meaningful = {w for w in ref_words if len(w) >= 4}
    if not meaningful:
        return 1.0
    combined = " ".join(retrieved_texts).lower()
    found = sum(1 for w in meaningful if w in combined)
    return found / len(meaningful)


def _optional_float(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def _optional_int(value: Any) -> int | None:
    return int(value) if isinstance(value, int) and not isinstance(value, bool) else None


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[Mapping[str, Any]]:
    rows: list[Mapping[str, Any]] = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows
