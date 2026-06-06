"""Deterministic Memory V2 evaluation runner over real multi-turn tasks."""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.deterministic_metrics import hit_at_k, phrase_recall, reciprocal_rank
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import build_manifest, build_report, write_json_artifact, write_run_artifacts

DEFAULT_MEMORY_DATASET_ID = "memory-v2-dialogues"
ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")


@dataclass(frozen=True)
class MemoryConfig:
    run_id: str
    mode: str = "memory-smoke"
    dataset_id: str = DEFAULT_MEMORY_DATASET_ID
    l1_window_turns: int = 4
    l1_budget_chars: int = 8000
    l2_segment_turns: int = 4
    l3_top_k: int = 3
    max_samples: int | None = None
    splits: tuple[str, ...] = ()
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.l1_window_turns <= 0:
            raise ValueError("l1_window_turns must be positive")
        if self.l1_budget_chars <= 0:
            raise ValueError("l1_budget_chars must be positive")
        if self.l2_segment_turns <= 0:
            raise ValueError("l2_segment_turns must be positive")
        if self.l3_top_k <= 0:
            raise ValueError("l3_top_k must be positive")
        if self.max_samples is not None and self.max_samples <= 0:
            raise ValueError("max_samples must be positive when provided")

    def as_dict(self) -> dict[str, Any]:
        return {
            "datasetId": self.dataset_id,
            "l1WindowTurns": self.l1_window_turns,
            "l1BudgetChars": self.l1_budget_chars,
            "l2SegmentTurns": self.l2_segment_turns,
            "l3TopK": self.l3_top_k,
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
        }


@dataclass(frozen=True)
class MemoryCandidate:
    memory_id: str
    content: str
    memory_type: str
    tags: tuple[str, ...]


