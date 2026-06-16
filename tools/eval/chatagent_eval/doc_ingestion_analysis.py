"""Failure analysis for Phase 10a production document-ingestion retrieval."""

from __future__ import annotations

import json
from collections import Counter, defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.doc_ingestion_runner import (
    DocIngestionConfig,
    _candidate_contexts,
    _load_rows,
    _phrase_recall,
    _rank_contexts,
)


@dataclass(frozen=True)
class DocIngestionAnalysisConfig:
    run_id: str
    dataset_id: str = "doc-ingestion-retrieval-v1"
    top_k: int = 8
    candidate_k: int = 12
    rrf_k: int = 60
    splits: tuple[str, ...] = ()
    failed_audit_target: int = 50
    success_audit_target: int = 25

    def __post_init__(self) -> None:
        if self.top_k <= 0:
            raise ValueError("top_k must be positive")
        if self.candidate_k <= 0:
            raise ValueError("candidate_k must be positive")
        if self.top_k > self.candidate_k:
            raise ValueError("top_k must not exceed candidate_k")
        if self.failed_audit_target < 0:
            raise ValueError("failed_audit_target must be non-negative")
        if self.success_audit_target < 0:
            raise ValueError("success_audit_target must be non-negative")


def run_doc_ingestion_failure_analysis(
    *, dataset_root: Path, output_root: Path, config: DocIngestionAnalysisConfig
) -> Path:
    rows = _load_rows(
        dataset_root,
        DocIngestionConfig(
            run_id=config.run_id,
            dataset_id=config.dataset_id,
            top_k=config.top_k,
            candidate_k=config.candidate_k,
            rrf_k=config.rrf_k,
            splits=config.splits,
        ),
    )
    if not rows:
        raise ValueError("doc-ingestion analysis selection produced no rows")

    analyzed = [_analyze_row(row, config) for row in rows]
    report = _build_report(analyzed, config)
    audit_rows = _audit_sample(analyzed, config)
    repair_rows = _repair_candidates(analyzed)
    trainable_repairs = [row for row in repair_rows if row.get("repairAllowedForTuning")]
    run_dir = output_root / config.run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    _write_json(run_dir / "analysis.json", report)
    _write_jsonl(run_dir / "audit-sample.jsonl", audit_rows)
    _write_jsonl(run_dir / "repair-candidates.jsonl", repair_rows)
    _write_jsonl(run_dir / "repair-candidates-trainable.jsonl", trainable_repairs)
    _write_jsonl(run_dir / "repair-overlay-review-template.jsonl", _repair_overlay_template(trainable_repairs))
    (run_dir / "analysis.md").write_text(_markdown_report(report), encoding="utf-8")
    (run_dir / "audit-assist.md").write_text(
        _audit_assist_report(report, audit_rows, repair_rows, trainable_repairs), encoding="utf-8"
    )
    return run_dir


