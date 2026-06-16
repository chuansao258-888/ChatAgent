"""Deterministic split/source-group analysis for completed semantic Memory runs."""

from __future__ import annotations

import json
from collections import Counter, defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.datasets import sha256_file
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import SAFE_RUN_ID, write_json_artifact

ARTIFACT_FILES = ("manifest.json", "analysis.json", "analysis.md")


@dataclass(frozen=True)
class MemorySemanticAnalysisConfig:
    run_id: str
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if not SAFE_RUN_ID.fullmatch(self.run_id):
            raise ValueError(f"unsafe run id: {self.run_id}")


def run_memory_semantic_analysis(
    *,
    input_run_dir: Path,
    output_root: Path,
    config: MemorySemanticAnalysisConfig,
) -> Path:
    manifest_path = input_run_dir / "manifest.json"
    report_path = input_run_dir / "report.json"
    samples_path = input_run_dir / "samples.jsonl"
    if not manifest_path.is_file() or not report_path.is_file() or not samples_path.is_file():
        raise ValueError("semantic input run is missing manifest, report, or samples")
    source_manifest = _read_json(manifest_path)
    source_report = _read_json(report_path)
    samples = _read_jsonl(samples_path)
    _validate_source_run(source_manifest, source_report, samples)

    by_split = {
        key: _aggregate(rows)
        for key, rows in sorted(_group(samples, "split").items())
    }
    by_source_group = {
        key: _aggregate(rows)
        for key, rows in sorted(_group(samples, "sourceGroupId", nested=True).items())
    }
    split_variance = _variance(by_split)
    source_group_variance = _variance(by_source_group)
    visible_by_split = _has_visible_variance(split_variance)
    visible_by_source_group = _has_visible_variance(source_group_variance)
    analysis = {
        "status": "pass" if visible_by_split and visible_by_source_group else "warn",
        "sourceRun": {
            "runId": source_manifest["runId"],
            "datasetId": source_manifest["datasetId"],
            "datasetHash": source_manifest["datasetHash"],
            "configFingerprint": source_manifest["configFingerprint"],
            "manifestHash": sha256_file(manifest_path),
            "reportHash": sha256_file(report_path),
            "samplesHash": sha256_file(samples_path),
        },
        "overall": _aggregate(samples),
        "bySplit": by_split,
        "bySourceGroup": by_source_group,
        "variance": {
            "bySplit": split_variance,
            "bySourceGroup": source_group_variance,
            "visibleBySplit": visible_by_split,
            "visibleBySourceGroup": visible_by_source_group,
        },
    }
    config_dict = {"sourceRunId": source_manifest["runId"]}
    manifest = {
        "runId": config.run_id,
        "suite": "memory-semantic-analysis",
        "mode": "deterministic",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "gitBranch": config.git_branch,
        "gitSha": config.git_sha,
        "datasetId": source_manifest["datasetId"],
        "datasetHash": source_manifest["datasetHash"],
        "config": config_dict,
        "configFingerprint": config_fingerprint(config_dict),
        "sourceRun": analysis["sourceRun"],
        "artifactFiles": list(ARTIFACT_FILES),
    }
    run_dir = (output_root.resolve() / config.run_id).resolve()
    if not run_dir.is_relative_to(output_root.resolve()):
        raise ValueError("analysis run directory escapes output root")
    run_dir.mkdir(parents=True, exist_ok=True)
    write_json_artifact(run_dir / "manifest.json", manifest)
    write_json_artifact(run_dir / "analysis.json", analysis)
    (run_dir / "analysis.md").write_text(_render_markdown(analysis), encoding="utf-8", newline="\n")
    return run_dir