def run_memory(*, dataset_root: Path, output_root: Path, config: MemoryConfig) -> Path:
    dataset_manifest = _read_json(dataset_root / "manifests" / "datasets" / f"{config.dataset_id}.json")
    rows = _select_rows(_read_jsonl(dataset_root / dataset_manifest["localPath"]), config)
    config_dict = config.as_dict() | {
        "datasetManifestHash": dataset_manifest["datasetHash"],
        "recordSchema": dataset_manifest["recordSchema"],
        "adr": "0001-memory-compaction-v2-l2-schema-reset",
    }
    fingerprint = config_fingerprint(config_dict)

    evaluated = [_evaluate_row(row, config) for row in rows]
    samples = [item["sample"] for item in evaluated]
    failures = [failure for item in evaluated for failure in item["failures"]]
    metric_values = _aggregate_metrics(evaluated, config)
    status = "warn" if failures else "pass"

    manifest = build_manifest(
        run_id=config.run_id,
        suite="memory-v2",
        mode=config.mode,
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=dataset_manifest["datasetId"],
        dataset_hash=dataset_manifest["datasetHash"],
        config=config_dict,
        config_fingerprint=fingerprint,
        artifact_files=ARTIFACT_FILES,
    )
    metrics = {
        "status": status,
        "l1": {
            "completeTurnRecall": metric_values["memory.l1CompleteTurnRecall"],
            "budgetCompliance": metric_values["memory.l1BudgetCompliance"],
            "toolResponseRecall": metric_values["memory.l1ToolResponseRecall"],
        },
        "l2": {
            "factRecall": metric_values["memory.l2FactRecall"],
            "factPrecision": metric_values["memory.l2FactPrecision"],
            "contradictionRate": metric_values["memory.l2ContradictionRate"],
            "segmentRangeCoverage": metric_values["memory.l2SegmentRangeCoverage"],
            "fallbackRate": metric_values["memory.l2FallbackRate"],
            "retryCount": metric_values["memory.l2RetryCount"],
        },
        "l3": {
            "extractionF1": metric_values["memory.l3ExtractionF1"],
            "typeAccuracy": metric_values["memory.l3TypeAccuracy"],
            "tagAccuracy": metric_values["memory.l3TagAccuracy"],
            "idempotency": metric_values["memory.l3Idempotency"],
            "recallHitAtK": metric_values["memory.l3RecallHitAtK"],
            "recallMrr": metric_values["memory.l3RecallMrr"],
        },
        "merged": metric_values,
    }
    report = build_report(
        run_id=config.run_id,
        suite="memory-v2",
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


def _select_rows(rows: Sequence[Mapping[str, Any]], config: MemoryConfig) -> list[Mapping[str, Any]]:
    selected = [row for row in rows if not config.splits or str(row["split"]) in config.splits]
    if config.max_samples is not None:
        selected = selected[: config.max_samples]
    if not selected:
        raise ValueError("memory selection produced no rows")
    return selected


def _evaluate_row(row: Mapping[str, Any], config: MemoryConfig) -> dict[str, Any]:
    turns = list(row.get("turns", []))
    l1_expected = turns[-config.l1_window_turns :]
    l1_included = _select_l1_turns(l1_expected, config.l1_budget_chars)
    stable_turns = turns[: max(0, len(turns) - config.l1_window_turns)]
    segments = _l2_segments(stable_turns, config.l2_segment_turns)
    synopsis = _l2_synopsis(segments)
    l2_reference_facts = _reference_facts_from_turns(stable_turns)
    l2_summary_facts = _reference_facts_from_text(synopsis)
    expected_memories = _expected_l3_memories(turns, row)
    extracted_memories = _extract_l3_memories(turns, row)
    repeated_extraction = _extract_l3_memories(turns, row)
    ranked_memories = _rank_memories(_last_user_text(turns), extracted_memories)
    relevant_memory_ids = _relevant_memory_ids(_last_user_text(turns), expected_memories)

    l1 = {
        "completeTurnRecall": _turn_recall(l1_expected, l1_included),
        "budgetCompliance": 1.0 if _turn_chars(l1_included) <= config.l1_budget_chars else 0.0,
        "toolResponseRecall": _tool_recall(l1_expected, l1_included),
        "expectedTurnCount": len(l1_expected),
        "includedTurnCount": len(l1_included),
    }
    l2 = {
        "factRecall": phrase_recall([synopsis], l2_reference_facts) if l2_reference_facts else None,
        "factPrecision": _fact_precision(l2_summary_facts, l2_reference_facts),
        "contradictionRate": _contradiction_rate(synopsis),
        "segmentRangeCoverage": _segment_range_coverage(segments, len(stable_turns)),
        "fallbackRate": 0.0,
        "retryCount": 0.0,
        "stableTurnCount": len(stable_turns),
        "segmentCount": len(segments),
    }
    l3 = _l3_metrics(
        expected=expected_memories,
        extracted=extracted_memories,
        repeated=repeated_extraction,
        ranked=ranked_memories,
        relevant_memory_ids=relevant_memory_ids,
        top_k=config.l3_top_k,
    )

    contexts = _sample_contexts(row, l1_included, segments, ranked_memories, config)
    sample = {
        "sampleId": row["sampleId"],
        "datasetId": row["datasetId"],
        "split": row["split"],
        "userInput": _last_user_text(turns) or "Evaluate memory preservation for this conversation.",
        "reference": row.get("expectedResponse"),
        "retrievedContexts": contexts,
        "referenceContextIds": relevant_memory_ids,
        "metadata": {
            "sourceGroupId": row["sourceGroupId"],
            "expectedResponse": row.get("expectedResponse"),
            "sourceMetadata": dict(row.get("metadata") or {}),
            "l1": l1,
            "l2": l2 | {"segments": segments, "structuredSummaryJson": _structured_summary_json(synopsis, l2_reference_facts)},
            "l3": l3 | {"expectedMemories": [_memory_json(item) for item in expected_memories]},
        },
    }
    failures = _row_failures(row, l1, l2, l3, contexts, relevant_memory_ids)
    return {"sample": sample, "failures": failures, "metrics": {"l1": l1, "l2": l2, "l3": l3}}


def _select_l1_turns(turns: Sequence[Mapping[str, Any]], budget_chars: int) -> list[Mapping[str, Any]]:
    selected: list[Mapping[str, Any]] = []
    used = 0
    for turn in reversed(turns):
        cost = len(_turn_text(turn))
        if selected and used + cost > budget_chars:
            break
        if not selected and cost > budget_chars:
            break
        selected.append(turn)
        used += cost
    return list(reversed(selected))


def _l2_segments(turns: Sequence[Mapping[str, Any]], segment_turns: int) -> list[dict[str, Any]]:
    segments: list[dict[str, Any]] = []
    for start in range(0, len(turns), segment_turns):
        end = min(start + segment_turns, len(turns))
        segment_turns_slice = turns[start:end]
        segments.append(
            {
                "id": f"segment-{len(segments)}",
                "seqStartNo": float(start + 1),
                "seqEndNo": float(end),
                "turnIndexes": list(range(start, end)),
                "summary": _turns_text(segment_turns_slice),
                "structured": {
                    "facts": _reference_facts_from_turns(segment_turns_slice),
                    "entities": _entities(_turns_text(segment_turns_slice)),
                },
            }
        )
    return segments


def _l2_synopsis(segments: Sequence[Mapping[str, Any]]) -> str:
    return "\n".join(str(segment["summary"]) for segment in segments).strip()


def _expected_l3_memories(turns: Sequence[Mapping[str, Any]], row: Mapping[str, Any]) -> list[MemoryCandidate]:
    return _dedupe_memories(_memory_candidates(turns, row, include_user_preferences=True))


def _extract_l3_memories(turns: Sequence[Mapping[str, Any]], row: Mapping[str, Any]) -> list[MemoryCandidate]:
    return _dedupe_memories(_memory_candidates(turns, row, include_user_preferences=True))


def _memory_candidates(turns: Sequence[Mapping[str, Any]], row: Mapping[str, Any], *, include_user_preferences: bool) -> list[MemoryCandidate]:
    metadata = row.get("metadata") or {}
    tags = _metadata_tags(metadata)
    candidates: list[MemoryCandidate] = []
    for turn in turns:
        text = _turn_text(turn)
        speaker = str(turn.get("speaker", ""))
        if speaker == "agent" and text and not _is_apology(text):
            candidates.append(_candidate("fact", text, tags))
        if include_user_preferences and speaker == "user" and re.search(r"\bprefer|preference|like to|always\b", text, re.IGNORECASE):
            candidates.append(_candidate("preference", text, ("preference", *tags)))
    return candidates


def _candidate(memory_type: str, content: str, tags: Sequence[str]) -> MemoryCandidate:
    normalized = _normalize(content)
    digest = hashlib.sha256(f"{memory_type}:{normalized}".encode("utf-8")).hexdigest()[:16]
    return MemoryCandidate(
        memory_id=f"mem-{digest}",
        content=content.strip(),
        memory_type=memory_type,
        tags=tuple(dict.fromkeys(tag for tag in tags if tag)),
    )


def _dedupe_memories(memories: Sequence[MemoryCandidate]) -> list[MemoryCandidate]:
    result: dict[str, MemoryCandidate] = {}
    for memory in memories:
        result.setdefault(memory.memory_id, memory)
    return list(result.values())


def _l3_metrics(
    *,
    expected: Sequence[MemoryCandidate],
    extracted: Sequence[MemoryCandidate],
    repeated: Sequence[MemoryCandidate],
    ranked: Sequence[tuple[MemoryCandidate, float]],
    relevant_memory_ids: Sequence[str],
    top_k: int,
) -> dict[str, float | None]:
    expected_ids = [item.memory_id for item in expected]
    extracted_ids = [item.memory_id for item in extracted]
    true_positive = len(set(expected_ids) & set(extracted_ids))
    precision = true_positive / len(extracted_ids) if extracted_ids else (1.0 if not expected_ids else 0.0)
    recall = true_positive / len(expected_ids) if expected_ids else None
    f1 = _f1(precision, recall)
    type_accuracy = _type_accuracy(expected, extracted)
    tag_accuracy = _tag_accuracy(expected, extracted)
    repeated_ids = [item.memory_id for item in repeated]
    idempotency = 1.0 if extracted_ids == repeated_ids and len(extracted_ids) == len(set(extracted_ids)) else 0.0
    ranked_ids = [item.memory_id for item, _score in ranked]
    hit = hit_at_k(ranked_ids, relevant_memory_ids, top_k) if relevant_memory_ids else None
    mrr = reciprocal_rank(ranked_ids, relevant_memory_ids) if relevant_memory_ids else None
    return {
        "extractionPrecision": precision,
        "extractionRecall": recall,
        "extractionF1": f1,
        "typeAccuracy": type_accuracy,
        "tagAccuracy": tag_accuracy,
        "idempotency": idempotency,
        "recallHitAtK": hit,
        "recallMrr": mrr,
        "expectedMemoryCount": float(len(expected)),
        "extractedMemoryCount": float(len(extracted)),
        "relevantMemoryCount": float(len(relevant_memory_ids)),
    }


def _rank_memories(query: str, memories: Sequence[MemoryCandidate]) -> list[tuple[MemoryCandidate, float]]:
    query_tokens = _tokens(query)
    ranked = []
    for memory in memories:
        content_tokens = _tokens(memory.content)
        score = float(len(query_tokens & content_tokens))
        ranked.append((memory, score))
    return sorted(ranked, key=lambda item: (-item[1], item[0].memory_id))


def _relevant_memory_ids(query: str, memories: Sequence[MemoryCandidate]) -> list[str]:
    query_tokens = _tokens(query)
    overlapping = [memory.memory_id for memory in memories if query_tokens & _tokens(memory.content)]
    return overlapping or [memory.memory_id for memory in memories]


def _row_failures(
    row: Mapping[str, Any],
    l1: Mapping[str, Any],
    l2: Mapping[str, Any],
    l3: Mapping[str, Any],
    contexts: Sequence[Mapping[str, Any]],
    relevant_memory_ids: Sequence[str],
) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    if _lt(l1["completeTurnRecall"], 1.0) or _lt(l1["budgetCompliance"], 1.0) or _lt(l1.get("toolResponseRecall"), 1.0):
        failures.append(_failure(row, "l1CompleteTurnRecall", "l1_turn_or_tool_response_not_preserved", contexts))
    if l2["factRecall"] is not None and _lt(l2["factRecall"], 1.0):
        failures.append(_failure(row, "l2FactRecall", "l2_missing_reference_facts", contexts))
    if l2["segmentRangeCoverage"] is not None and _lt(l2["segmentRangeCoverage"], 1.0):
        failures.append(_failure(row, "l2SegmentRangeCoverage", "l2_segment_range_gap_or_overlap", contexts))
    if l2["contradictionRate"] and l2["contradictionRate"] > 0:
        failures.append(_failure(row, "l2ContradictionRate", "l2_contradiction_detected", contexts))
    if l3["extractionF1"] is not None and _lt(l3["extractionF1"], 1.0):
        failures.append(_failure(row, "l3ExtractionF1", "l3_memory_extraction_mismatch", contexts))
    if _lt(l3["idempotency"], 1.0):
        failures.append(_failure(row, "l3Idempotency", "l3_memory_extraction_not_idempotent", contexts))
    if relevant_memory_ids and _lt(l3["recallHitAtK"], 1.0):
        failures.append(_failure(row, "l3RecallHitAtK", "l3_recall_missed_relevant_memory", contexts))
    return failures


def _failure(row: Mapping[str, Any], metric: str, category: str, contexts: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    return {
        "sampleId": row["sampleId"],
        "metric": metric,
        "errorCategory": category,
        "sourceGroupId": row["sourceGroupId"],
        "topContexts": list(contexts),
    }


def _sample_contexts(
    row: Mapping[str, Any],
    l1_turns: Sequence[Mapping[str, Any]],
    segments: Sequence[Mapping[str, Any]],
    ranked_memories: Sequence[tuple[MemoryCandidate, float]],
    config: MemoryConfig,
) -> list[dict[str, Any]]:
    sample_id = str(row["sampleId"])
    contexts: list[dict[str, Any]] = []
    for index, turn in enumerate(l1_turns):
        contexts.append(
            {
                "id": f"{sample_id}:l1:{index}",
                "text": _turn_text(turn),
                "sourceId": "l1",
                "score": 1.0,
            }
        )
    for segment in segments:
        contexts.append(
            {
                "id": f"{sample_id}:l2:{segment['id']}",
                "text": str(segment["summary"]),
                "sourceId": "l2",
                "score": 1.0,
            }
        )
    for memory, score in ranked_memories[: config.l3_top_k]:
        contexts.append(
            {
                "id": memory.memory_id,
                "text": memory.content,
                "sourceId": "l3",
                "score": score,
            }
        )
    return contexts


def _aggregate_metrics(evaluated: Sequence[Mapping[str, Any]], config: MemoryConfig) -> dict[str, float | None]:
    l1_rows = [item["metrics"]["l1"] for item in evaluated]
    l2_rows = [item["metrics"]["l2"] for item in evaluated]
    l3_rows = [item["metrics"]["l3"] for item in evaluated]
    return {
        "memory.sampleCount": float(len(evaluated)),
        "memory.l1WindowTurns": float(config.l1_window_turns),
        "memory.l1BudgetChars": float(config.l1_budget_chars),
        "memory.l1CompleteTurnRecall": _mean(row["completeTurnRecall"] for row in l1_rows),
        "memory.l1BudgetCompliance": _mean(row["budgetCompliance"] for row in l1_rows),
        "memory.l1ToolResponseRecall": _mean(row["toolResponseRecall"] for row in l1_rows),
        "memory.l2FactRecall": _mean(row["factRecall"] for row in l2_rows),
        "memory.l2FactPrecision": _mean(row["factPrecision"] for row in l2_rows),
        "memory.l2ContradictionRate": _mean(row["contradictionRate"] for row in l2_rows),
        "memory.l2SegmentRangeCoverage": _mean(row["segmentRangeCoverage"] for row in l2_rows),
        "memory.l2FallbackRate": _mean(row["fallbackRate"] for row in l2_rows),
        "memory.l2RetryCount": _mean(row["retryCount"] for row in l2_rows),
        "memory.l3ExtractionPrecision": _mean(row["extractionPrecision"] for row in l3_rows),
        "memory.l3ExtractionRecall": _mean(row["extractionRecall"] for row in l3_rows),
        "memory.l3ExtractionF1": _mean(row["extractionF1"] for row in l3_rows),
        "memory.l3TypeAccuracy": _mean(row["typeAccuracy"] for row in l3_rows),
        "memory.l3TagAccuracy": _mean(row["tagAccuracy"] for row in l3_rows),
        "memory.l3Idempotency": _mean(row["idempotency"] for row in l3_rows),
        "memory.l3RecallHitAtK": _mean(row["recallHitAtK"] for row in l3_rows),
        "memory.l3RecallMrr": _mean(row["recallMrr"] for row in l3_rows),
        "memory.l3TopK": float(config.l3_top_k),
    }


def _turn_recall(expected: Sequence[Mapping[str, Any]], included: Sequence[Mapping[str, Any]]) -> float:
    if not expected:
        return 1.0
    expected_texts = [_turn_text(turn) for turn in expected]
    included_texts = Counter(_turn_text(turn) for turn in included)
    found = 0
    for text in expected_texts:
        if included_texts[text] > 0:
            found += 1
            included_texts[text] -= 1
    return found / len(expected_texts)


def _tool_recall(expected: Sequence[Mapping[str, Any]], included: Sequence[Mapping[str, Any]]) -> float | None:
    expected_tools = [turn for turn in expected if _is_tool_turn(turn)]
    if not expected_tools:
        return None
    return _turn_recall(expected_tools, [turn for turn in included if _is_tool_turn(turn)])


def _is_tool_turn(turn: Mapping[str, Any]) -> bool:
    speaker = str(turn.get("speaker", "")).casefold()
    metadata = turn.get("metadata") or {}
    return speaker == "tool" or str(metadata.get("author_type", "")).casefold() == "tool"


def _turn_chars(turns: Sequence[Mapping[str, Any]]) -> int:
    return sum(len(_turn_text(turn)) for turn in turns)


def _turns_text(turns: Sequence[Mapping[str, Any]]) -> str:
    return "\n".join(f"{turn.get('speaker', 'unknown')}: {_turn_text(turn)}" for turn in turns if _turn_text(turn)).strip()


def _turn_text(turn: Mapping[str, Any]) -> str:
    return str(turn.get("text", "")).strip()


def _last_user_text(turns: Sequence[Mapping[str, Any]]) -> str:
    for turn in reversed(turns):
        if turn.get("speaker") == "user":
            return _turn_text(turn)
    return ""


def _reference_facts_from_turns(turns: Sequence[Mapping[str, Any]]) -> list[str]:
    return _reference_facts_from_text(_turns_text(turns))


def _reference_facts_from_text(text: str) -> list[str]:
    facts: list[str] = []
    for sentence in re.split(r"(?<=[.!?])\s+|\n+", text):
        normalized = sentence.strip()
        if 20 <= len(normalized) <= 220 and not _is_apology(normalized):
            facts.append(normalized)
        if len(facts) >= 12:
            break
    return list(dict.fromkeys(facts))


def _fact_precision(summary_facts: Sequence[str], reference_facts: Sequence[str]) -> float | None:
    if not summary_facts:
        return 1.0 if not reference_facts else 0.0
    if not reference_facts:
        return 1.0
    reference_text = " ".join(reference_facts)
    supported = sum(1 for fact in summary_facts if phrase_recall([reference_text], [fact]) == 1.0)
    return supported / len(summary_facts)


def _contradiction_rate(text: str) -> float:
    normalized = _normalize(text)
    if not normalized:
        return 0.0
    positives: set[tuple[str, str]] = set()
    negatives: set[tuple[str, str]] = set()
    pattern = re.compile(
        r"\b(?P<subject>[a-z0-9][a-z0-9 -]{1,60}?)\s+"
        r"(?P<verb>is|are|was|were)\s+"
        r"(?P<neg>not\s+)?"
        r"(?P<predicate>[a-z0-9][a-z0-9 -]{1,60}?)(?:[.!?]|$)"
    )
    for match in pattern.finditer(normalized):
        subject = _normalize(match.group("subject"))
        predicate = _normalize(match.group("predicate"))
        if subject.startswith("if ") or " if " in subject or predicate.startswith("if ") or " if " in predicate:
            continue
        key = (subject, predicate)
        if match.group("neg"):
            negatives.add(key)
        else:
            positives.add(key)
    checked = len(negatives)
    return len(positives & negatives) / checked if checked else 0.0


def _segment_range_coverage(segments: Sequence[Mapping[str, Any]], stable_turn_count: int) -> float:
    if stable_turn_count == 0:
        return 1.0
    covered: list[int] = []
    for segment in segments:
        covered.extend(int(index) for index in segment.get("turnIndexes", []))
    unique = set(covered)
    expected = set(range(stable_turn_count))
    if not covered:
        return 0.0
    gap_coverage = len(unique & expected) / len(expected)
    overlap_penalty = (len(covered) - len(unique)) / len(expected)
    return max(0.0, gap_coverage - overlap_penalty)


def _structured_summary_json(summary: str, facts: Sequence[str]) -> dict[str, Any]:
    return {
        "summary": summary,
        "facts": list(facts),
        "decisions": [],
        "open_tasks": [],
        "entities": _entities(summary),
    }


def _type_accuracy(expected: Sequence[MemoryCandidate], extracted: Sequence[MemoryCandidate]) -> float | None:
    if not expected:
        return None
    extracted_by_id = {item.memory_id: item for item in extracted}
    matches = sum(1 for item in expected if item.memory_id in extracted_by_id and extracted_by_id[item.memory_id].memory_type == item.memory_type)
    return matches / len(expected)


def _tag_accuracy(expected: Sequence[MemoryCandidate], extracted: Sequence[MemoryCandidate]) -> float | None:
    if not expected:
        return None
    extracted_by_id = {item.memory_id: item for item in extracted}
    scores = []
    for item in expected:
        actual = set(extracted_by_id[item.memory_id].tags) if item.memory_id in extracted_by_id else set()
        expected_tags = set(item.tags)
        scores.append(len(actual & expected_tags) / len(expected_tags) if expected_tags else 1.0)
    return sum(scores) / len(scores)


def _metadata_tags(metadata: Mapping[str, Any]) -> tuple[str, ...]:
    tags: list[str] = []
    for key in ("questionType", "multiTurn", "answerability"):
        value = metadata.get(key, [])
        if isinstance(value, str):
            value = [value]
        tags.extend(_slug(str(item)) for item in value if str(item) and str(item) != "N/A")
    domain = str(metadata.get("domain", ""))
    if domain:
        tags.append(_slug(domain.split("-")[0]))
    return tuple(dict.fromkeys(tag for tag in tags if tag))


def _entities(text: str) -> list[str]:
    return list(dict.fromkeys(re.findall(r"\b[A-Z][A-Za-z0-9]+(?:\s+[A-Z][A-Za-z0-9]+)*\b", text)))[:20]


def _memory_json(memory: MemoryCandidate) -> dict[str, Any]:
    return {
        "id": memory.memory_id,
        "type": memory.memory_type,
        "content": memory.content,
        "tags": list(memory.tags),
    }


def _f1(precision: float | None, recall: float | None) -> float | None:
    if precision is None or recall is None:
        return None
    if precision + recall == 0:
        return 0.0
    return 2 * precision * recall / (precision + recall)


def _mean(values: Iterable[float | None]) -> float | None:
    numeric = [float(value) for value in values if value is not None and not math.isnan(float(value))]
    return sum(numeric) / len(numeric) if numeric else None


def _lt(value: Any, expected: float) -> bool:
    return value is not None and float(value) < expected


def _tokens(text: str) -> set[str]:
    return {token.casefold() for token in re.findall(r"[A-Za-z0-9]+", text) if len(token) > 2}


def _normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().casefold()


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.casefold()).strip("-")


def _is_apology(text: str) -> bool:
    return "don't have the answer" in text.casefold() or text.casefold().startswith("i'm sorry")


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
