"""Semantic Memory V2 support/usefulness evaluation with an explicit LLM judge."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
from contextlib import suppress
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.datasets import sha256_file
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import SAFE_RUN_ID, build_manifest, build_report, write_json_artifact, write_run_artifacts

LABELS = ("supported", "partial", "unsupported", "contradicted")
TARGETS = ("l2", "l3")
REFERENCE_POLICY_VERSION = "memory-reference-eligibility-v2"
ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")
RUBRIC = {
    "supported": "The claim is directly entailed by cited source turns.",
    "partial": "The claim is broadly consistent but missing a required qualifier, entity, time bound, or source detail.",
    "unsupported": "The claim is not evidenced by the cited source turns.",
    "contradicted": "The claim conflicts with cited source turns.",
}
JUDGE_GUIDANCE = (
    "Use supported for faithful paraphrases and for shorter claims whose omitted details do not change their scope "
    "or truth. Use partial only when an omission changes a required scope, condition, identity, time bound, or "
    "material qualification. Use unsupported when the cited turns neither establish nor make the claim false. "
    "Use contradicted only when the cited turns explicitly or necessarily make the claim false; merely mentioning "
    "a different non-exclusive fact is not a contradiction."
)
Judge = Callable[[Mapping[str, Any], "MemorySemanticConfig"], Mapping[str, Any]]
Embedder = Callable[[Sequence[str], "MemorySemanticConfig"], list[list[float]]]
MatchJudge = Callable[[Mapping[str, Any], Mapping[str, Any], "MemorySemanticConfig"], Mapping[str, Any]]


@dataclass(frozen=True)
class MemorySemanticConfig:
    run_id: str
    input_kind: str = "calibration"
    judge_provider: str = "deepseek"
    judge_model: str = "deepseek-chat"
    judge_base_url: str | None = None
    judge_api_key: str | None = None
    repeats: int = 2
    judge_max_attempts_per_repeat: int = 3
    embedding_model: str = "bge-m3"
    embedding_base_url: str | None = None
    embedding_num_gpu: int | None = 1
    embedding_match_threshold: float = 0.80
    max_samples: int | None = None
    splits: tuple[str, ...] = ()
    targets: tuple[str, ...] = TARGETS
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.input_kind not in {"calibration", "full-export"}:
            raise ValueError("input_kind must be calibration or full-export")
        if self.repeats <= 0 or (self.input_kind == "calibration" and self.repeats < 2):
            raise ValueError("repeats must be at least 2 for calibration and at least 1 for full-export")
        if self.judge_max_attempts_per_repeat <= 0:
            raise ValueError("judge_max_attempts_per_repeat must be positive")
        if self.max_samples is not None and self.max_samples <= 0:
            raise ValueError("max_samples must be positive when provided")
        if self.input_kind == "full-export" and not self.splits:
            raise ValueError("full-export splits must be explicit so sealed holdout is not accessed accidentally")
        if not self.judge_provider.strip() or not self.judge_model.strip():
            raise ValueError("judge provider and model must be non-empty")
        if not self.embedding_model.strip():
            raise ValueError("embedding_model must be non-empty")
        if self.embedding_num_gpu is not None and self.embedding_num_gpu < 0:
            raise ValueError("embedding_num_gpu must be non-negative")
        if not 0 <= self.embedding_match_threshold <= 1:
            raise ValueError("embedding_match_threshold must be between 0 and 1")
        if not self.targets or any(target not in TARGETS for target in self.targets):
            raise ValueError("targets must contain only l2 and/or l3")
        if not SAFE_RUN_ID.fullmatch(self.run_id):
            raise ValueError(f"unsafe run id: {self.run_id}")

    def as_dict(self) -> dict[str, Any]:
        return {
            "inputKind": self.input_kind,
            "judgeProvider": self.judge_provider,
            "judgeModel": self.judge_model,
            "judgeTemperature": 0.0,
            "repeats": self.repeats,
            "judgeMaxAttemptsPerRepeat": self.judge_max_attempts_per_repeat,
            "embeddingModel": self.embedding_model,
            "embeddingNumGpu": self.embedding_num_gpu,
            "embeddingMatchThreshold": self.embedding_match_threshold,
            "l3MatchProtocol": "embedding-threshold-plus-pairwise-judge-v1",
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
            "targets": list(self.targets),
            "rubric": dict(RUBRIC),
            "judgeGuidance": JUDGE_GUIDANCE,
        }


def run_memory_semantic(
    *,
    input_path: Path,
    output_root: Path,
    config: MemorySemanticConfig,
    judge: Judge | None = None,
    reference_cluster_run_dir: Path | None = None,
    reuse_reference_semantic_run_dir: Path | None = None,
    embedder: Embedder | None = None,
    match_judge: MatchJudge | None = None,
) -> Path:
    reference_rows, reference_provenance, policy_rejected_by_sample = _load_reference_clusters(
        reference_cluster_run_dir,
        input_path,
        config,
    )
    dataset_id, items, review, selected_source_ids = _load_items(input_path, config, reference_rows)
    reusable_references, reuse_provenance = _load_reusable_reference_judgments(
        reuse_reference_semantic_run_dir,
        items,
        config,
    )
    selected_policy_rejected_count = sum(
        policy_rejected_by_sample.get(sample_id, 0) for sample_id in selected_source_ids
    )
    if reference_provenance is not None:
        reference_provenance["policyRejectedReferenceCount"] = selected_policy_rejected_count
    dataset_hash = sha256_file(input_path)
    config_dict = config.as_dict() | {
        "inputFile": input_path.name,
        "calibrationReview": review,
        "referenceClusterRun": reference_provenance,
        "reuseReferenceSemanticRun": reuse_provenance,
    }
    fingerprint = config_fingerprint(config_dict)
    judge_fn = judge or _call_judge
    checkpoint_dir, checkpoint_rows = _checkpoint_paths(output_root, config.run_id)
    completed = _load_or_create_checkpoint(
        checkpoint_dir=checkpoint_dir,
        checkpoint_rows=checkpoint_rows,
        run_id=config.run_id,
        config_fingerprint_value=fingerprint,
        dataset_hash=dataset_hash,
        item_ids=[str(item["id"]) for item in items],
    )
    samples = [dict(row["sample"]) for row in completed]
    failures = [dict(failure) for row in completed for failure in row["failures"]]
    completed_ids = {str(row["itemId"]) for row in completed}
    for item in items:
        item_id = str(item["id"])
        reusable = reusable_references.get(item_id)
        if item_id in completed_ids or reusable is None:
            continue
        samples.append(reusable)
        completed_ids.add(item_id)

    for item in items:
        if str(item["id"]) in completed_ids:
            continue
        sample, item_failures = _evaluate_item(item, config, judge_fn)
        samples.append(sample)
        failures.extend(item_failures)
        _append_checkpoint_row(
            checkpoint_rows,
            {"itemId": str(item["id"]), "sample": sample, "failures": item_failures},
        )

    match_metrics, match_failures = _l3_reference_metrics(
        samples,
        config,
        embedder or _embed_texts,
        reference_enabled=reference_provenance is not None,
        match_judge=match_judge or _call_match_judge,
    )
    failures.extend(match_failures)
    metrics = _aggregate(samples, config) | match_metrics
    metrics["memory.semanticL3PolicyRejectedReferenceCount"] = float(
        selected_policy_rejected_count
    )
    numeric_metrics = {key: value for key, value in metrics.items() if key != "memory.semanticLabelCounts"}
    status = "warn" if failures else "pass"
    manifest = build_manifest(
        run_id=config.run_id,
        suite="memory-semantic",
        mode=config.input_kind,
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=dataset_id,
        dataset_hash=dataset_hash,
        config=config_dict,
        config_fingerprint=fingerprint,
        models={
            "judge": f"{config.judge_provider}/{config.judge_model}",
            "embedding": config.embedding_model,
        },
        artifact_files=ARTIFACT_FILES,
    )
    metric_root = {
        "status": status,
        "semantic": {
            "l2SupportRate": metrics["memory.semanticL2SupportRate"],
            "l2ContradictionRate": metrics["memory.semanticL2ContradictionRate"],
            "l3SupportRate": metrics["memory.semanticL3SupportRate"],
            "l3Usefulness": metrics["memory.semanticL3Usefulness"],
            "l3ReferenceSupportRate": metrics["memory.semanticL3ReferenceSupportRate"],
            "l3ExtractionPrecision": metrics["memory.semanticL3ExtractionPrecision"],
            "l3ExtractionRecall": metrics["memory.semanticL3ExtractionRecall"],
            "l3ExtractionF1": metrics["memory.semanticL3ExtractionF1"],
            "l3MatchedTypeAccuracy": metrics["memory.semanticL3MatchedTypeAccuracy"],
            "l3SubjectActionObjectCoverage": metrics["memory.semanticL3SubjectActionObjectCoverage"],
            "l3UserSpecificityRate": metrics["memory.semanticL3UserSpecificityRate"],
            "l3TemporalStabilityRate": metrics["memory.semanticL3TemporalStabilityRate"],
            "judgeRepeatAgreement": metrics["memory.semanticJudgeRepeatAgreement"],
            "calibrationAccuracy": metrics["memory.semanticCalibrationAccuracy"],
            "completionRate": metrics["memory.semanticCompletionRate"],
        },
        "labelCounts": metrics["memory.semanticLabelCounts"],
        "merged": numeric_metrics,
    }
    report = build_report(
        run_id=config.run_id,
        suite="memory-semantic",
        mode=config.input_kind,
        status=status,
        dataset_id=dataset_id,
        dataset_hash=dataset_hash,
        config_fingerprint=fingerprint,
        metrics=numeric_metrics,
        threshold_results=[],
    )
    run_dir = write_run_artifacts(output_root, manifest, metric_root, samples, failures)
    write_json_artifact(run_dir / "report.json", report)
    _remove_checkpoint(checkpoint_dir, checkpoint_rows)
    return run_dir


def _load_reusable_reference_judgments(
    run_dir: Path | None,
    items: Sequence[Mapping[str, Any]],
    config: MemorySemanticConfig,
) -> tuple[dict[str, dict[str, Any]], dict[str, Any] | None]:
    if run_dir is None:
        return {}, None
    manifest_path = run_dir / "manifest.json"
    samples_path = run_dir / "samples.jsonl"
    if not manifest_path.is_file() or not samples_path.is_file():
        raise ValueError("reuse reference semantic run must contain manifest.json and samples.jsonl")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("suite") != "memory-semantic":
        raise ValueError("reuse reference semantic run has wrong suite")
    available: dict[tuple[str, str, str], Mapping[str, Any]] = {}
    with samples_path.open(encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            sample = json.loads(line)
            metadata = sample.get("metadata")
            if not isinstance(metadata, Mapping) or metadata.get("role") != "l3-reference":
                continue
            judgments = metadata.get("judgments")
            if not isinstance(judgments, list) or len(judgments) != config.repeats:
                continue
            key = (
                str(metadata.get("sourceSampleId") or ""),
                str(metadata.get("referenceClusterId") or ""),
                str(metadata.get("claim") or ""),
            )
            if key in available:
                raise ValueError(f"duplicate reusable reference judgment: {key[:2]}")
            available[key] = sample

    reusable: dict[str, dict[str, Any]] = {}
    for item in items:
        if item.get("role") != "l3-reference":
            continue
        key = (
            str(item.get("sourceSampleId") or ""),
            str(item.get("referenceClusterId") or ""),
            str(item.get("claim") or ""),
        )
        prior = available.get(key)
        if prior is None or str(prior.get("split") or "") != str(item.get("split") or ""):
            continue
        metadata = prior["metadata"]
        reused = _sample(item, metadata["judgments"], [], config)
        reused["metadata"]["judgeReuse"] = {
            "runId": manifest.get("runId"),
            "samplesHash": sha256_file(samples_path),
        }
        reusable[str(item["id"])] = reused
    return reusable, {
        "runId": manifest.get("runId"),
        "manifestHash": sha256_file(manifest_path),
        "samplesHash": sha256_file(samples_path),
        "reusedReferenceCount": len(reusable),
    }


def _evaluate_item(
    item: Mapping[str, Any],
    config: MemorySemanticConfig,
    judge_fn: Judge,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    judgments: list[dict[str, Any]] = []
    attempt_failures: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    for repeat in range(1, config.repeats + 1):
        repeat_errors: list[dict[str, Any]] = []
        for attempt in range(1, config.judge_max_attempts_per_repeat + 1):
            try:
                result = _validate_judgment(judge_fn(item, config))
                judgments.append(result | {"repeat": repeat, "attempt": attempt})
                break
            except Exception as exc:
                error = {
                    "repeat": repeat,
                    "attempt": attempt,
                    "error": _sanitize_judge_error(exc),
                }
                repeat_errors.append(error)
                attempt_failures.append(error)
                if not _is_retryable_judge_error(exc):
                    break
        if not judgments or judgments[-1]["repeat"] != repeat:
            failures.append(_failure(item, "memory.semanticJudge", "judge_error", repeat_errors, config))
    sample = _sample(item, judgments, attempt_failures, config)
    if len(judgments) == config.repeats and config.repeats >= 2:
        labels = [judgment["label"] for judgment in judgments]
        if len(set(labels)) != 1:
            failures.append(_failure(item, "memory.semanticJudgeRepeatAgreement", "repeat_disagreement", labels, config))
        expected = item.get("expectedLabel")
        if expected and labels[0] != expected:
            failures.append(
                _failure(
                    item,
                    "memory.semanticCalibrationAccuracy",
                    "calibration_label_mismatch",
                    {"expected": expected, "actual": labels[0]},
                    config,
                )
            )
    return sample, failures


def validate_calibration_fixture(root: Mapping[str, Any]) -> None:
    if str(root.get("datasetId") or "") != "memory-semantic-calibration-v1":
        raise ValueError("calibration fixture has unexpected datasetId")
    if dict(root.get("rubric") or {}) != RUBRIC:
        raise ValueError("calibration fixture rubric does not match the approved rubric")
    review = root.get("review")
    if not isinstance(review, Mapping) or not str(review.get("status") or "") or not str(review.get("reviewer") or ""):
        raise ValueError("calibration fixture must record honest review provenance")
    examples = list(root.get("examples") or [])
    ids = [str(item.get("id") or "") for item in examples]
    if len(examples) < 20 or any(not item for item in ids) or len(ids) != len(set(ids)):
        raise ValueError("calibration fixture needs at least 20 uniquely identified examples")
    labels = Counter(str(item.get("expectedLabel") or "") for item in examples)
    targets = Counter(str(item.get("target") or "") for item in examples)
    for label in LABELS:
        if labels[label] < 5:
            raise ValueError(f"calibration fixture needs at least 5 {label} examples")
    for target in TARGETS:
        if targets[target] < 5:
            raise ValueError(f"calibration fixture must cover {target}")
    for item in examples:
        turns = item.get("sourceTurns")
        if (
            item.get("target") not in TARGETS
            or item.get("expectedLabel") not in LABELS
            or not str(item.get("claim") or "").strip()
            or not str(item.get("expectedRationale") or "").strip()
            or not isinstance(turns, list)
            or not turns
            or any(not str(turn.get("id") or "") or not str(turn.get("text") or "").strip() for turn in turns)
        ):
            raise ValueError(f"invalid calibration example: {item.get('id')}")


def _load_reference_clusters(
    run_dir: Path | None,
    input_path: Path,
    config: MemorySemanticConfig,
) -> tuple[dict[str, dict[str, Any]] | None, dict[str, Any] | None, dict[str, int]]:
    if run_dir is None:
        return None, None, {}
    if config.input_kind != "full-export":
        raise ValueError("reference clusters may only be used with full-export semantic scoring")
    manifest_path = run_dir / "manifest.json"
    report_path = run_dir / "report.json"
    samples_path = run_dir / "samples.jsonl"
    if not manifest_path.is_file() or not report_path.is_file() or not samples_path.is_file():
        raise ValueError("reference cluster run is missing manifest, report, or samples")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if manifest.get("suite") != "memory-reference-clusters" or report.get("status") != "pass":
        raise ValueError("reference cluster run has invalid suite or status")
    rows = _read_jsonl(samples_path)
    result: dict[str, dict[str, Any]] = {}
    policy_rejected_by_sample: dict[str, int] = {}
    policy_rejected_count = 0
    for row in rows:
        sample_id = str(row.get("sampleId") or "")
        if not sample_id or sample_id in result:
            raise ValueError("reference cluster run has missing or duplicate sample IDs")
        metadata = row.get("metadata")
        if not isinstance(metadata, Mapping) or metadata.get("completed") is not True:
            raise ValueError(f"reference cluster run has incomplete row: {sample_id}")
        derived_source_hash = _reference_sample_source_input_hash(row)
        recorded_source_hash = metadata.get("sourceInputHash")
        if recorded_source_hash is not None and recorded_source_hash != derived_source_hash:
            raise ValueError(f"reference cluster run has invalid source input hash: {sample_id}")
        metadata["sourceInputHash"] = derived_source_hash
        clusters = metadata.get("referenceClusters")
        if not isinstance(clusters, list):
            raise ValueError(f"reference cluster run has invalid reference clusters: {sample_id}")
        source_turns = [
            {
                "id": str(context.get("id") or ""),
                "speaker": str(context.get("sourceId") or "unknown"),
                "text": str(context.get("text") or "").strip(),
            }
            for context in row.get("retrievedContexts") or []
        ]
        eligible: list[dict[str, Any]] = []
        policy_rejections: list[dict[str, Any]] = []
        for cluster in clusters:
            reason = _reference_policy_rejection(cluster, source_turns)
            if reason:
                policy_rejections.append(
                    {
                        "clusterId": str(cluster.get("clusterId") or ""),
                        "canonicalFact": str(cluster.get("canonicalFact") or ""),
                        "reason": reason,
                    }
                )
            else:
                eligible.append(cluster)
        metadata["referenceClusters"] = eligible
        metadata["policyRejectedReferenceClusters"] = policy_rejections
        policy_rejected_count += len(policy_rejections)
        policy_rejected_by_sample[sample_id] = len(policy_rejections)
        result[sample_id] = row
    return result, {
        "runId": str(manifest.get("runId") or ""),
        "datasetHash": str(manifest.get("datasetHash") or ""),
        "configFingerprint": str(manifest.get("configFingerprint") or ""),
        "manifestHash": sha256_file(manifest_path),
        "samplesHash": sha256_file(samples_path),
        "referencePolicyVersion": REFERENCE_POLICY_VERSION,
        "policyRejectedReferenceCount": policy_rejected_count,
    }, policy_rejected_by_sample


def _load_items(
    input_path: Path,
    config: MemorySemanticConfig,
    reference_rows: Mapping[str, Mapping[str, Any]] | None = None,
) -> tuple[str, list[dict[str, Any]], dict[str, Any] | None, set[str]]:
    if config.input_kind == "calibration":
        root = json.loads(input_path.read_text(encoding="utf-8"))
        validate_calibration_fixture(root)
        items = [
            {
                **dict(item),
                "datasetId": root["datasetId"],
                "sourceSampleId": str(item["id"]),
                "sourceGroupId": str(item["id"]),
                "split": "calibration",
                "role": f"{item['target']}-calibration",
            }
            for item in root["examples"]
            if item["target"] in config.targets
        ]
        if config.max_samples is not None:
            items = items[: config.max_samples]
        return str(root["datasetId"]), items, dict(root["review"]), {
            str(item["sourceSampleId"]) for item in items
        }

    rows = _read_jsonl(input_path)
    if config.splits:
        rows = [row for row in rows if str(row.get("split") or "") in config.splits]
    if config.max_samples is not None:
        rows = rows[: config.max_samples]
    if reference_rows is not None:
        selected_ids = {str(row.get("sampleId") or "") for row in rows}
        missing = sorted(selected_ids - set(reference_rows))
        if missing:
            raise ValueError(f"reference cluster run is missing selected source sample IDs: {missing[:3]}")
        mismatched = [
            str(row.get("sampleId") or "")
            for row in rows
            if _source_input_hash(row)
            != reference_rows[str(row.get("sampleId") or "")]["metadata"]["sourceInputHash"]
        ]
        if mismatched:
            raise ValueError(f"reference cluster source input mismatch: {mismatched[:3]}")
    items = [
        item
        for row in rows
        for item in _full_export_items(
            row,
            reference_rows.get(str(row.get("sampleId") or "")) if reference_rows is not None else None,
        )
        if item["target"] in config.targets
    ]
    if not items:
        raise ValueError("full memory export produced no semantic judgment items")
    dataset_ids = {str(item["datasetId"]) for item in items}
    if len(dataset_ids) != 1:
        raise ValueError("full memory export contains multiple dataset IDs")
    return dataset_ids.pop(), items, None, {str(row["sampleId"]) for row in rows}


def _full_export_items(
    row: Mapping[str, Any],
    reference_row: Mapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    outputs = row.get("moduleOutputs")
    if not isinstance(outputs, Mapping):
        raise ValueError(f"full memory export row lacks moduleOutputs: {row.get('sampleId')}")
    summary = outputs.get("l1Summary")
    extraction = outputs.get("l3Extraction")
    provider = outputs.get("provider")
    if not isinstance(summary, Mapping) or not isinstance(extraction, Mapping) or not isinstance(provider, Mapping):
        raise ValueError(f"full memory export row has incomplete moduleOutputs: {row.get('sampleId')}")
    turns = [
        {"id": f"turn-{index}", "speaker": str(turn.get("speaker") or "unknown"), "text": str(turn.get("text") or "")}
        for index, turn in enumerate(row.get("turns") or [], 1)
        if str(turn.get("text") or "").strip()
    ]
    if not turns:
        raise ValueError(f"full memory export row has no source turns: {row.get('sampleId')}")
    base = {
        "datasetId": str(row.get("datasetId") or ""),
        "sourceSampleId": str(row.get("sampleId") or ""),
        "sourceGroupId": str(row.get("sourceGroupId") or ""),
        "split": str(row.get("split") or ""),
        "sourceTurns": turns,
        "expectedLabel": None,
        "expectedRationale": None,
        "sourceModelProvenance": dict(provider),
    }
    synopsis = str(summary.get("synopsis") or "").strip()
    if not synopsis:
        raise ValueError(f"full memory export row has blank synopsis: {row.get('sampleId')}")
    items = [
        base
        | {
            "id": f"{base['sourceSampleId']}:l2:{index}",
            "target": "l2",
            "role": "l2-summary",
            "claim": claim,
        }
        for index, claim in enumerate(_split_claims(synopsis), 1)
    ]
    memories = extraction.get("memories")
    if not isinstance(memories, list):
        raise ValueError(f"full memory export row has invalid l3 memories: {row.get('sampleId')}")
    for index, memory in enumerate(memories, 1):
        content = str(memory.get("content") or "").strip() if isinstance(memory, Mapping) else ""
        if content:
            items.append(
                base
                | {
                    "id": f"{base['sourceSampleId']}:l3:{index}",
                    "target": "l3",
                    "role": "l3-extracted",
                    "claim": content,
                    "memoryType": str(memory.get("type") or "") if isinstance(memory, Mapping) else "",
                    "memoryTags": list(memory.get("tags") or []) if isinstance(memory, Mapping) else [],
                }
            )
    if reference_row is not None:
        metadata = reference_row.get("metadata")
        clusters = metadata.get("referenceClusters") if isinstance(metadata, Mapping) else None
        if not isinstance(clusters, list) or metadata.get("completed") is not True:
            raise ValueError(f"reference cluster row is incomplete: {row.get('sampleId')}")
        for index, cluster in enumerate(clusters, 1):
            if not isinstance(cluster, Mapping):
                raise ValueError(f"reference cluster row has invalid cluster: {row.get('sampleId')}")
            fact = str(cluster.get("canonicalFact") or "").strip()
            cluster_id = str(cluster.get("clusterId") or "").strip()
            if not fact or not cluster_id:
                raise ValueError(f"reference cluster row has blank fact or ID: {row.get('sampleId')}")
            items.append(
                base
                | {
                    "id": f"{base['sourceSampleId']}:l3ref:{index}",
                    "target": "l3",
                    "role": "l3-reference",
                    "claim": fact,
                    "referenceClusterId": cluster_id,
                    "memoryType": str(cluster.get("memoryType") or ""),
                }
            )
    return items


def _source_input_hash(row: Mapping[str, Any]) -> str:
    turns = [
        {
            "id": f"turn-{index}",
            "speaker": str(turn.get("speaker") or "unknown"),
            "text": str(turn.get("text") or "").strip(),
        }
        for index, turn in enumerate(row.get("turns") or [], 1)
        if str(turn.get("text") or "").strip()
    ]
    if not turns:
        raise ValueError(f"memory source input has no turns: {row.get('sampleId')}")
    return _canonical_source_input_hash(
        sample_id=str(row.get("sampleId") or ""),
        dataset_id=str(row.get("datasetId") or ""),
        source_group_id=str(row.get("sourceGroupId") or ""),
        split=str(row.get("split") or ""),
        turns=turns,
    )


def _reference_sample_source_input_hash(row: Mapping[str, Any]) -> str:
    metadata = row.get("metadata")
    contexts = row.get("retrievedContexts")
    if not isinstance(metadata, Mapping) or not isinstance(contexts, list) or not contexts:
        raise ValueError(f"reference cluster row lacks source inputs: {row.get('sampleId')}")
    turns = [
        {
            "id": str(context.get("id") or ""),
            "speaker": str(context.get("sourceId") or "unknown"),
            "text": str(context.get("text") or "").strip(),
        }
        for context in contexts
    ]
    return _canonical_source_input_hash(
        sample_id=str(row.get("sampleId") or ""),
        dataset_id=str(row.get("datasetId") or ""),
        source_group_id=str(metadata.get("sourceGroupId") or ""),
        split=str(row.get("split") or ""),
        turns=turns,
    )


def _canonical_source_input_hash(
    *,
    sample_id: str,
    dataset_id: str,
    source_group_id: str,
    split: str,
    turns: Sequence[Mapping[str, str]],
) -> str:
    payload = {
        "sampleId": sample_id,
        "datasetId": dataset_id,
        "sourceGroupId": source_group_id,
        "split": split,
        "turns": [dict(turn) for turn in turns],
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _reference_policy_rejection(
    cluster: Mapping[str, Any],
    source_turns: Sequence[Mapping[str, str]],
) -> str | None:
    evidence_ids = {str(value) for value in cluster.get("evidenceTurnIds") or []}
    evidence = [turn for turn in source_turns if str(turn.get("id") or "") in evidence_ids]
    if not evidence:
        return "missing_policy_evidence"
    fact = str(cluster.get("canonicalFact") or "")
    if _OTHER_PERSON_REFERENCE.search(fact):
        return "about_other_person"
    if any(_is_conditional_or_hypothetical(str(turn.get("text") or "")) for turn in evidence):
        return "conditional_or_hypothetical_evidence"
    if all(_is_information_need(str(turn.get("text") or "")) for turn in evidence):
        return "evidence_only_information_need"
    return None


_OTHER_PERSON_REFERENCE = re.compile(
    r"\b(?:user(?:'s)?\s+)?(?:sons?|daughters?|children|parents?|siblings?|partner|spouse|"
    r"relative|friend|colleague|coworker|manager|employee)s?\b",
    re.IGNORECASE,
)
_INFORMATION_NEED_START = re.compile(
    r"^\s*(?:who|what|when|where|why|how|which|can|could|would|should|is|are|do|does|did|"
    r"tell\s+me|explain|show\s+me|give\s+me)\b",
    re.IGNORECASE,
)
_CONDITIONAL_START = re.compile(r"^\s*(?:if|suppose|assuming|imagine|hypothetically)\b", re.IGNORECASE)


def _is_information_need(text: str) -> bool:
    return bool(text.strip().endswith("?") or _INFORMATION_NEED_START.search(text))


def _is_conditional_or_hypothetical(text: str) -> bool:
    return bool(_CONDITIONAL_START.search(text))


def _call_judge(item: Mapping[str, Any], config: MemorySemanticConfig) -> Mapping[str, Any]:
    from openai import OpenAI

    api_key = config.judge_api_key or _provider_api_key(config.judge_provider)
    if not api_key:
        raise ValueError(f"missing API key for semantic memory judge provider: {config.judge_provider}")
    client_args: dict[str, Any] = {"api_key": api_key}
    base_url = _clean_base_url(config.judge_base_url or _provider_base_url(config.judge_provider))
    if base_url:
        client_args["base_url"] = base_url
    client = OpenAI(**client_args)
    response = client.chat.completions.create(
        model=config.judge_model,
        temperature=0,
        response_format={"type": "json_object"},
        messages=[
            {
                "role": "system",
                "content": (
                    "Judge one memory claim only from the cited source turns. Use the supplied four-label rubric. "
                    f"{JUDGE_GUIDANCE} "
                    "Return JSON with exactly label, rationale, usefulnessScore, userSpecificity, temporalStability, "
                    "subjectActionObjectCoverage. Scores are numbers from 0 to 1. userSpecificity and "
                    "temporalStability are booleans. subjectActionObjectCoverage measures whether the claim preserves "
                    "the source's required subject, action or relation, object, and material qualifiers. For L3, "
                    "usefulnessScore measures whether a supported, user-specific, temporally stable memory would help "
                    "a future turn; transient or generic claims score low. For L2, usefulnessScore measures whether "
                    "the supported summary claim preserves decision-relevant conversation context."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "target": item["target"],
                        "claim": item["claim"],
                        "sourceTurns": item["sourceTurns"],
                        "rubric": RUBRIC,
                        "judgeGuidance": JUDGE_GUIDANCE,
                    },
                    ensure_ascii=False,
                ),
            },
        ],
    )
    content = str(response.choices[0].message.content or "").strip()
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content)
    return json.loads(content)


def _validate_judgment(value: Mapping[str, Any]) -> dict[str, Any]:
    required = {
        "label",
        "rationale",
        "usefulnessScore",
        "userSpecificity",
        "temporalStability",
        "subjectActionObjectCoverage",
    }
    if set(value) != required:
        raise ValueError(f"semantic judge output must contain exactly {sorted(required)}")
    label = str(value["label"])
    rationale = str(value["rationale"]).strip()
    usefulness = value["usefulnessScore"]
    coverage = value["subjectActionObjectCoverage"]
    if label not in LABELS or not rationale:
        raise ValueError("semantic judge returned invalid label or rationale")
    if not isinstance(usefulness, (int, float)) or isinstance(usefulness, bool) or not 0 <= usefulness <= 1:
        raise ValueError("semantic judge usefulnessScore must be between 0 and 1")
    if not isinstance(coverage, (int, float)) or isinstance(coverage, bool) or not 0 <= coverage <= 1:
        raise ValueError("semantic judge subjectActionObjectCoverage must be between 0 and 1")
    if not isinstance(value["userSpecificity"], bool) or not isinstance(value["temporalStability"], bool):
        raise ValueError("semantic judge specificity/stability fields must be booleans")
    return {
        "label": label,
        "rationale": rationale,
        "usefulnessScore": float(usefulness),
        "userSpecificity": value["userSpecificity"],
        "temporalStability": value["temporalStability"],
        "subjectActionObjectCoverage": float(coverage),
    }


def _sample(
    item: Mapping[str, Any],
    judgments: Sequence[Mapping[str, Any]],
    attempt_failures: Sequence[Mapping[str, Any]],
    config: MemorySemanticConfig,
) -> dict[str, Any]:
    turns = list(item["sourceTurns"])
    label = judgments[0]["label"] if judgments else None
    return {
        "sampleId": str(item["id"]),
        "datasetId": str(item["datasetId"]),
        "split": str(item["split"]),
        "userInput": str(item["claim"]),
        "reference": item.get("expectedLabel"),
        "retrievedContexts": [{"id": turn["id"], "text": turn["text"], "sourceId": turn.get("speaker")} for turn in turns],
        "referenceContextIds": [str(turn["id"]) for turn in turns],
        "metadata": {
            "sourceSampleId": item["sourceSampleId"],
            "sourceGroupId": item["sourceGroupId"],
            "target": item["target"],
            "role": item.get("role"),
            "claim": item["claim"],
            "referenceClusterId": item.get("referenceClusterId"),
            "memoryType": item.get("memoryType"),
            "memoryTags": item.get("memoryTags"),
            "expectedLabel": item.get("expectedLabel"),
            "expectedRationale": item.get("expectedRationale"),
            "judgeLabel": label,
            "judgments": list(judgments),
            "judgeAttemptFailures": list(attempt_failures),
            "judgeProvenance": {
                "provider": config.judge_provider,
                "model": config.judge_model,
                "temperature": 0.0,
            },
            "sourceModelProvenance": item.get("sourceModelProvenance"),
        },
    }


def _aggregate(samples: Sequence[Mapping[str, Any]], config: MemorySemanticConfig) -> dict[str, Any]:
    complete = [sample for sample in samples if len(sample["metadata"]["judgments"]) == config.repeats]
    first = [sample["metadata"]["judgments"][0] for sample in complete]
    labels = [judgment["label"] for judgment in first]
    l2 = [
        sample
        for sample in complete
        if sample["metadata"]["target"] == "l2" and sample["metadata"].get("role") != "l3-reference"
    ]
    l3 = [
        sample
        for sample in complete
        if sample["metadata"]["target"] == "l3" and sample["metadata"].get("role") != "l3-reference"
    ]
    l3_references = [
        sample
        for sample in complete
        if sample["metadata"].get("role") == "l3-reference"
    ]
    expected_calibration = [sample for sample in samples if sample["metadata"].get("expectedLabel")]
    return {
        "memory.semanticItemCount": float(len(samples)),
        "memory.semanticCompletedItemCount": float(len(complete)),
        "memory.semanticCompletionRate": len(complete) / len(samples) if samples else None,
        "memory.semanticL2SupportRate": _label_rate(l2, "supported"),
        "memory.semanticL2ContradictionRate": _label_rate(l2, "contradicted"),
        "memory.semanticL3SupportRate": _label_rate(l3, "supported"),
        "memory.semanticL3ReferenceSupportRate": _label_rate(l3_references, "supported"),
        "memory.semanticL3Usefulness": _mean(
            sample["metadata"]["judgments"][0]["usefulnessScore"] for sample in l3
        ),
        "memory.semanticL3SubjectActionObjectCoverage": _mean(
            sample["metadata"]["judgments"][0]["subjectActionObjectCoverage"] for sample in l3
        ),
        "memory.semanticL3UserSpecificityRate": _mean(
            1.0 if sample["metadata"]["judgments"][0]["userSpecificity"] else 0.0 for sample in l3
        ),
        "memory.semanticL3TemporalStabilityRate": _mean(
            1.0 if sample["metadata"]["judgments"][0]["temporalStability"] else 0.0 for sample in l3
        ),
        "memory.semanticJudgeRepeatAgreement": (
            _mean(
                1.0
                if len(sample["metadata"]["judgments"]) == config.repeats
                and len({judgment["label"] for judgment in sample["metadata"]["judgments"]}) == 1
                else 0.0
                for sample in samples
            )
            if config.repeats >= 2
            else None
        ),
        "memory.semanticCalibrationAccuracy": _mean(
            1.0
            if len(sample["metadata"]["judgments"]) == config.repeats
            and sample["metadata"]["judgments"][0]["label"] == sample["metadata"]["expectedLabel"]
            else 0.0
            for sample in expected_calibration
        ),
        "memory.semanticLabelCounts": dict(Counter(labels)),
    }


def _l3_reference_metrics(
    samples: Sequence[Mapping[str, Any]],
    config: MemorySemanticConfig,
    embedder: Embedder,
    *,
    reference_enabled: bool,
    match_judge: MatchJudge,
) -> tuple[dict[str, float | None], list[dict[str, Any]]]:
    metric_defaults: dict[str, float | None] = {
        "memory.semanticL3ExtractionPrecision": None,
        "memory.semanticL3ExtractionRecall": None,
        "memory.semanticL3ExtractionF1": None,
        "memory.semanticL3MatchedCount": 0.0,
        "memory.semanticL3ExtractedCount": 0.0,
        "memory.semanticL3ReferenceCount": 0.0,
        "memory.semanticL3MatchedTypeAccuracy": None,
    }
    actual = [sample for sample in samples if sample["metadata"].get("role") == "l3-extracted"]
    generated_references = [sample for sample in samples if sample["metadata"].get("role") == "l3-reference"]
    if not reference_enabled:
        return metric_defaults, []
    if not generated_references:
        failures = [
            _match_failure(
                sample,
                "memory.semanticL3ExtractionPrecision",
                "l3_extracted_memory_without_any_reference_cluster",
                {"embeddingThreshold": config.embedding_match_threshold},
            )
            for sample in actual
        ]
        return metric_defaults | {
            "memory.semanticL3ExtractionPrecision": 0.0 if actual else None,
            "memory.semanticL3ExtractedCount": float(len(actual)),
        }, failures
    references = [
        sample
        for sample in generated_references
        if _sample_label(sample, config) == "supported"
    ]
    failures = [
        _match_failure(
            sample,
            "memory.semanticL3ReferenceSupportRate",
            "l3_reference_cluster_not_judge_supported",
            {"judgeLabel": _sample_label(sample, config)},
        )
        for sample in generated_references
        if _sample_label(sample, config) != "supported"
    ]
    if not references:
        failures.extend(
            _match_failure(
                sample,
                "memory.semanticL3ExtractionPrecision",
                "l3_extracted_memory_without_supported_reference_cluster",
                {"embeddingThreshold": config.embedding_match_threshold},
            )
            for sample in actual
        )
        return metric_defaults | {
            "memory.semanticL3ExtractionPrecision": 0.0 if actual else None,
            "memory.semanticL3ExtractedCount": float(len(actual)),
        }, failures
    texts = list(dict.fromkeys(str(sample["metadata"]["claim"]) for sample in (*actual, *references)))
    try:
        vectors = embedder(texts, config)
        if len(vectors) != len(texts):
            raise ValueError("embedding result count mismatch")
        vector_by_text = {
            text: _validated_vector(vector)
            for text, vector in zip(texts, vectors, strict=True)
        }
    except Exception as exc:
        failure = {
            "sampleId": "memory-l3-reference-matching",
            "metric": "memory.semanticL3ExtractionF1",
            "errorCategory": "l3_reference_embedding_error",
            "sourceGroupId": "",
            "details": _sanitize_judge_error(exc),
            "judgeProvenance": {
                "provider": config.judge_provider,
                "model": config.judge_model,
                "temperature": 0.0,
            },
            "embeddingProvenance": {
                "model": config.embedding_model,
                "numGpu": config.embedding_num_gpu,
                "matchThreshold": config.embedding_match_threshold,
            },
        }
        return metric_defaults, [*failures, failure]

    candidates: list[tuple[float, str, str, Mapping[str, Any], Mapping[str, Any]]] = []
    nearest_by_actual: dict[str, tuple[str, float]] = {}
    nearest_by_reference: dict[str, tuple[str, float]] = {}
    for actual_sample in actual:
        actual_id = str(actual_sample["sampleId"])
        actual_label = _sample_label(actual_sample, config)
        for reference_sample in references:
            if (
                actual_sample["metadata"]["sourceSampleId"]
                != reference_sample["metadata"]["sourceSampleId"]
            ):
                continue
            reference_id = str(reference_sample["sampleId"])
            score = _cosine_similarity(
                vector_by_text[str(actual_sample["metadata"]["claim"])],
                vector_by_text[str(reference_sample["metadata"]["claim"])],
            )
            if actual_id not in nearest_by_actual or score > nearest_by_actual[actual_id][1]:
                nearest_by_actual[actual_id] = (reference_id, score)
            if reference_id not in nearest_by_reference or score > nearest_by_reference[reference_id][1]:
                nearest_by_reference[reference_id] = (actual_id, score)
            if (
                actual_label == "supported"
                and score >= config.embedding_match_threshold
            ):
                candidates.append((score, actual_id, reference_id, actual_sample, reference_sample))

    matches: list[tuple[float, Mapping[str, Any], Mapping[str, Any]]] = []
    matched_actual: set[str] = set()
    matched_reference: set[str] = set()
    for score, actual_id, reference_id, actual_sample, reference_sample in sorted(
        candidates,
        key=lambda row: (-row[0], row[1], row[2]),
    ):
        if actual_id in matched_actual or reference_id in matched_reference:
            continue
        confirmation, confirmation_failures = _confirm_l3_match(
            actual_sample,
            reference_sample,
            config,
            match_judge,
        )
        actual_sample["metadata"].setdefault("l3MatchConfirmations", []).append(
            {
                "referenceSampleId": reference_id,
                "embeddingSimilarity": score,
                **confirmation,
            }
        )
        failures.extend(confirmation_failures)
        if confirmation.get("equivalent") is not True:
            continue
        matched_actual.add(actual_id)
        matched_reference.add(reference_id)
        matches.append((score, actual_sample, reference_sample))

    for sample in actual:
        sample_id = str(sample["sampleId"])
        nearest = nearest_by_actual.get(sample_id)
        sample["metadata"]["l3ReferenceMatch"] = {
            "matched": sample_id in matched_actual,
            "nearestReferenceSampleId": nearest[0] if nearest else None,
            "nearestEmbeddingSimilarity": nearest[1] if nearest else None,
            "embeddingThreshold": config.embedding_match_threshold,
        }
    for sample in references:
        sample_id = str(sample["sampleId"])
        nearest = nearest_by_reference.get(sample_id)
        sample["metadata"]["l3ExtractedMatch"] = {
            "matched": sample_id in matched_reference,
            "nearestExtractedSampleId": nearest[0] if nearest else None,
            "nearestEmbeddingSimilarity": nearest[1] if nearest else None,
            "embeddingThreshold": config.embedding_match_threshold,
        }
    for score, actual_sample, reference_sample in matches:
        actual_sample["metadata"]["l3ReferenceMatch"]["referenceClusterId"] = reference_sample["metadata"][
            "referenceClusterId"
        ]
        actual_sample["metadata"]["l3ReferenceMatch"]["embeddingSimilarity"] = score
        reference_sample["metadata"]["l3ExtractedMatch"]["extractedSampleId"] = actual_sample["sampleId"]
        reference_sample["metadata"]["l3ExtractedMatch"]["embeddingSimilarity"] = score

    actual_count = len(actual)
    reference_count = len(references)
    matched_count = len(matches)
    matched_type_accuracy = _mean(
        1.0
        if str(actual_sample["metadata"].get("memoryType") or "")
        == str(reference_sample["metadata"].get("memoryType") or "")
        else 0.0
        for _score, actual_sample, reference_sample in matches
    )
    precision = matched_count / actual_count if actual_count else None
    recall = matched_count / reference_count if reference_count else None
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall > 0
        else 0.0 if precision == 0 or recall == 0 else None
    )
    failures.extend(
        _match_failure(
            sample,
            "memory.semanticL3ExtractionPrecision",
            "l3_extracted_memory_not_matched_to_reference_cluster",
            sample["metadata"]["l3ReferenceMatch"],
        )
        for sample in actual
        if str(sample["sampleId"]) not in matched_actual
    )
    failures.extend(
        _match_failure(
            sample,
            "memory.semanticL3ExtractionRecall",
            "l3_reference_cluster_not_matched_by_extraction",
            sample["metadata"]["l3ExtractedMatch"],
        )
        for sample in references
        if str(sample["sampleId"]) not in matched_reference
    )
    return {
        "memory.semanticL3ExtractionPrecision": precision,
        "memory.semanticL3ExtractionRecall": recall,
        "memory.semanticL3ExtractionF1": f1,
        "memory.semanticL3MatchedCount": float(matched_count),
        "memory.semanticL3ExtractedCount": float(actual_count),
        "memory.semanticL3ReferenceCount": float(reference_count),
        "memory.semanticL3MatchedTypeAccuracy": matched_type_accuracy,
    }, failures


def _confirm_l3_match(
    actual_sample: Mapping[str, Any],
    reference_sample: Mapping[str, Any],
    config: MemorySemanticConfig,
    match_judge: MatchJudge,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    attempt_failures: list[dict[str, Any]] = []
    for attempt in range(1, config.judge_max_attempts_per_repeat + 1):
        try:
            result = _validate_match_confirmation(match_judge(actual_sample, reference_sample, config))
            return result | {"attempt": attempt}, []
        except Exception as exc:
            attempt_failures.append({"attempt": attempt, "error": _sanitize_judge_error(exc)})
            if not _is_retryable_judge_error(exc):
                break
    failure = {
        "sampleId": str(actual_sample["sampleId"]),
        "metric": "memory.semanticL3ExtractionF1",
        "errorCategory": "l3_match_judge_error",
        "sourceGroupId": str(actual_sample["metadata"]["sourceGroupId"]),
        "details": {
            "referenceSampleId": str(reference_sample["sampleId"]),
            "attemptFailures": attempt_failures,
        },
        "sourceTurns": list(actual_sample["retrievedContexts"]),
        "claim": str(actual_sample["metadata"]["claim"]),
        "referenceClaim": str(reference_sample["metadata"]["claim"]),
        "judgeProvenance": dict(actual_sample["metadata"]["judgeProvenance"]),
    }
    return {
        "equivalent": False,
        "rationale": "Pairwise match confirmation failed closed.",
        "attempt": None,
    }, [failure]


def _call_match_judge(
    actual_sample: Mapping[str, Any],
    reference_sample: Mapping[str, Any],
    config: MemorySemanticConfig,
) -> Mapping[str, Any]:
    from openai import OpenAI

    api_key = config.judge_api_key or _provider_api_key(config.judge_provider)
    if not api_key:
        raise ValueError(f"missing API key for semantic memory match judge provider: {config.judge_provider}")
    client_args: dict[str, Any] = {"api_key": api_key}
    base_url = _clean_base_url(config.judge_base_url or _provider_base_url(config.judge_provider))
    if base_url:
        client_args["base_url"] = base_url
    client = OpenAI(**client_args)
    response = client.chat.completions.create(
        model=config.judge_model,
        temperature=0,
        response_format={"type": "json_object"},
        messages=[
            {
                "role": "system",
                "content": (
                    "Decide whether an extracted user-memory claim and a reference-cluster claim express the same "
                    "atomic memory. Equivalent means they can replace one another without changing subject, status, "
                    "scope, condition, time, certainty, or decision state. For example, considering an action is not "
                    "equivalent to deciding to take it. Return JSON with exactly equivalent and rationale."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "extractedClaim": actual_sample["metadata"]["claim"],
                        "referenceClaim": reference_sample["metadata"]["claim"],
                    },
                    ensure_ascii=False,
                ),
            },
        ],
    )
    content = str(response.choices[0].message.content or "").strip()
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content)
    return json.loads(content)


def _validate_match_confirmation(value: Mapping[str, Any]) -> dict[str, Any]:
    if set(value) != {"equivalent", "rationale"}:
        raise ValueError("L3 match judge output must contain exactly equivalent and rationale")
    if not isinstance(value["equivalent"], bool) or not str(value["rationale"]).strip():
        raise ValueError("L3 match judge returned invalid equivalence or rationale")
    return {"equivalent": value["equivalent"], "rationale": str(value["rationale"]).strip()}


def _sample_label(sample: Mapping[str, Any], config: MemorySemanticConfig) -> str | None:
    judgments = sample["metadata"].get("judgments") or []
    return str(judgments[0]["label"]) if len(judgments) == config.repeats else None


def _embed_texts(texts: Sequence[str], config: MemorySemanticConfig) -> list[list[float]]:
    from chatagent_eval.ragas_runner import _ollama_embed_batch

    base_url = _clean_base_url(
        config.embedding_base_url
        or os.getenv("CHATAGENT_EVAL_RAGAS_EMBEDDING_BASE_URL")
        or os.getenv("CHATAGENT_RAG_EMBEDDING_BASE_URL")
        or "http://127.0.0.1:11434"
    )
    if not base_url:
        raise ValueError("missing embedding base URL")
    result: list[list[float]] = []
    for start in range(0, len(texts), 64):
        result.extend(
            _ollama_embed_batch(
                base_url,
                config.embedding_model,
                texts[start : start + 64],
                num_gpu=config.embedding_num_gpu,
            )
        )
    return result


def _validated_vector(value: Sequence[float]) -> list[float]:
    vector = [float(item) for item in value]
    if not vector or any(not math.isfinite(item) for item in vector):
        raise ValueError("embedding result contains an empty or non-finite vector")
    return vector


def _cosine_similarity(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right):
        raise ValueError("embedding vector dimension mismatch")
    denominator = math.sqrt(sum(value * value for value in left)) * math.sqrt(
        sum(value * value for value in right)
    )
    if denominator == 0:
        raise ValueError("embedding vector has zero norm")
    return sum(a * b for a, b in zip(left, right, strict=True)) / denominator


def _match_failure(
    sample: Mapping[str, Any],
    metric: str,
    category: str,
    details: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "sampleId": str(sample["sampleId"]),
        "metric": metric,
        "errorCategory": category,
        "sourceGroupId": str(sample["metadata"]["sourceGroupId"]),
        "details": dict(details),
        "sourceTurns": list(sample["retrievedContexts"]),
        "claim": str(sample["metadata"]["claim"]),
        "judgeProvenance": dict(sample["metadata"]["judgeProvenance"]),
    }


def _label_rate(samples: Sequence[Mapping[str, Any]], label: str) -> float | None:
    if not samples:
        return None
    return sum(sample["metadata"]["judgments"][0]["label"] == label for sample in samples) / len(samples)


def _mean(values: Sequence[float] | Any) -> float | None:
    materialized = [float(value) for value in values if value is not None]
    return sum(materialized) / len(materialized) if materialized else None


def _failure(
    item: Mapping[str, Any],
    metric: str,
    category: str,
    details: Any,
    config: MemorySemanticConfig,
) -> dict[str, Any]:
    return {
        "sampleId": str(item["id"]),
        "metric": metric,
        "errorCategory": category,
        "sourceGroupId": str(item["sourceGroupId"]),
        "details": details,
        "sourceTurns": list(item["sourceTurns"]),
        "claim": str(item["claim"]),
        "judgeProvenance": {
            "provider": config.judge_provider,
            "model": config.judge_model,
            "temperature": 0.0,
        },
    }


def _provider_api_key(provider: str) -> str | None:
    normalized = provider.lower()
    if normalized == "deepseek":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY") or os.getenv("CHATAGENT_DEEPSEEK_API_KEY")
    return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY")


def _provider_base_url(provider: str) -> str | None:
    normalized = provider.lower()
    if normalized == "deepseek":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or os.getenv("CHATAGENT_DEEPSEEK_BASE_URL")
    return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL")


def _clean_base_url(value: str | None) -> str | None:
    return value.strip().rstrip(";/") if value and value.strip() else None


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _checkpoint_paths(output_root: Path, run_id: str) -> tuple[Path, Path]:
    checkpoint_dir = output_root.resolve() / f".{run_id}.checkpoint"
    return checkpoint_dir, checkpoint_dir / "items.jsonl"


def _load_or_create_checkpoint(
    *,
    checkpoint_dir: Path,
    checkpoint_rows: Path,
    run_id: str,
    config_fingerprint_value: str,
    dataset_hash: str,
    item_ids: Sequence[str],
) -> list[dict[str, Any]]:
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_config = checkpoint_dir / "config.json"
    item_ids_hash = hashlib.sha256(
        json.dumps(list(item_ids), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    expected = {
        "runId": run_id,
        "configFingerprint": config_fingerprint_value,
        "datasetHash": dataset_hash,
        "itemCount": len(item_ids),
        "itemIdsHash": f"sha256:{item_ids_hash}",
    }
    if checkpoint_config.exists():
        actual = json.loads(checkpoint_config.read_text(encoding="utf-8"))
        if actual != expected:
            raise ValueError(f"memory semantic checkpoint config mismatch for run id: {run_id}")
    else:
        checkpoint_config.write_text(
            json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    if not checkpoint_rows.exists():
        return []
    allowed_ids = set(item_ids)
    seen: set[str] = set()
    rows: list[dict[str, Any]] = []
    for line in checkpoint_rows.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if set(row) != {"itemId", "sample", "failures"}:
            raise ValueError("memory semantic checkpoint row has an invalid envelope")
        item_id = str(row["itemId"])
        if item_id not in allowed_ids or item_id in seen:
            raise ValueError(f"memory semantic checkpoint has unknown or duplicate item id: {item_id}")
        if not isinstance(row["sample"], Mapping) or not isinstance(row["failures"], list):
            raise ValueError(f"memory semantic checkpoint has invalid payload for item id: {item_id}")
        seen.add(item_id)
        rows.append(dict(row))
    return rows


def _append_checkpoint_row(path: Path, row: Mapping[str, Any]) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(dict(row), ensure_ascii=False, sort_keys=True) + "\n")


def _remove_checkpoint(checkpoint_dir: Path, checkpoint_rows: Path) -> None:
    with suppress(FileNotFoundError):
        checkpoint_rows.unlink()
    with suppress(FileNotFoundError):
        (checkpoint_dir / "config.json").unlink()
    with suppress(FileNotFoundError, OSError):
        checkpoint_dir.rmdir()


def _sanitize_judge_error(exc: Exception) -> str:
    message = str(exc)
    message = re.sub(r"(?i)(api[_ ]?key\s*[:=]\s*)[^,}\s]+", r"\1[redacted]", message)
    message = re.sub(r"(?i)(authorization\s*[:=]\s*)[^,}\s]+", r"\1[redacted]", message)
    return message


def _is_retryable_judge_error(exc: Exception) -> bool:
    message = str(exc).lower()
    return not any(token in message for token in ("401", "authentication", "missing api key"))


def _split_claims(value: str) -> list[str]:
    claims = [
        claim.strip()
        for claim in re.split(r"(?<=[.!?])\s+|\n+", value)
        if claim.strip()
    ]
    return claims or [value.strip()]