def _validate_source_run(
    manifest: Mapping[str, Any],
    report: Mapping[str, Any],
    samples: Sequence[Mapping[str, Any]],
) -> None:
    if manifest.get("suite") != "memory-semantic" or report.get("suite") != "memory-semantic":
        raise ValueError("input run must be a memory-semantic run")
    if manifest.get("mode") != "full-export" or report.get("mode") != "full-export":
        raise ValueError("semantic analysis requires a full-export source run")
    for field in ("runId", "datasetId", "datasetHash", "configFingerprint"):
        if manifest.get(field) != report.get(field):
            raise ValueError(f"semantic manifest/report mismatch: {field}")
    if not samples:
        raise ValueError("semantic input run contains no samples")
    ids = [str(row.get("sampleId") or "") for row in samples]
    if any(not sample_id for sample_id in ids) or len(ids) != len(set(ids)):
        raise ValueError("semantic input run has missing or duplicate sample IDs")
    for row in samples:
        metadata = row.get("metadata")
        judgments = metadata.get("judgments") if isinstance(metadata, Mapping) else None
        if (
            not str(row.get("split") or "")
            or not isinstance(metadata, Mapping)
            or not str(metadata.get("sourceGroupId") or "")
            or not str(metadata.get("role") or "")
            or not isinstance(judgments, list)
            or not judgments
        ):
            raise ValueError(f"semantic sample is incomplete: {row.get('sampleId')}")
    metrics = report.get("metrics")
    if not isinstance(metrics, Mapping) or metrics.get("memory.semanticCompletionRate") != 1.0:
        raise ValueError("semantic analysis requires a complete source run")
    computed = _aggregate(samples)
    expected = {
        "memory.semanticItemCount": computed["itemCount"],
        "memory.semanticL2SupportRate": computed["l2"]["supportRate"],
        "memory.semanticL3ExtractionPrecision": computed["l3"]["precision"],
        "memory.semanticL3ExtractionRecall": computed["l3"]["recall"],
        "memory.semanticL3ExtractionF1": computed["l3"]["f1"],
    }
    for key, value in expected.items():
        if not _same_metric(metrics.get(key), value):
            raise ValueError(f"semantic report/sample mismatch: {key}")


def _group(
    samples: Sequence[Mapping[str, Any]],
    key: str,
    *,
    nested: bool = False,
) -> dict[str, list[Mapping[str, Any]]]:
    grouped: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for sample in samples:
        value = sample["metadata"].get(key) if nested else sample.get(key)
        grouped[str(value)].append(sample)
    return grouped


def _aggregate(samples: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    l2 = [row for row in samples if row["metadata"]["role"] == "l2-summary"]
    extracted = [row for row in samples if row["metadata"]["role"] == "l3-extracted"]
    references = [row for row in samples if row["metadata"]["role"] == "l3-reference"]
    supported_references = [row for row in references if _label(row) == "supported"]
    matched_extracted = [row for row in extracted if row["metadata"].get("l3ReferenceMatch", {}).get("matched") is True]
    matched_references = [row for row in supported_references if row["metadata"].get("l3ExtractedMatch", {}).get("matched") is True]
    precision = len(matched_extracted) / len(extracted) if extracted else None
    recall = len(matched_references) / len(supported_references) if supported_references else None
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall > 0
        else 0.0 if precision == 0 or recall == 0 else None
    )
    return {
        "itemCount": len(samples),
        "sourceSampleCount": len({str(row["metadata"]["sourceSampleId"]) for row in samples}),
        "sourceGroupCount": len({str(row["metadata"]["sourceGroupId"]) for row in samples}),
        "labelCounts": dict(sorted(Counter(_label(row) for row in samples).items())),
        "l2": {
            "itemCount": len(l2),
            "supportRate": _rate(l2, "supported"),
            "contradictionRate": _rate(l2, "contradicted"),
        },
        "l3": {
            "extractedCount": len(extracted),
            "referenceCount": len(supported_references),
            "generatedReferenceCount": len(references),
            "matchedCount": len(matched_extracted),
            "supportRate": _rate(extracted, "supported"),
            "referenceSupportRate": _rate(references, "supported"),
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "usefulness": _mean(_judgment(row).get("usefulnessScore") for row in extracted),
            "subjectActionObjectCoverage": _mean(
                _judgment(row).get("subjectActionObjectCoverage") for row in extracted
            ),
            "userSpecificityRate": _mean(
                1.0 if _judgment(row).get("userSpecificity") is True else 0.0 for row in extracted
            ),
            "temporalStabilityRate": _mean(
                1.0 if _judgment(row).get("temporalStability") is True else 0.0 for row in extracted
            ),
        },
    }