def _analyze_row(row: Mapping[str, Any], config: DocIngestionAnalysisConfig) -> dict[str, Any]:
    metadata = row.get("metadata") or {}
    candidates = list(_candidate_contexts(metadata))
    ranked = list(
        _rank_contexts(
            candidates,
            DocIngestionConfig(
                run_id=config.run_id,
                dataset_id=config.dataset_id,
                top_k=config.top_k,
                candidate_k=config.candidate_k,
                rrf_k=config.rrf_k,
            ),
        )
    )
    reference_ids = {str(value) for value in row.get("referenceContextIds", []) if value}
    top_contexts = ranked[: config.top_k]
    candidate_contexts = ranked[: config.candidate_k]
    top_ids = [str(context.get("chunkId") or context.get("id") or "") for context in top_contexts]
    candidate_ids = [str(context.get("chunkId") or context.get("id") or "") for context in candidate_contexts]
    top_hit = bool(reference_ids & set(top_ids))
    oracle_hit = bool(reference_ids & set(candidate_ids))
    reference_rank = _first_rank(candidate_ids, reference_ids)
    reference_doc_id = str(metadata.get("referenceDocId") or row.get("fileId") or "")
    same_doc_top = _has_same_doc(top_contexts, reference_doc_id, reference_ids)
    same_doc_candidate = _has_same_doc(candidate_contexts, reference_doc_id, reference_ids)
    retrieved_texts = [str(context.get("content") or context.get("text") or "") for context in top_contexts]
    phrase_recall = _phrase_recall(str(metadata.get("referenceContent") or ""), retrieved_texts)
    query = str(row.get("userInput") or "")
    format_name = str(metadata.get("format") or row.get("fileFormat") or "unknown")
    generation_method = str(metadata.get("generationMethod") or "unknown")
    signals = _signals(row, metadata, phrase_recall, same_doc_top, same_doc_candidate, bool(top_contexts))
    primary_category = _primary_category(
        top_hit=top_hit,
        oracle_hit=oracle_hit,
        phrase_recall=phrase_recall,
        same_doc_top=same_doc_top,
        same_doc_candidate=same_doc_candidate,
        signals=signals,
    )
    return {
        "sampleId": row.get("sampleId"),
        "split": row.get("split"),
        "format": format_name,
        "sourceGroupId": row.get("sourceGroupId"),
        "sourceGroup": metadata.get("sourceGroup"),
        "sourceUrl": metadata.get("sourceUrl") or row.get("sourceUrl"),
        "referenceDocId": reference_doc_id,
        "referenceDocFilename": metadata.get("referenceDocFilename"),
        "generationMethod": generation_method,
        "parser": metadata.get("parser"),
        "chunker": metadata.get("chunker"),
        "mineruSelected": bool((metadata.get("mineru") or {}).get("selected")),
        "mineruRoute": "mineru" if bool((metadata.get("mineru") or {}).get("selected")) else "standard",
        "mqConsumerCompleted": bool((metadata.get("mq") or {}).get("consumerCompleted")),
        "mqRoute": "mq-consumer" if bool((metadata.get("mq") or {}).get("consumerCompleted")) else "direct-or-unknown",
        "queryLength": len(query),
        "queryLengthBucket": _query_length_bucket(len(query)),
        "referenceLength": len(str(metadata.get("referenceContent") or "")),
        "topHit": top_hit,
        "oracleHit": oracle_hit,
        "referenceRank": reference_rank,
        "phraseRecall": phrase_recall,
        "sameDocTopK": same_doc_top,
        "sameDocCandidate": same_doc_candidate,
        "primaryFailureCategory": primary_category,
        "signals": signals,
        "tableNumericFlag": "table_or_numeric_format" in signals,
        "referenceChunkIds": sorted(reference_ids),
        "topChunkIds": top_ids,
        "candidateChunkIds": candidate_ids,
        "topContexts": [_context_preview(context, index + 1) for index, context in enumerate(top_contexts)],
        "candidateContexts": [
            _context_preview(context, index + 1) for index, context in enumerate(candidate_contexts)
        ],
        "userInputPreview": _preview(query),
        "referencePreview": _preview(str(metadata.get("referenceContent") or "")),
    }


def _signals(
    row: Mapping[str, Any],
    metadata: Mapping[str, Any],
    phrase_recall: float,
    same_doc_top: bool,
    same_doc_candidate: bool,
    has_top_contexts: bool,
) -> list[str]:
    query = str(row.get("userInput") or "")
    reference = str(metadata.get("referenceContent") or "")
    format_name = str(metadata.get("format") or row.get("fileFormat") or "")
    result: list[str] = []
    if len(query) > 300:
        result.append("long_query")
    if _looks_like_evidence_query(query, reference):
        result.append("evidence_as_query")
    if phrase_recall >= 0.75:
        result.append("high_phrase_recall")
    if phrase_recall >= 0.9 and has_top_contexts and not same_doc_top:
        result.append("ambiguous_duplicate_evidence")
    if same_doc_top:
        result.append("same_doc_in_topk")
    elif same_doc_candidate:
        result.append("same_doc_in_candidate_pool")
    if format_name in {"XLSX", "SEC_HTML"}:
        result.append("table_or_numeric_format")
    if bool((metadata.get("mineru") or {}).get("selected")):
        result.append("mineru_selected")
    return result


