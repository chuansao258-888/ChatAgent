"""Build the sealed-safe calibration/development subset for final Memory L3 tuning."""

from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Mapping, Sequence

from chatagent_eval.datasets import read_jsonl, sha256_file, sha256_json, write_json
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import SAFE_RUN_ID, build_manifest, build_report, write_run_artifacts

ALLOWED_SPLITS = {"calibration", "development"}
HARD_NEGATIVE_CATEGORIES = (
    "question_or_information_need",
    "conditional_or_hypothetical",
    "other_person_fact",
    "temporary_need_or_open_plan",
    "assistant_statement_only",
    "baseline_non_atomic_or_unstable",
)

_QUESTION = re.compile(
    r"(?:\?|^\s*(?:who|what|when|where|why|how|which|can|could|would|should|is|are|do|does|did|tell me|explain)\b)",
    re.IGNORECASE,
)
_CONDITIONAL = re.compile(r"^\s*(?:if|suppose|assuming|imagine|hypothetically)\b", re.IGNORECASE)
_OTHER_PERSON = re.compile(
    r"\b(?:son|daughter|child|parent|sibling|partner|spouse|friend|colleague|coworker|manager|employee)s?\b",
    re.IGNORECASE,
)
_OPEN_PLAN = re.compile(
    r"\b(?:want|would like|interested|considering|might|may|plan(?:ning)?|need|looking for|currently|this week|today)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class MemoryTuningSubsetConfig:
    run_id: str
    hard_negative_target: int = 120
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if not SAFE_RUN_ID.fullmatch(self.run_id):
            raise ValueError(f"unsafe run id: {self.run_id}")
        if not 100 <= self.hard_negative_target <= 150:
            raise ValueError("hard_negative_target must be between 100 and 150")


def run_memory_tuning_subset(
    *,
    source_export_samples: Path,
    semantic_run_dir: Path,
    output_root: Path,
    config: MemoryTuningSubsetConfig,
) -> Path:
    source_rows = _read_caldev_source_rows(source_export_samples)
    semantic_rows = read_jsonl(semantic_run_dir / "samples.jsonl")
    semantic_manifest = _read_json(semantic_run_dir / "manifest.json")
    _validate_source_rows(source_rows)

    supported_references = [
        row
        for row in semantic_rows
        if row.get("metadata", {}).get("role") == "l3-reference"
        and row.get("metadata", {}).get("judgeLabel") == "supported"
        and str(row.get("split") or "") in ALLOWED_SPLITS
    ]
    if not supported_references:
        raise ValueError("semantic run contains no supported calibration/development L3 references")
    positive_ids = sorted({str(row["metadata"]["sourceSampleId"]) for row in supported_references})
    source_by_id = {str(row["sampleId"]): row for row in source_rows}
    missing = [sample_id for sample_id in positive_ids if sample_id not in source_by_id]
    if missing:
        raise ValueError(f"supported reference source rows missing from export: {missing[:3]}")

    missed = [
        {
            "sourceSampleId": str(row["metadata"]["sourceSampleId"]),
            "referenceClusterId": str(row["metadata"]["referenceClusterId"]),
            "canonicalFact": str(row["metadata"]["claim"]),
            "memoryType": str(row["metadata"].get("memoryType") or ""),
            "split": str(row["split"]),
            "category": _reference_category(str(row["metadata"]["claim"])),
        }
        for row in supported_references
        if not row["metadata"].get("l3ExtractedMatch", {}).get("matched", False)
    ]
    matched_count = len(supported_references) - len(missed)

    hard_negative_pool = [
        row
        for row in source_rows
        if str(row["sampleId"]) not in set(positive_ids)
        and str(row["split"]) in ALLOWED_SPLITS
    ]
    categorized = {category: [] for category in HARD_NEGATIVE_CATEGORIES}
    for row in hard_negative_pool:
        categorized[_hard_negative_category(row)].append(row)
    selected_hard_negatives = _round_robin(categorized, config.hard_negative_target)
    if len(selected_hard_negatives) != config.hard_negative_target:
        raise ValueError(
            f"only {len(selected_hard_negatives)} eligible hard negatives; expected {config.hard_negative_target}"
        )

    positives = [
        {
            "sampleId": sample_id,
            "split": str(source_by_id[sample_id]["split"]),
            "referenceCount": sum(
                str(row["metadata"]["sourceSampleId"]) == sample_id for row in supported_references
            ),
        }
        for sample_id in positive_ids
    ]
    negatives = [
        {
            "sampleId": str(row["sampleId"]),
            "split": str(row["split"]),
            "category": _hard_negative_category(row),
            "baselineExtractedMemoryCount": len(
                row.get("moduleOutputs", {}).get("l3Extraction", {}).get("memories", [])
            ),
        }
        for row in selected_hard_negatives
    ]
    sample_ids = [row["sampleId"] for row in positives] + [row["sampleId"] for row in negatives]
    if len(sample_ids) != len(set(sample_ids)):
        raise ValueError("memory tuning subset contains duplicate sample IDs")

    config_dict = {
        "hardNegativeTarget": config.hard_negative_target,
        "allowedSplits": sorted(ALLOWED_SPLITS),
        "sourceExportSamples": str(source_export_samples),
        "sourceExportHash": sha256_file(source_export_samples),
        "semanticRunId": semantic_manifest.get("runId"),
        "semanticSamplesHash": sha256_file(semantic_run_dir / "samples.jsonl"),
    }
    selection = {
        "schemaVersion": 1,
        "runId": config.run_id,
        "sampleIds": sample_ids,
        "positiveRows": positives,
        "hardNegativeRows": negatives,
        "counts": {
            "sampleCount": len(sample_ids),
            "positiveRowCount": len(positives),
            "supportedReferenceCount": len(supported_references),
            "matchedReferenceCount": matched_count,
            "missedReferenceCount": len(missed),
            "hardNegativeCount": len(negatives),
            "bySplit": dict(Counter(row["split"] for row in (*positives, *negatives))),
            "hardNegativesByCategory": dict(Counter(row["category"] for row in negatives)),
        },
        "provenance": config_dict,
    }
    analysis = {
        "schemaVersion": 1,
        "runId": config.run_id,
        "policy": "memory-reference-eligibility-v2",
        "matchedReferenceCount": matched_count,
        "missedReferenceCount": len(missed),
        "missedByCategory": dict(Counter(row["category"] for row in missed)),
        "missedReferences": missed,
        "holdoutAccessed": False,
    }
    metrics = {
        "memory.tuningSubsetSampleCount": float(len(sample_ids)),
        "memory.tuningSubsetPositiveRowCount": float(len(positives)),
        "memory.tuningSubsetSupportedReferenceCount": float(len(supported_references)),
        "memory.tuningSubsetMissedReferenceCount": float(len(missed)),
        "memory.tuningSubsetHardNegativeCount": float(len(negatives)),
    }
    fingerprint = config_fingerprint(config_dict)
    manifest = build_manifest(
        run_id=config.run_id,
        suite="memory-tuning-subset",
        mode="calibration-development",
        timestamp=datetime.now(UTC).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=str(semantic_manifest.get("datasetId") or "memory-v2-dialogues"),
        dataset_hash=sha256_json(sample_ids),
        config=config_dict,
        config_fingerprint=fingerprint,
        artifact_files=("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json",
                        "selection.json", "missed-reference-analysis.json"),
    )
    report = build_report(
        run_id=config.run_id,
        suite="memory-tuning-subset",
        mode="calibration-development",
        status="pass",
        dataset_id=str(manifest["datasetId"]),
        dataset_hash=str(manifest["datasetHash"]),
        config_fingerprint=fingerprint,
        metrics=metrics,
    )
    run_dir = write_run_artifacts(output_root, manifest, metrics, [*positives, *negatives], [])
    write_json(run_dir / "selection.json", selection)
    write_json(run_dir / "missed-reference-analysis.json", analysis)
    write_json(run_dir / "report.json", report)
    return run_dir


def _validate_source_rows(rows: Sequence[Mapping[str, Any]]) -> None:
    if not rows:
        raise ValueError("source export is empty")
    ids = [str(row.get("sampleId") or "") for row in rows]
    if any(not value for value in ids) or len(ids) != len(set(ids)):
        raise ValueError("source export sample IDs must be non-empty and unique")


def _read_caldev_source_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            row = json.loads(line)
            if str(row.get("split") or "") in ALLOWED_SPLITS:
                rows.append(row)
    return rows


def _hard_negative_category(row: Mapping[str, Any]) -> str:
    turns = list(row.get("turns") or [])
    user_text = " ".join(str(turn.get("text") or "") for turn in turns if turn.get("speaker") == "user")
    assistant_text = " ".join(str(turn.get("text") or "") for turn in turns if turn.get("speaker") != "user")
    memories = row.get("moduleOutputs", {}).get("l3Extraction", {}).get("memories", [])
    explicit_user_fact = bool(re.search(r"\b(?:i|i'm|i am|my|we|our)\b", user_text, re.IGNORECASE))
    if memories and assistant_text and not explicit_user_fact:
        return "assistant_statement_only"
    if _CONDITIONAL.search(user_text):
        return "conditional_or_hypothetical"
    if _OTHER_PERSON.search(user_text):
        return "other_person_fact"
    if _OPEN_PLAN.search(user_text):
        return "temporary_need_or_open_plan"
    if _QUESTION.search(user_text):
        return "question_or_information_need"
    if memories:
        return "baseline_non_atomic_or_unstable"
    return "question_or_information_need"


def _round_robin(
    categorized: Mapping[str, Sequence[Mapping[str, Any]]],
    target: int,
) -> list[Mapping[str, Any]]:
    ordered = {
        category: sorted(rows, key=lambda row: str(row["sampleId"]))
        for category, rows in categorized.items()
    }
    selected: list[Mapping[str, Any]] = []
    index = 0
    while len(selected) < target:
        added = False
        for category in HARD_NEGATIVE_CATEGORIES:
            rows = ordered[category]
            if index < len(rows):
                selected.append(rows[index])
                added = True
                if len(selected) == target:
                    break
        if not added:
            break
        index += 1
    return selected


def _reference_category(fact: str) -> str:
    lowered = fact.lower()
    if re.search(r"\b(?:allerg|must|cannot|can't|never|constraint|requires?)\b", lowered):
        return "durable_constraint"
    if re.search(r"\b(?:decided|will use|will avoid|chose|committed|does not co-sign)\b", lowered):
        return "settled_decision"
    if re.search(r"\b(?:owns?|has|uses?|invests?|works on|project|business)\b", lowered):
        return "ownership_active_use_or_project"
    if re.search(r"\b(?:likes?|loves?|prefers?|fan|habit|always)\b", lowered):
        return "stable_preference_belief_or_habit"
    if re.search(r"\b(?:is|lives?|national|student|owner|works?)\b", lowered):
        return "stable_identity_or_background"
    if re.search(r"\b(?:interested|wants?|currently|learned|disappointed)\b", lowered):
        return "transient_or_low_stability_reference"
    return "confirmed_user_fact"


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))
