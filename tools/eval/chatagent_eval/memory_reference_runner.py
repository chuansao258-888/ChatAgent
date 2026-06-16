"""Independent reference-cluster generation for semantic L3 memory evaluation."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Callable, Mapping, Sequence
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.datasets import sha256_file
from chatagent_eval.memory_semantic_runner import (
    _clean_base_url,
    _is_retryable_judge_error,
    _provider_api_key,
    _provider_base_url,
    _reference_policy_rejection,
    _sanitize_judge_error,
    _source_input_hash,
)
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import SAFE_RUN_ID, build_manifest, build_report, write_json_artifact, write_run_artifacts

MEMORY_TYPES = ("fact", "preference", "decision", "constraint", "profile")
ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")
PROMPT_VERSION = "memory-reference-clusters-v2"
TRANSIENT_REFERENCE_PATTERNS = (
    re.compile(r"\bwants?\s+to\s+(?:know|learn|ask)\b", re.IGNORECASE),
    re.compile(r"\bwonders?\b", re.IGNORECASE),
    re.compile(r"\bconsider(?:s|ing)?\b", re.IGNORECASE),
    re.compile(r"\basks?\s+(?:about|whether|which|what|how|why|when|where)\b", re.IGNORECASE),
)
Extractor = Callable[[Mapping[str, Any], "MemoryReferenceConfig"], Mapping[str, Any]]


@dataclass(frozen=True)
class MemoryReferenceConfig:
    run_id: str
    judge_provider: str = "deepseek"
    judge_model: str = "deepseek-chat"
    judge_base_url: str | None = None
    judge_api_key: str | None = None
    judge_max_attempts: int = 3
    max_samples: int | None = None
    splits: tuple[str, ...] = ("calibration", "development")
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if not SAFE_RUN_ID.fullmatch(self.run_id):
            raise ValueError(f"unsafe run id: {self.run_id}")
        if not self.judge_provider.strip() or not self.judge_model.strip():
            raise ValueError("judge provider and model must be non-empty")
        if self.judge_max_attempts <= 0:
            raise ValueError("judge_max_attempts must be positive")
        if self.max_samples is not None and self.max_samples <= 0:
            raise ValueError("max_samples must be positive when provided")
        if not self.splits:
            raise ValueError("splits must be explicit so sealed holdout is not accessed accidentally")

    def as_dict(self) -> dict[str, Any]:
        return {
            "judgeProvider": self.judge_provider,
            "judgeModel": self.judge_model,
            "judgeTemperature": 0.0,
            "judgeMaxAttempts": self.judge_max_attempts,
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
            "promptVersion": PROMPT_VERSION,
        }


def run_memory_reference_clusters(
    *,
    input_path: Path,
    output_root: Path,
    config: MemoryReferenceConfig,
    extractor: Extractor | None = None,
) -> Path:
    rows = _load_rows(input_path, config)
    dataset_ids = {str(row.get("datasetId") or "") for row in rows}
    if len(dataset_ids) != 1 or "" in dataset_ids:
        raise ValueError("memory reference input must contain exactly one non-empty datasetId")
    dataset_id = dataset_ids.pop()
    dataset_hash = sha256_file(input_path)
    source_export = _source_export_provenance(input_path, dataset_id)
    config_dict = config.as_dict() | {
        "inputFile": input_path.name,
        "sourceExport": source_export,
        "sourceInputSetHash": _source_input_set_hash(rows),
    }
    fingerprint = config_fingerprint(config_dict)
    checkpoint_dir, checkpoint_rows = _checkpoint_paths(output_root, config.run_id)
    completed = _load_or_create_checkpoint(
        checkpoint_dir=checkpoint_dir,
        checkpoint_rows=checkpoint_rows,
        run_id=config.run_id,
        config_fingerprint_value=fingerprint,
        dataset_hash=dataset_hash,
        sample_ids=[str(row["sampleId"]) for row in rows],
    )
    samples = [dict(row["sample"]) for row in completed]
    failures = [dict(failure) for row in completed for failure in row["failures"]]
    completed_ids = {str(row["sampleId"]) for row in completed}
    extractor_fn = extractor or _call_extractor

    for row in rows:
        sample_id = str(row["sampleId"])
        if sample_id in completed_ids:
            continue
        sample, row_failures = _extract_reference_row(row, config, extractor_fn)
        samples.append(sample)
        failures.extend(row_failures)
        _append_checkpoint_row(
            checkpoint_rows,
            {"sampleId": sample_id, "sample": sample, "failures": row_failures},
        )

    metrics = _aggregate(samples)
    status = "warn" if failures else "pass"
    manifest = build_manifest(
        run_id=config.run_id,
        suite="memory-reference-clusters",
        mode="full-export",
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=dataset_id,
        dataset_hash=dataset_hash,
        config=config_dict,
        config_fingerprint=fingerprint,
        models={"referenceJudge": f"{config.judge_provider}/{config.judge_model}"},
        artifact_files=ARTIFACT_FILES,
    )
    metric_root = {
        "status": status,
        "referenceClusters": {
            "rowCount": metrics["memory.referenceClusterRowCount"],
            "completedRowCount": metrics["memory.referenceClusterCompletedRowCount"],
            "completionRate": metrics["memory.referenceClusterCompletionRate"],
            "clusterCount": metrics["memory.referenceClusterCount"],
            "rowsWithNoClusters": metrics["memory.referenceClusterRowsWithNoClusters"],
            "rejectedClusterCount": metrics["memory.referenceClusterRejectedCount"],
        },
        "merged": metrics,
    }
    report = build_report(
        run_id=config.run_id,
        suite="memory-reference-clusters",
        mode="full-export",
        status=status,
        dataset_id=dataset_id,
        dataset_hash=dataset_hash,
        config_fingerprint=fingerprint,
        metrics=metrics,
        threshold_results=[],
    )
    run_dir = write_run_artifacts(output_root, manifest, metric_root, samples, failures)
    write_json_artifact(run_dir / "report.json", report)
    _remove_checkpoint(checkpoint_dir, checkpoint_rows)
    return run_dir


def _load_rows(input_path: Path, config: MemoryReferenceConfig) -> list[dict[str, Any]]:
    rows = [
        json.loads(line)
        for line in input_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    rows = [row for row in rows if str(row.get("split") or "") in config.splits]
    if config.max_samples is not None:
        rows = rows[: config.max_samples]
    if not rows:
        raise ValueError("memory reference input produced no rows for the requested splits")
    sample_ids = [str(row.get("sampleId") or "") for row in rows]
    if any(not sample_id for sample_id in sample_ids) or len(sample_ids) != len(set(sample_ids)):
        raise ValueError("memory reference input has missing or duplicate sample IDs")
    for row in rows:
        provider = (row.get("moduleOutputs") or {}).get("provider")
        if not isinstance(provider, Mapping) or any(
            not str(provider.get(key) or "").strip()
            for key in ("summaryModel", "extractorModel", "embeddingModel")
        ):
            raise ValueError(f"memory reference input lacks source-model provenance: {row.get('sampleId')}")
    return rows


def _source_export_provenance(input_path: Path, dataset_id: str) -> dict[str, str]:
    manifest_path = input_path.parent / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("memory reference input must have a sibling source export manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if str(manifest.get("datasetId") or "") != dataset_id:
        raise ValueError("memory reference source export manifest datasetId mismatch")
    run_id = str(manifest.get("runId") or "")
    source_dataset_hash = str(manifest.get("datasetHash") or "")
    if not run_id or not source_dataset_hash:
        raise ValueError("memory reference source export manifest lacks runId or datasetHash")
    return {
        "runId": run_id,
        "datasetHash": source_dataset_hash,
        "manifestHash": sha256_file(manifest_path),
    }


def _extract_reference_row(
    row: Mapping[str, Any],
    config: MemoryReferenceConfig,
    extractor: Extractor,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    turns = _source_turns(row)
    attempt_failures: list[dict[str, Any]] = []
    clusters: list[dict[str, Any]] | None = None
    rejections: list[dict[str, Any]] = []
    for attempt in range(1, config.judge_max_attempts + 1):
        try:
            clusters, rejections = _validate_extraction(extractor({"sourceTurns": turns}, config), row, turns)
            break
        except Exception as exc:
            attempt_failures.append({"attempt": attempt, "error": _sanitize_judge_error(exc)})
            if not _is_retryable_judge_error(exc):
                break
    failures: list[dict[str, Any]] = []
    if clusters is None:
        failures.append(
            {
                "sampleId": str(row["sampleId"]),
                "metric": "memory.referenceClusterCompletionRate",
                "errorCategory": "reference_cluster_judge_error",
                "sourceGroupId": str(row.get("sourceGroupId") or ""),
                "details": attempt_failures,
                "sourceTurns": turns,
                "judgeProvenance": _judge_provenance(config),
            }
        )
        clusters = []
    sample = {
        "sampleId": str(row["sampleId"]),
        "datasetId": str(row["datasetId"]),
        "split": str(row.get("split") or ""),
        "userInput": "Generate independent L3 reference fact clusters from cited source turns.",
        "reference": None,
        "retrievedContexts": [
            {"id": turn["id"], "text": turn["text"], "sourceId": turn["speaker"]}
            for turn in turns
        ],
        "referenceContextIds": [turn["id"] for turn in turns],
        "metadata": {
            "sourceSampleId": str(row["sampleId"]),
            "sourceGroupId": str(row.get("sourceGroupId") or ""),
            "referenceClusters": clusters,
            "rejectedReferenceClusters": rejections,
            "completed": not failures,
            "judgeAttemptFailures": attempt_failures,
            "judgeProvenance": _judge_provenance(config),
            "sourceModelProvenance": dict((row.get("moduleOutputs") or {}).get("provider") or {}),
            "sourceInputHash": _source_input_hash(row),
        },
    }
    return sample, failures


def _source_turns(row: Mapping[str, Any]) -> list[dict[str, str]]:
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
        raise ValueError(f"memory reference row has no source turns: {row.get('sampleId')}")
    return turns


def _source_input_set_hash(rows: Sequence[Mapping[str, Any]]) -> str:
    payload = [
        {"sampleId": str(row["sampleId"]), "sourceInputHash": _source_input_hash(row)}
        for row in rows
    ]
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _call_extractor(item: Mapping[str, Any], config: MemoryReferenceConfig) -> Mapping[str, Any]:
    from openai import OpenAI

    api_key = config.judge_api_key or _provider_api_key(config.judge_provider)
    if not api_key:
        raise ValueError(f"missing API key for memory reference judge provider: {config.judge_provider}")
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
                    "Create independent reference fact clusters for evaluating a long-term user-memory extractor. "
                    "Use only the cited source turns. Include only explicitly established, durable, user-specific "
                    "facts, preferences, completed decisions, constraints, or profile details that would be useful "
                    "in a future conversation. Each cluster must be one atomic fact; never combine separate facts "
                    "into one sentence. Exclude generic assistant advice, transient questions or information needs, "
                    "open considerations, contingent or tentative plans, external expectations, unsupported "
                    "inferences, and duplicate paraphrases. A user's question is not evidence of a preference. "
                    "Return JSON with exactly one key, clusters. clusters is a list of at most 10 objects with exactly "
                    "canonicalFact, evidenceTurnIds, memoryType, rationale. evidenceTurnIds must cite source-turn IDs. "
                    f"memoryType must be one of {list(MEMORY_TYPES)}. Return an empty list when no durable memory exists."
                ),
            },
            {
                "role": "user",
                "content": json.dumps({"sourceTurns": item["sourceTurns"]}, ensure_ascii=False),
            },
        ],
    )
    content = str(response.choices[0].message.content or "").strip()
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content)
    return json.loads(content)


def _validate_extraction(
    value: Mapping[str, Any],
    row: Mapping[str, Any],
    turns: Sequence[Mapping[str, str]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    if set(value) != {"clusters"} or not isinstance(value["clusters"], list):
        raise ValueError("reference cluster judge output must contain exactly a clusters list")
    raw_clusters = value["clusters"]
    if len(raw_clusters) > 10:
        raise ValueError("reference cluster judge returned more than 10 clusters")
    turn_ids = {turn["id"] for turn in turns}
    seen_facts: set[str] = set()
    clusters: list[dict[str, Any]] = []
    rejections: list[dict[str, Any]] = []
    for index, raw in enumerate(raw_clusters, 1):
        if not isinstance(raw, Mapping) or set(raw) != {
            "canonicalFact",
            "evidenceTurnIds",
            "memoryType",
            "rationale",
        }:
            raise ValueError("reference cluster has an invalid envelope")
        fact = str(raw["canonicalFact"]).strip()
        rationale = str(raw["rationale"]).strip()
        memory_type = str(raw["memoryType"]).strip()
        evidence_ids = raw["evidenceTurnIds"]
        normalized = _normalize(fact)
        rejection_reason = _cluster_rejection_reason(
            fact=fact,
            rationale=rationale,
            memory_type=memory_type,
            evidence_ids=evidence_ids,
            turn_ids=turn_ids,
            normalized=normalized,
            seen_facts=seen_facts,
            turns=turns,
            raw_cluster=raw,
        )
        if rejection_reason:
            rejections.append(
                {
                    "index": index,
                    "reason": rejection_reason,
                    "canonicalFact": fact,
                    "memoryType": memory_type,
                }
            )
            continue
        seen_facts.add(normalized)
        digest = hashlib.sha256(f"{row['sampleId']}:{normalized}".encode("utf-8")).hexdigest()[:16]
        clusters.append(
            {
                "clusterId": f"l3ref-{digest}",
                "canonicalFact": fact,
                "evidenceTurnIds": [str(evidence_id) for evidence_id in evidence_ids],
                "memoryType": memory_type,
                "rationale": rationale,
            }
        )
    return clusters, rejections


def _cluster_rejection_reason(
    *,
    fact: str,
    rationale: str,
    memory_type: str,
    evidence_ids: Any,
    turn_ids: set[str],
    normalized: str,
    seen_facts: set[str],
    turns: Sequence[Mapping[str, str]],
    raw_cluster: Mapping[str, Any],
) -> str | None:
    if not fact or not rationale:
        return "blank_fact_or_rationale"
    if memory_type not in MEMORY_TYPES:
        return "invalid_memory_type"
    if (
        not isinstance(evidence_ids, list)
        or not evidence_ids
        or any(str(evidence_id) not in turn_ids for evidence_id in evidence_ids)
    ):
        return "invalid_evidence_turn_ids"
    if normalized in seen_facts:
        return "duplicate_fact"
    if any(pattern.search(fact) for pattern in TRANSIENT_REFERENCE_PATTERNS):
        return "transient_information_need"
    policy_reason = _reference_policy_rejection(raw_cluster, turns)
    if policy_reason:
        return policy_reason
    return None


def _aggregate(samples: Sequence[Mapping[str, Any]]) -> dict[str, float]:
    completed = [sample for sample in samples if sample["metadata"]["completed"]]
    cluster_count = sum(len(sample["metadata"]["referenceClusters"]) for sample in completed)
    rows_with_no_clusters = sum(not sample["metadata"]["referenceClusters"] for sample in completed)
    rejected_count = sum(len(sample["metadata"]["rejectedReferenceClusters"]) for sample in completed)
    return {
        "memory.referenceClusterRowCount": float(len(samples)),
        "memory.referenceClusterCompletedRowCount": float(len(completed)),
        "memory.referenceClusterCompletionRate": len(completed) / len(samples) if samples else 0.0,
        "memory.referenceClusterCount": float(cluster_count),
        "memory.referenceClusterRowsWithNoClusters": float(rows_with_no_clusters),
        "memory.referenceClusterRejectedCount": float(rejected_count),
    }


def _judge_provenance(config: MemoryReferenceConfig) -> dict[str, Any]:
    return {"provider": config.judge_provider, "model": config.judge_model, "temperature": 0.0}


def _normalize(value: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+", value.lower()))


def _checkpoint_paths(output_root: Path, run_id: str) -> tuple[Path, Path]:
    checkpoint_dir = output_root.resolve() / f".{run_id}.checkpoint"
    return checkpoint_dir, checkpoint_dir / "rows.jsonl"


def _load_or_create_checkpoint(
    *,
    checkpoint_dir: Path,
    checkpoint_rows: Path,
    run_id: str,
    config_fingerprint_value: str,
    dataset_hash: str,
    sample_ids: Sequence[str],
) -> list[dict[str, Any]]:
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_config = checkpoint_dir / "config.json"
    ids_hash = hashlib.sha256(
        json.dumps(list(sample_ids), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    expected = {
        "runId": run_id,
        "configFingerprint": config_fingerprint_value,
        "datasetHash": dataset_hash,
        "sampleCount": len(sample_ids),
        "sampleIdsHash": f"sha256:{ids_hash}",
    }
    if checkpoint_config.exists():
        actual = json.loads(checkpoint_config.read_text(encoding="utf-8"))
        if actual != expected:
            raise ValueError(f"memory reference checkpoint config mismatch for run id: {run_id}")
    else:
        checkpoint_config.write_text(
            json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    if not checkpoint_rows.exists():
        return []
    allowed_ids = set(sample_ids)
    seen: set[str] = set()
    rows: list[dict[str, Any]] = []
    for line in checkpoint_rows.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if set(row) != {"sampleId", "sample", "failures"}:
            raise ValueError("memory reference checkpoint row has an invalid envelope")
        sample_id = str(row["sampleId"])
        if sample_id not in allowed_ids or sample_id in seen:
            raise ValueError(f"memory reference checkpoint has unknown or duplicate sample id: {sample_id}")
        if not isinstance(row["sample"], Mapping) or not isinstance(row["failures"], list):
            raise ValueError(f"memory reference checkpoint has invalid payload for sample id: {sample_id}")
        seen.add(sample_id)
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