def _primary_category(
    *,
    top_hit: bool,
    oracle_hit: bool,
    phrase_recall: float,
    same_doc_top: bool,
    same_doc_candidate: bool,
    signals: Sequence[str],
) -> str:
    if top_hit:
        return "hit"
    if "ambiguous_duplicate_evidence" in signals:
        return "ambiguous_duplicate_evidence"
    if not oracle_hit:
        if phrase_recall >= 0.75 or same_doc_top:
            return "reference_or_chunk_boundary_suspect"
        return "candidate_pool_miss"
    if same_doc_top or same_doc_candidate:
        return "same_document_wrong_chunk"
    if "long_query" in signals or "evidence_as_query" in signals:
        return "query_quality_suspect"
    return "ranking_miss"


def _build_report(
    analyzed: Sequence[Mapping[str, Any]], config: DocIngestionAnalysisConfig
) -> dict[str, Any]:
    total = len(analyzed)
    top_hits = sum(1 for row in analyzed if row["topHit"])
    oracle_hits = sum(1 for row in analyzed if row["oracleHit"])
    categories = Counter(str(row["primaryFailureCategory"]) for row in analyzed if not row["topHit"])
    axis_ranking = _axis_ranking(analyzed)
    return {
        "runId": config.run_id,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "datasetId": config.dataset_id,
        "config": {
            "topK": config.top_k,
            "candidateK": config.candidate_k,
            "rrfK": config.rrf_k,
            "splits": list(config.splits),
        },
        "summary": {
            "rowCount": total,
            "topKHitRate": top_hits / total if total else 0.0,
            "oracleRecallAtCandidateK": oracle_hits / total if total else 0.0,
            "rankingGap": (oracle_hits - top_hits) / total if total else 0.0,
            "failureCount": total - top_hits,
        },
        "failureCategories": dict(sorted(categories.items())),
        "byFormat": _grouped_metrics(analyzed, "format"),
        "bySourceGroupId": _grouped_metrics(analyzed, "sourceGroupId"),
        "bySplit": _grouped_metrics(analyzed, "split"),
        "byGenerationMethod": _grouped_metrics(analyzed, "generationMethod"),
        "byParser": _grouped_metrics(analyzed, "parser"),
        "byChunker": _grouped_metrics(analyzed, "chunker"),
        "byQueryLengthBucket": _grouped_metrics(analyzed, "queryLengthBucket"),
        "byMineruRoute": _grouped_metrics(analyzed, "mineruRoute"),
        "byMqRoute": _grouped_metrics(analyzed, "mqRoute"),
        "byTableNumericFlag": _grouped_metrics(analyzed, "tableNumericFlag"),
        "axisRanking": axis_ranking,
        "recommendedFirstAxis": axis_ranking[0]["axis"] if axis_ranking else None,
    }


def _grouped_metrics(rows: Sequence[Mapping[str, Any]], key: str) -> dict[str, dict[str, Any]]:
    groups: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for row in rows:
        value = row.get(key)
        name = "unknown" if value is None or value == "" else str(value)
        groups[name].append(row)
    result: dict[str, dict[str, Any]] = {}
    for name, items in sorted(groups.items()):
        total = len(items)
        top_hits = sum(1 for row in items if row["topHit"])
        oracle_hits = sum(1 for row in items if row["oracleHit"])
        result[name] = {
            "rowCount": total,
            "topKHitRate": top_hits / total if total else 0.0,
            "oracleRecallAtCandidateK": oracle_hits / total if total else 0.0,
            "rankingGap": (oracle_hits - top_hits) / total if total else 0.0,
            "averagePhraseRecall": _mean(float(row["phraseRecall"]) for row in items),
            "averageQueryLength": _mean(float(row["queryLength"]) for row in items),
            "failureCategories": dict(
                sorted(Counter(str(row["primaryFailureCategory"]) for row in items if not row["topHit"]).items())
            ),
        }
    return result