def _variance(groups: Mapping[str, Mapping[str, Any]]) -> dict[str, dict[str, float | int | None]]:
    paths = {
        "l2SupportRate": ("l2", "supportRate"),
        "l3SupportRate": ("l3", "supportRate"),
        "l3Precision": ("l3", "precision"),
        "l3Recall": ("l3", "recall"),
        "l3F1": ("l3", "f1"),
        "l3Usefulness": ("l3", "usefulness"),
        "l3TemporalStabilityRate": ("l3", "temporalStabilityRate"),
    }
    result: dict[str, dict[str, float | int | None]] = {}
    for name, path in paths.items():
        values = [
            float(group[path[0]][path[1]])
            for group in groups.values()
            if group[path[0]][path[1]] is not None
        ]
        result[name] = {
            "groupCount": len(values),
            "min": min(values) if values else None,
            "max": max(values) if values else None,
            "range": max(values) - min(values) if values else None,
        }
    return result


def _has_visible_variance(variance: Mapping[str, Mapping[str, Any]]) -> bool:
    return any(
        int(metric.get("groupCount") or 0) >= 2 and float(metric.get("range") or 0.0) > 0.0
        for metric in variance.values()
    )


def _judgment(sample: Mapping[str, Any]) -> Mapping[str, Any]:
    return sample["metadata"]["judgments"][0]


def _label(sample: Mapping[str, Any]) -> str:
    return str(_judgment(sample).get("label") or "")


def _rate(samples: Sequence[Mapping[str, Any]], label: str) -> float | None:
    return sum(_label(row) == label for row in samples) / len(samples) if samples else None


def _mean(values: Sequence[float] | Any) -> float | None:
    materialized = [float(value) for value in values if value is not None]
    return sum(materialized) / len(materialized) if materialized else None


def _render_markdown(analysis: Mapping[str, Any]) -> str:
    overall = analysis["overall"]
    lines = [
        "# Memory Semantic Analysis",
        "",
        f"- Source run: `{analysis['sourceRun']['runId']}`",
        f"- Items: `{overall['itemCount']}`",
        f"- Source groups: `{overall['sourceGroupCount']}`",
        f"- Visible split variance: `{str(analysis['variance']['visibleBySplit']).lower()}`",
        f"- Visible source-group variance: `{str(analysis['variance']['visibleBySourceGroup']).lower()}`",
        "",
        "## Split Metrics",
        "",
        "| Split | L2 support | L3 extracted | L3 precision | L3 recall | L3 F1 | L3 usefulness |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for split, row in analysis["bySplit"].items():
        lines.append(
            f"| {split} | {_fmt(row['l2']['supportRate'])} | {row['l3']['extractedCount']} | "
            f"{_fmt(row['l3']['precision'])} | {_fmt(row['l3']['recall'])} | "
            f"{_fmt(row['l3']['f1'])} | {_fmt(row['l3']['usefulness'])} |"
        )
    lines.extend(["", "## Notes", "", "- This artifact performs no model or judge calls.", ""])
    return "\n".join(lines)


def _fmt(value: Any) -> str:
    return "null" if value is None else f"{float(value):.4f}"


def _same_metric(left: Any, right: Any) -> bool:
    if left is None or right is None:
        return left is None and right is None
    return abs(float(left) - float(right)) <= 1e-12


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