def _axis_ranking(rows: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    cost_weight = {"low": 1.0, "medium": 1.4, "high": 2.0}
    risk_weight = {"low": 1.0, "medium": 1.3, "high": 1.8}
    axes = {
        "candidate_generation": {
            "predicate": lambda row: row["primaryFailureCategory"] == "candidate_pool_miss",
            "implementationCost": "medium",
            "regressionRisk": "medium",
            "note": "Reference chunk absent from candidate pool; tune candidate generation/search breadth first.",
        },
        "ranking_rrf_topk": {
            "predicate": lambda row: row["primaryFailureCategory"] == "ranking_miss",
            "implementationCost": "low",
            "regressionRisk": "medium",
            "note": "Reference chunk is in candidate pool but outside topK; tune ranking/RRF/topK.",
        },
        "chunk_boundary_reference_quality": {
            "predicate": lambda row: row["primaryFailureCategory"]
            in {"same_document_wrong_chunk", "reference_or_chunk_boundary_suspect"},
            "implementationCost": "low",
            "regressionRisk": "low",
            "note": "Same document or high phrase support without chunk hit; audit chunk boundaries and reference IDs.",
        },
        "query_generation": {
            "predicate": lambda row: not row["topHit"]
            and (
                "long_query" in row["signals"]
                or "evidence_as_query" in row["signals"]
                or row["primaryFailureCategory"] == "ambiguous_duplicate_evidence"
            ),
            "implementationCost": "medium",
            "regressionRisk": "medium",
            "note": "Evidence-like or duplicate-template queries need source/date/title disambiguation before tuning.",
        },
        "table_numeric_retrieval": {
            "predicate": lambda row: not row["topHit"] and "table_or_numeric_format" in row["signals"],
            "implementationCost": "high",
            "regressionRisk": "medium",
            "note": "Table/numeric formats are overrepresented in misses; improve headers, units, and table chunks.",
        },
    }
    holdout_total = max(1, sum(1 for row in rows if row.get("split") == "holdout"))
    ranked: list[dict[str, Any]] = []
    for axis, spec in axes.items():
        predicate = spec["predicate"]
        affected = [row for row in rows if predicate(row)]
        if not affected:
            continue
        holdout_affected = [row for row in affected if row.get("split") == "holdout"]
        cost = str(spec["implementationCost"])
        risk = str(spec["regressionRisk"])
        holdout_impact = len(holdout_affected) / holdout_total
        priority_score = holdout_impact / (cost_weight[cost] * risk_weight[risk])
        ranked.append(
            {
                "axis": axis,
                "affectedFailures": len(affected),
                "holdoutAffectedFailures": len(holdout_affected),
                "estimatedHoldoutRecallImpact": holdout_impact,
                "implementationCost": cost,
                "regressionRisk": risk,
                "priorityScore": priority_score,
                "note": spec["note"],
            }
        )
    return [
        item
        for item in sorted(
            ranked,
            key=lambda item: (-float(item["priorityScore"]), -int(item["holdoutAffectedFailures"]), item["axis"]),
        )
    ]


def _audit_sample(rows: Sequence[Mapping[str, Any]], config: DocIngestionAnalysisConfig) -> list[Mapping[str, Any]]:
    failed = [row for row in rows if not row["topHit"] and row.get("split") == "holdout"]
    passed = [row for row in rows if row["topHit"] and row.get("split") == "holdout"]
    selected = _stratified_take(failed, config.failed_audit_target) + _stratified_take(
        passed, config.success_audit_target
    )
    return [_audit_record(row) for row in selected]


def _repair_candidates(rows: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    candidates = [
        row
        for row in rows
        if not row["topHit"]
        and (
            row["primaryFailureCategory"] in {"same_document_wrong_chunk", "reference_or_chunk_boundary_suspect"}
            or float(row["phraseRecall"]) >= 0.9
        )
    ]
    return [_audit_record(row) for row in candidates]


def _repair_overlay_template(rows: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    template: list[Mapping[str, Any]] = []
    for row in rows:
        top_context = (row.get("topContexts") or [{}])[0]
        candidate_context = (row.get("candidateContexts") or [{}])[0]
        suggested_context = top_context if top_context.get("chunkId") else candidate_context
        template.append(
            {
                "sampleId": row["sampleId"],
                "decision": "reference_chunk_wrong",
                "newReferenceChunkId": suggested_context.get("chunkId") or "",
                "newReferenceContent": suggested_context.get("contentPreview") or "",
                "tuningAllowed": False,
                "reviewRequired": True,
                "reason": "",
                "reviewedBy": "",
                "sourceSplit": row["split"],
                "sourceFormat": row["format"],
                "sourcePrimaryFailureCategory": row["primaryFailureCategory"],
            }
        )
    return template


def _stratified_take(rows: Sequence[Mapping[str, Any]], target: int) -> list[Mapping[str, Any]]:
    if target <= 0:
        return []
    groups: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[str(row.get("format") or "unknown")].append(row)
    selected: list[Mapping[str, Any]] = []
    names = sorted(groups)
    while len(selected) < target and any(groups.values()):
        for name in names:
            if groups[name] and len(selected) < target:
                selected.append(groups[name].pop(0))
    return selected


def _audit_record(row: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "sampleId": row["sampleId"],
        "split": row["split"],
        "format": row["format"],
        "sourceGroupId": row["sourceGroupId"],
        "sourceUrl": row["sourceUrl"],
        "referenceDocId": row["referenceDocId"],
        "referenceDocFilename": row["referenceDocFilename"],
        "generationMethod": row["generationMethod"],
        "primaryFailureCategory": row["primaryFailureCategory"],
        "signals": list(row["signals"]),
        "referenceRank": row["referenceRank"],
        "phraseRecall": row["phraseRecall"],
        "queryLength": row["queryLength"],
        "referenceChunkIds": row["referenceChunkIds"],
        "topChunkIds": row["topChunkIds"],
        "repairAllowedForTuning": row["split"] in {"calibration", "development"},
        "topContexts": row["topContexts"],
        "candidateContexts": row["candidateContexts"],
        "userInputPreview": row["userInputPreview"],
        "referencePreview": row["referencePreview"],
        "manualAudit": {
            "referenceChunkCorrect": None,
            "answerInNeighborChunk": None,
            "queryAmbiguous": None,
            "parserOrChunkerLostStructure": None,
            "notes": "",
        },
    }


def _markdown_report(report: Mapping[str, Any]) -> str:
    summary = report["summary"]
    lines = [
        "# Phase 10d-A Doc-Ingestion Failure Analysis",
        "",
        f"Run ID: `{report['runId']}`",
        f"Created: `{report['createdAt']}`",
        "",
        "## Summary",
        "",
        f"- Rows: {summary['rowCount']}",
        f"- topK hit rate: {summary['topKHitRate']:.4f}",
        f"- oracle recall@candidateK: {summary['oracleRecallAtCandidateK']:.4f}",
        f"- ranking gap: {summary['rankingGap']:.4f}",
        f"- failures: {summary['failureCount']}",
        "",
        "## Recommended First Axis",
        "",
        f"`{report.get('recommendedFirstAxis')}`",
        "",
        "## Axis Ranking",
        "",
        "| Axis | Failures | Holdout failures | Holdout impact | Cost | Risk | Priority | Note |",
        "|---|---:|---:|---:|---|---|---:|---|",
    ]
    for item in report["axisRanking"]:
        lines.append(
            f"| {item['axis']} | {item['affectedFailures']} | {item['holdoutAffectedFailures']} | "
            f"{item['estimatedHoldoutRecallImpact']:.4f} | {item['implementationCost']} | "
            f"{item['regressionRisk']} | {item['priorityScore']:.4f} | {item['note']} |"
        )
    lines.extend(
        [
            "",
            "## Per Format",
            "",
            "| Format | Rows | topK | oracle | gap | phrase | avg query |",
            "|---|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for fmt, metrics in report["byFormat"].items():
        lines.append(
            f"| {fmt} | {metrics['rowCount']} | {metrics['topKHitRate']:.4f} | "
            f"{metrics['oracleRecallAtCandidateK']:.4f} | {metrics['rankingGap']:.4f} | "
            f"{metrics['averagePhraseRecall']:.4f} | {metrics['averageQueryLength']:.1f} |"
        )
    lines.extend(["", "## Failure Categories", "", "```json", json.dumps(report["failureCategories"], indent=2), "```", ""])
    lines.extend(
        [
            "Additional grouped metrics are available in `analysis.json`: "
            "`bySourceGroupId`, `byParser`, `byChunker`, `byQueryLengthBucket`, "
            "`byMineruRoute`, `byMqRoute`, and `byTableNumericFlag`.",
            "",
        ]
    )
    return "\n".join(lines)


def _audit_assist_report(
    report: Mapping[str, Any],
    audit_rows: Sequence[Mapping[str, Any]],
    repair_rows: Sequence[Mapping[str, Any]],
    trainable_repairs: Sequence[Mapping[str, Any]],
) -> str:
    lines = [
        "# Phase 10d-A Audit Assist",
        "",
        "This file is an audit worklist summary, not a completed human review.",
        "Holdout rows may inform diagnosis, but must not drive tuning-specific repairs.",
        "",
        "## Counts",
        "",
        f"- Audit worklist rows: {len(audit_rows)}",
        f"- Repair candidates: {len(repair_rows)}",
        f"- Calibration/development repair candidates allowed for tuning: {len(trainable_repairs)}",
        f"- Recommended first axis: `{report.get('recommendedFirstAxis')}`",
        "",
        "## Repair Candidates By Split",
        "",
        "```json",
        json.dumps(_counter_by(repair_rows, "split"), indent=2, sort_keys=True),
        "```",
        "",
        "## Trainable Repair Candidates By Format",
        "",
        "```json",
        json.dumps(_counter_by(trainable_repairs, "format"), indent=2, sort_keys=True),
        "```",
        "",
        "## Trainable Repair Candidates By Category",
        "",
        "```json",
        json.dumps(_counter_by(trainable_repairs, "primaryFailureCategory"), indent=2, sort_keys=True),
        "```",
        "",
        "## Next Audit Questions",
        "",
        "- Is the reference chunk objectively correct, or is a neighboring/top chunk a better label?",
        "- Did parser/chunker output lose table headers, section titles, or row context?",
        "- Is the query a reusable user-like query, or just copied evidence text?",
        "- For holdout rows, record the diagnosis but do not tune against it.",
        "",
    ]
    return "\n".join(lines)


def _counter_by(rows: Sequence[Mapping[str, Any]], key: str) -> dict[str, int]:
    return dict(sorted(Counter(str(row.get(key) or "unknown") for row in rows).items()))


def _first_rank(ids: Sequence[str], reference_ids: set[str]) -> int | None:
    for index, value in enumerate(ids, start=1):
        if value in reference_ids:
            return index
    return None


def _has_same_doc(
    contexts: Sequence[Mapping[str, Any]], reference_doc_id: str, reference_ids: set[str]
) -> bool:
    if not reference_doc_id:
        return False
    return any(
        str(context.get("documentId") or "") == reference_doc_id
        and str(context.get("chunkId") or context.get("id") or "") not in reference_ids
        for context in contexts
    )


def _looks_like_evidence_query(query: str, reference: str) -> bool:
    if len(query) < 120 or not reference:
        return False
    normalized_query = " ".join(query.casefold().split())
    normalized_reference = " ".join(reference.casefold().split())
    return normalized_query[:120] in normalized_reference or normalized_reference[:120] in normalized_query


def _query_length_bucket(length: int) -> str:
    if length < 80:
        return "short_lt80"
    if length < 180:
        return "medium_80_179"
    if length < 320:
        return "long_180_319"
    return "very_long_320_plus"


def _context_preview(context: Mapping[str, Any], rank: int) -> dict[str, Any]:
    return {
        "rank": rank,
        "chunkId": context.get("chunkId"),
        "documentId": context.get("documentId"),
        "score": context.get("score"),
        "contentPreview": _preview(str(context.get("content") or "")),
    }


def _preview(value: str, limit: int = 400) -> str:
    compact = " ".join(value.split())
    return compact if len(compact) <= limit else compact[: limit - 3] + "..."


def _mean(values: Sequence[float] | Any) -> float:
    materialized = list(values)
    return sum(materialized) / len(materialized) if materialized else 0.0


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True), encoding="utf-8")


def _write_jsonl(path: Path, rows: Sequence[Mapping[str, Any]]) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, sort_keys=True) + "\n")
