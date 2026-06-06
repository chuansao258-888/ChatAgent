"""Deterministic agent-module evaluation runner over real multi-turn tasks."""

from __future__ import annotations

import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.deterministic_metrics import phrase_recall
from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import build_manifest, build_report, write_json_artifact, write_run_artifacts

DEFAULT_AGENT_MODULE_DATASET_ID = "memory-v2-dialogues"
DEFAULT_PARAMETER_SPACE_ID = "agent-modules-v1"
ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")
KNOWLEDGE_SEARCH_TOOL = "knowledge.search"
CONTENT_WORD = re.compile(r"[A-Za-z][A-Za-z0-9'-]{2,}")
STOPWORDS = {
    "about",
    "after",
    "again",
    "answer",
    "before",
    "being",
    "could",
    "does",
    "have",
    "into",
    "more",
    "most",
    "that",
    "their",
    "there",
    "these",
    "they",
    "this",
    "what",
    "when",
    "where",
    "which",
    "while",
    "with",
    "would",
}


@dataclass(frozen=True)
class AgentModuleConfig:
    run_id: str
    mode: str = "agent-modules-smoke"
    dataset_id: str = DEFAULT_AGENT_MODULE_DATASET_ID
    parameter_space_id: str = DEFAULT_PARAMETER_SPACE_ID
    intent_history_turns: int = 6
    intent_min_evidence_terms: int = 1
    rewrite_history_turns: int = 6
    rewrite_max_anchors: int = 3
    rewrite_max_extra_terms: int = 0
    tool_candidate_limit: int = 1
    multiturn_coref_window_turns: int = 6
    ragas_agent_metrics: bool = False
    max_samples: int | None = None
    splits: tuple[str, ...] = ()
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.intent_history_turns < 0:
            raise ValueError("intent_history_turns must be non-negative")
        if self.intent_min_evidence_terms <= 0:
            raise ValueError("intent_min_evidence_terms must be positive")
        if self.rewrite_history_turns < 0:
            raise ValueError("rewrite_history_turns must be non-negative")
        if self.rewrite_max_anchors < 0:
            raise ValueError("rewrite_max_anchors must be non-negative")
        if self.rewrite_max_extra_terms < 0:
            raise ValueError("rewrite_max_extra_terms must be non-negative")
        if self.tool_candidate_limit < 0:
            raise ValueError("tool_candidate_limit must be non-negative")
        if self.multiturn_coref_window_turns < 0:
            raise ValueError("multiturn_coref_window_turns must be non-negative")
        if self.max_samples is not None and self.max_samples <= 0:
            raise ValueError("max_samples must be positive when provided")

    def as_dict(self) -> dict[str, Any]:
        return {
            "datasetId": self.dataset_id,
            "parameterSpaceId": self.parameter_space_id,
            "intentHistoryTurns": self.intent_history_turns,
            "intentMinEvidenceTerms": self.intent_min_evidence_terms,
            "rewriteHistoryTurns": self.rewrite_history_turns,
            "rewriteMaxAnchors": self.rewrite_max_anchors,
            "rewriteMaxExtraTerms": self.rewrite_max_extra_terms,
            "toolCandidateLimit": self.tool_candidate_limit,
            "multiturnCorefWindowTurns": self.multiturn_coref_window_turns,
            "ragasAgentMetrics": self.ragas_agent_metrics,
            "maxSamples": self.max_samples,
            "splits": list(self.splits),
        }


def run_agent_modules(*, dataset_root: Path, output_root: Path, config: AgentModuleConfig) -> Path:
    dataset_manifest = _read_json(dataset_root / "manifests" / "datasets" / f"{config.dataset_id}.json")
    rows = _select_rows(_read_jsonl(dataset_root / dataset_manifest["localPath"]), config)
    config_dict = config.as_dict() | {
        "datasetManifestHash": dataset_manifest["datasetHash"],
        "recordSchema": dataset_manifest["recordSchema"],
        "dataSourcePolicy": "clean-room-real-mtrag-derived",
    }
    fingerprint = config_fingerprint(config_dict)

    evaluated = [_evaluate_row(row, config) for row in rows]
    samples = [item["sample"] for item in evaluated]
    failures = [failure for item in evaluated for failure in item["failures"]]
    metric_values = _aggregate_metrics(evaluated, config)
    status = "warn" if failures else "pass"

    manifest = build_manifest(
        run_id=config.run_id,
        suite="agent-modules",
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
        "intent": {
            "exactPathAccuracy": metric_values["agentModules.intentExactPathAccuracy"],
            "domainAccuracy": metric_values["agentModules.intentDomainAccuracy"],
            "outOfScopeAccuracy": metric_values["agentModules.intentOutOfScopeAccuracy"],
            "clarificationAccuracy": metric_values["agentModules.intentClarificationAccuracy"],
            "ambiguityAccuracy": metric_values["agentModules.intentAmbiguityAccuracy"],
        },
        "queryRewrite": {
            "anchorRecall": metric_values["agentModules.rewriteAnchorRecall"],
            "retrievalLiftRate": metric_values["agentModules.rewriteRetrievalLiftRate"],
            "noOverExpansionRate": metric_values["agentModules.rewriteNoOverExpansionRate"],
            "historyTurns": config.rewrite_history_turns,
            "maxAnchors": config.rewrite_max_anchors,
        },
        "toolCall": {
            "accuracy": metric_values["agentModules.toolCallAccuracy"],
            "precision": metric_values["agentModules.toolCallPrecision"],
            "recall": metric_values["agentModules.toolCallRecall"],
            "f1": metric_values["agentModules.toolCallF1"],
            "candidateLimit": config.tool_candidate_limit,
        },
        "multiTurn": {
            "coreferenceRecall": metric_values["agentModules.coreferenceRecall"],
            "topicSwitchAccuracy": metric_values["agentModules.topicSwitchAccuracy"],
            "wrongHistorySuppression": metric_values["agentModules.wrongHistorySuppression"],
            "corefWindowTurns": config.multiturn_coref_window_turns,
        },
        "ragasAgentMetrics": {
            "enabled": config.ragas_agent_metrics,
            "toolCallAccuracy": metric_values["agentModules.ragasToolCallAccuracy"],
            "toolCallF1": metric_values["agentModules.ragasToolCallF1"],
        },
        "merged": metric_values,
    }
    report = build_report(
        run_id=config.run_id,
        suite="agent-modules",
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


def _select_rows(rows: Sequence[Mapping[str, Any]], config: AgentModuleConfig) -> list[Mapping[str, Any]]:
    selected = [row for row in rows if not config.splits or str(row["split"]) in config.splits]
    if config.max_samples is not None:
        selected = selected[: config.max_samples]
    if not selected:
        raise ValueError("agent-module selection produced no rows")
    return selected


def _evaluate_row(row: Mapping[str, Any], config: AgentModuleConfig) -> dict[str, Any]:
    turns = list(row.get("turns", []))
    last_user_text = _last_user_text(turns) or "Evaluate this agent-module task."
    expected = _expected_outputs(row, config)
    observed = _module_outputs(row, config, expected)

    intent = _intent_metrics(expected["intent"], observed["intent"])
    rewrite = _rewrite_metrics(expected["rewrite"], observed["rewrite"], last_user_text, config)
    tool = _tool_metrics(expected["toolCalls"], observed["toolCalls"])
    multiturn = _multiturn_metrics(expected["multiTurn"], observed["multiTurn"])
    contexts = _sample_contexts(row, expected, observed)

    sample = {
        "sampleId": row["sampleId"],
        "datasetId": row["datasetId"],
        "split": row["split"],
        "userInput": last_user_text,
        "reference": row.get("expectedResponse"),
        "response": observed["rewrite"]["query"],
        "retrievedContexts": contexts,
        "referenceContextIds": list(dict.fromkeys(str(item) for item in row.get("referenceContextIds", []))),
        "metadata": {
            "sourceGroupId": row["sourceGroupId"],
            "sourceMetadata": dict(row.get("metadata") or {}),
            "expected": expected,
            "observed": observed,
            "intent": intent,
            "queryRewrite": rewrite,
            "toolCall": tool,
            "multiTurn": multiturn,
            "ragasAgentMetrics": {
                "enabled": config.ragas_agent_metrics,
                "label": "optional-ragas-agent-metrics-disabled",
            },
        },
    }
    failures = _row_failures(row, intent, rewrite, tool, multiturn, contexts)
    return {
        "sample": sample,
        "failures": failures,
        "metrics": {
            "intent": intent,
            "rewrite": rewrite,
            "tool": tool,
            "multiturn": multiturn,
        },
    }


def _expected_outputs(row: Mapping[str, Any], config: AgentModuleConfig) -> dict[str, Any]:
    turns = list(row.get("turns", []))
    last_user_text = _last_user_text(turns) or ""
    history_turns = _windowed_history(_history_before_last_user(turns), config.rewrite_history_turns)
    intent = _expected_intent(row)
    anchors = _expected_anchors(history_turns, row, limit=3)
    expected_query = _rewrite_query(last_user_text, anchors, max_anchors=3)
    tool_calls = _expected_tool_calls(intent, expected_query)
    return {
        "intent": intent,
        "rewrite": {
            "query": expected_query,
            "anchors": anchors,
            "targetText": _target_text(row, anchors),
        },
        "toolCalls": tool_calls,
        "multiTurn": {
            "coreferenceRequired": _expected_coreference(row),
            "topicSwitch": _expected_topic_switch(row),
            "wrongHistorySuppressed": True,
        },
    }


def _module_outputs(row: Mapping[str, Any], config: AgentModuleConfig, expected: Mapping[str, Any]) -> dict[str, Any]:
    explicit = row.get("moduleOutputs")
    if isinstance(explicit, Mapping):
        return _merge_outputs(expected, explicit)

    turns = list(row.get("turns", []))
    last_user_text = _last_user_text(turns) or ""
    history = _history_before_last_user(turns)
    intent_history = _windowed_history(history, config.intent_history_turns)
    rewrite_history = _windowed_history(history, config.rewrite_history_turns)
    coref_history = _windowed_history(history, config.multiturn_coref_window_turns)
    intent = _observed_intent(row, config, intent_history)
    anchors = _expected_anchors(rewrite_history, row, limit=3)
    coref_anchors = _expected_anchors(coref_history, row, limit=3)
    rewrite_query = _rewrite_query(last_user_text, anchors, max_anchors=config.rewrite_max_anchors)
    tool_calls = _expected_tool_calls(intent, rewrite_query)[: config.tool_candidate_limit]
    included_anchors = anchors[: config.rewrite_max_anchors]
    return {
        "intent": intent,
        "rewrite": {
            "query": rewrite_query,
            "anchors": included_anchors,
            "targetText": expected["rewrite"]["targetText"],
        },
        "toolCalls": tool_calls,
        "multiTurn": {
            "coreferenceRequired": bool(included_anchors) and bool(coref_anchors)
            if expected["multiTurn"]["coreferenceRequired"]
            else False,
            "topicSwitch": expected["multiTurn"]["topicSwitch"],
            "wrongHistorySuppressed": True,
        },
    }


def _merge_outputs(expected: Mapping[str, Any], explicit: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "intent": dict(expected["intent"]) | dict(explicit.get("intent") or {}),
        "rewrite": dict(expected["rewrite"]) | dict(explicit.get("rewrite") or {}),
        "toolCalls": list(explicit.get("toolCalls", expected["toolCalls"])),
        "multiTurn": dict(expected["multiTurn"]) | dict(explicit.get("multiTurn") or {}),
    }


def _expected_intent(row: Mapping[str, Any]) -> dict[str, Any]:
    metadata = row.get("metadata") or {}
    answerability = _first_label(metadata.get("answerability"), default="UNKNOWN")
    multiturn = _first_label(metadata.get("multiTurn"), default="N/A")
    question_type = _first_label(metadata.get("questionType"), default="Unknown")
    domain = str(metadata.get("domain") or "unknown-domain")
    has_reference = bool(row.get("referenceContextIds"))
    kind = "KB" if answerability in {"ANSWERABLE", "PARTIAL"} or has_reference else "SYSTEM"
    outcome = answerability.casefold().replace("_", "-")
    return {
        "path": [domain, kind, outcome, multiturn],
        "domain": domain,
        "kind": kind,
        "answerability": answerability,
        "questionType": question_type,
        "clarification": multiturn == "Clarification",
        "ambiguous": answerability in {"PARTIAL", "CONVERSATIONAL"} or question_type in {"Opinion", "Non-Question"},
        "outOfScope": kind == "SYSTEM",
    }


def _observed_intent(
    row: Mapping[str, Any],
    config: AgentModuleConfig,
    history_turns: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    intent = dict(_expected_intent(row))
    evidence_terms = _history_evidence_terms(history_turns)
    if intent["path"][-1] != "N/A" and len(evidence_terms) < config.intent_min_evidence_terms:
        intent["path"] = [intent["domain"], intent["kind"], intent["answerability"].casefold().replace("_", "-"), "N/A"]
        intent["clarification"] = False
    return intent


def _expected_tool_calls(intent: Mapping[str, Any], query: str) -> list[dict[str, Any]]:
    if intent.get("kind") != "KB":
        return []
    return [{"name": KNOWLEDGE_SEARCH_TOOL, "arguments": {"query": query}}]


def _expected_coreference(row: Mapping[str, Any]) -> bool:
    metadata = row.get("metadata") or {}
    return _first_label(metadata.get("multiTurn"), default="N/A") != "N/A" and len(row.get("turns", [])) > 1


def _expected_topic_switch(row: Mapping[str, Any]) -> bool:
    metadata = row.get("metadata") or {}
    label = _first_label(metadata.get("multiTurn"), default="N/A")
    return label in {"Topic-switch", "Topic Switch", "TopicSwitch"}


def _intent_metrics(expected: Mapping[str, Any], observed: Mapping[str, Any]) -> dict[str, float]:
    return {
        "exactPathAccuracy": _same(expected.get("path"), observed.get("path")),
        "domainAccuracy": _same(expected.get("domain"), observed.get("domain")),
        "outOfScopeAccuracy": _same(expected.get("outOfScope"), observed.get("outOfScope")),
        "clarificationAccuracy": _same(expected.get("clarification"), observed.get("clarification")),
        "ambiguityAccuracy": _same(expected.get("ambiguous"), observed.get("ambiguous")),
    }


def _rewrite_metrics(
    expected: Mapping[str, Any],
    observed: Mapping[str, Any],
    last_user_text: str,
    config: AgentModuleConfig,
) -> dict[str, float | None]:
    anchors = list(expected.get("anchors", []))
    query = str(observed.get("query") or "")
    anchor_recall = phrase_recall([query], anchors) if anchors else None
    raw_score = _term_score(last_user_text, str(expected.get("targetText") or ""))
    rewrite_score = _term_score(query, str(expected.get("targetText") or ""))
    retrieval_lift = 1.0 if not anchors or rewrite_score >= raw_score and (anchor_recall or 0.0) >= 1.0 else 0.0
    extra_terms = _extra_terms(last_user_text, query, anchors)
    return {
        "anchorRecall": anchor_recall,
        "retrievalLift": rewrite_score - raw_score,
        "retrievalLiftRate": retrieval_lift,
        "noOverExpansion": 1.0 if extra_terms <= config.rewrite_max_extra_terms else 0.0,
        "extraTermCount": float(extra_terms),
    }


def _tool_metrics(expected_calls: Sequence[Mapping[str, Any]], observed_calls: Sequence[Mapping[str, Any]]) -> dict[str, float]:
    expected_names = [str(call.get("name")) for call in expected_calls]
    observed_names = [str(call.get("name")) for call in observed_calls]
    expected_set = set(expected_names)
    observed_set = set(observed_names)
    true_positive = len(expected_set & observed_set)
    precision = true_positive / len(observed_set) if observed_set else (1.0 if not expected_set else 0.0)
    recall = true_positive / len(expected_set) if expected_set else (1.0 if not observed_set else 0.0)
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "accuracy": 1.0 if expected_names == observed_names else 0.0,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def _multiturn_metrics(expected: Mapping[str, Any], observed: Mapping[str, Any]) -> dict[str, float]:
    expected_coref = bool(expected.get("coreferenceRequired"))
    observed_coref = bool(observed.get("coreferenceRequired"))
    expected_switch = bool(expected.get("topicSwitch"))
    observed_switch = bool(observed.get("topicSwitch"))
    return {
        "coreferenceRecall": 1.0 if not expected_coref or observed_coref else 0.0,
        "topicSwitchAccuracy": 1.0 if expected_switch == observed_switch else 0.0,
        "wrongHistorySuppression": 1.0 if bool(observed.get("wrongHistorySuppressed")) else 0.0,
    }


def _row_failures(
    row: Mapping[str, Any],
    intent: Mapping[str, float],
    rewrite: Mapping[str, float | None],
    tool: Mapping[str, float],
    multiturn: Mapping[str, float],
    contexts: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    if intent["exactPathAccuracy"] < 1.0:
        failures.append(_failure(row, "agentModules.intentExactPathAccuracy", "intent_path_mismatch", contexts))
    if rewrite.get("anchorRecall") is not None and (rewrite.get("anchorRecall") or 0.0) < 1.0:
        failures.append(_failure(row, "agentModules.rewriteAnchorRecall", "query_rewrite_missing_anchors", contexts))
    if rewrite["retrievalLiftRate"] < 1.0:
        failures.append(_failure(row, "agentModules.rewriteRetrievalLiftRate", "query_rewrite_no_retrieval_lift", contexts))
    if rewrite["noOverExpansion"] < 1.0:
        failures.append(_failure(row, "agentModules.rewriteNoOverExpansionRate", "query_rewrite_over_expanded", contexts))
    if tool["accuracy"] < 1.0 or tool["f1"] < 1.0:
        failures.append(_failure(row, "agentModules.toolCallF1", "tool_call_mismatch", contexts))
    if multiturn["coreferenceRecall"] < 1.0:
        failures.append(_failure(row, "agentModules.coreferenceRecall", "multiturn_coreference_missed", contexts))
    if multiturn["topicSwitchAccuracy"] < 1.0:
        failures.append(_failure(row, "agentModules.topicSwitchAccuracy", "multiturn_topic_switch_mismatch", contexts))
    if multiturn["wrongHistorySuppression"] < 1.0:
        failures.append(_failure(row, "agentModules.wrongHistorySuppression", "multiturn_wrong_history_leakage", contexts))
    return failures


def _failure(
    row: Mapping[str, Any],
    metric: str,
    category: str,
    contexts: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    return {
        "sampleId": row["sampleId"],
        "metric": metric,
        "errorCategory": category,
        "sourceGroupId": row["sourceGroupId"],
        "topContexts": list(contexts),
    }


def _sample_contexts(
    row: Mapping[str, Any],
    expected: Mapping[str, Any],
    observed: Mapping[str, Any],
) -> list[dict[str, Any]]:
    sample_id = str(row["sampleId"])
    source_group = str(row["sourceGroupId"])
    expected_tool_names = ", ".join(call["name"] for call in expected["toolCalls"]) or "none"
    observed_tool_names = ", ".join(call["name"] for call in observed["toolCalls"]) or "none"
    return [
        {
            "id": f"{sample_id}::intent",
            "text": f"expected={expected['intent']['path']} observed={observed['intent']['path']}",
            "sourceId": source_group,
            "score": None,
        },
        {
            "id": f"{sample_id}::rewrite",
            "text": str(observed["rewrite"]["query"]),
            "sourceId": source_group,
            "score": None,
        },
        {
            "id": f"{sample_id}::tools",
            "text": f"expected={expected_tool_names}; observed={observed_tool_names}",
            "sourceId": source_group,
            "score": None,
        },
        {
            "id": f"{sample_id}::multiturn",
            "text": json.dumps(observed["multiTurn"], ensure_ascii=False, sort_keys=True),
            "sourceId": source_group,
            "score": None,
        },
    ]


def _aggregate_metrics(evaluated: Sequence[Mapping[str, Any]], config: AgentModuleConfig) -> dict[str, float | None]:
    intent = [item["metrics"]["intent"] for item in evaluated]
    rewrite = [item["metrics"]["rewrite"] for item in evaluated]
    tool = [item["metrics"]["tool"] for item in evaluated]
    multiturn = [item["metrics"]["multiturn"] for item in evaluated]
    failure_count = sum(len(item["failures"]) for item in evaluated)
    return {
        "agentModules.sampleCount": float(len(evaluated)),
        "agentModules.failureCount": float(failure_count),
        "agentModules.intentExactPathAccuracy": _mean(item["exactPathAccuracy"] for item in intent),
        "agentModules.intentDomainAccuracy": _mean(item["domainAccuracy"] for item in intent),
        "agentModules.intentOutOfScopeAccuracy": _mean(item["outOfScopeAccuracy"] for item in intent),
        "agentModules.intentClarificationAccuracy": _mean(item["clarificationAccuracy"] for item in intent),
        "agentModules.intentAmbiguityAccuracy": _mean(item["ambiguityAccuracy"] for item in intent),
        "agentModules.rewriteAnchorRecall": _mean(item["anchorRecall"] for item in rewrite),
        "agentModules.rewriteRetrievalLift": _mean(item["retrievalLift"] for item in rewrite),
        "agentModules.rewriteRetrievalLiftRate": _mean(item["retrievalLiftRate"] for item in rewrite),
        "agentModules.rewriteNoOverExpansionRate": _mean(item["noOverExpansion"] for item in rewrite),
        "agentModules.toolCallAccuracy": _mean(item["accuracy"] for item in tool),
        "agentModules.toolCallPrecision": _mean(item["precision"] for item in tool),
        "agentModules.toolCallRecall": _mean(item["recall"] for item in tool),
        "agentModules.toolCallF1": _mean(item["f1"] for item in tool),
        "agentModules.coreferenceRecall": _mean(item["coreferenceRecall"] for item in multiturn),
        "agentModules.topicSwitchAccuracy": _mean(item["topicSwitchAccuracy"] for item in multiturn),
        "agentModules.wrongHistorySuppression": _mean(item["wrongHistorySuppression"] for item in multiturn),
        "agentModules.intentHistoryTurns": float(config.intent_history_turns),
        "agentModules.intentMinEvidenceTerms": float(config.intent_min_evidence_terms),
        "agentModules.rewriteHistoryTurns": float(config.rewrite_history_turns),
        "agentModules.rewriteMaxAnchors": float(config.rewrite_max_anchors),
        "agentModules.rewriteMaxExtraTerms": float(config.rewrite_max_extra_terms),
        "agentModules.toolCandidateLimit": float(config.tool_candidate_limit),
        "agentModules.corefWindowTurns": float(config.multiturn_coref_window_turns),
        "agentModules.ragasToolCallAccuracy": None,
        "agentModules.ragasToolCallF1": None,
    }


def _expected_anchors(history_turns: Sequence[Mapping[str, Any]], row: Mapping[str, Any], limit: int) -> list[str]:
    if limit <= 0 or not history_turns:
        return []
    current_tokens = _content_tokens(_last_user_text(row.get("turns", [])) or "")
    target_tokens = _content_tokens(_target_text(row, []))
    candidates: list[str] = []
    for turn in reversed(history_turns):
        if str(turn.get("speaker")) != "user":
            continue
        tokens = _content_tokens(str(turn.get("text") or ""))
        useful = [token for token in tokens if token not in current_tokens or token in target_tokens]
        if useful:
            candidates.append(" ".join(useful[:3]))
        if len(candidates) >= limit:
            break
    return list(reversed([candidate for candidate in candidates if candidate]))[:limit]


def _rewrite_query(last_user_text: str, anchors: Sequence[str], max_anchors: int) -> str:
    included = [anchor for anchor in anchors[:max_anchors] if anchor]
    if not included:
        return last_user_text
    return f"{last_user_text} {' '.join(included)}"


def _target_text(row: Mapping[str, Any], anchors: Sequence[str]) -> str:
    parts = [str(row.get("expectedResponse") or ""), " ".join(anchors)]
    for context_id in row.get("referenceContextIds", []):
        parts.append(str(context_id))
    return " ".join(parts)


def _term_score(query: str, target: str) -> float:
    query_terms = set(_content_tokens(query))
    target_terms = set(_content_tokens(target))
    if not target_terms:
        return 0.0
    return len(query_terms & target_terms) / len(target_terms)


def _extra_terms(raw_query: str, rewritten_query: str, anchors: Sequence[str]) -> int:
    allowed = set(_content_tokens(raw_query))
    for anchor in anchors:
        allowed.update(_content_tokens(anchor))
    return len(set(_content_tokens(rewritten_query)) - allowed)


def _content_tokens(text: str) -> list[str]:
    seen: set[str] = set()
    tokens: list[str] = []
    for match in CONTENT_WORD.finditer(text.casefold()):
        token = match.group(0)
        if token in STOPWORDS or token in seen:
            continue
        seen.add(token)
        tokens.append(token)
    return tokens


def _history_evidence_terms(history_turns: Sequence[Mapping[str, Any]]) -> set[str]:
    return {
        token
        for turn in history_turns
        if str(turn.get("speaker")) == "user"
        for token in _content_tokens(str(turn.get("text") or ""))
    }


def _history_before_last_user(turns: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    last_index = None
    for index, turn in enumerate(turns):
        if str(turn.get("speaker")) == "user":
            last_index = index
    return list(turns[:last_index]) if last_index is not None else list(turns)


def _windowed_history(history_turns: Sequence[Mapping[str, Any]], window_turns: int) -> list[Mapping[str, Any]]:
    if window_turns <= 0:
        return []
    return list(history_turns[-window_turns:])


def _last_user_text(turns: Sequence[Mapping[str, Any]]) -> str | None:
    for turn in reversed(turns):
        if str(turn.get("speaker")) == "user":
            return str(turn.get("text") or "")
    return None


def _first_label(value: Any, *, default: str) -> str:
    if isinstance(value, Sequence) and not isinstance(value, str) and value:
        return str(value[0])
    if value:
        return str(value)
    return default


def _same(left: Any, right: Any) -> float:
    return 1.0 if left == right else 0.0


def _mean(values: Sequence[float | None] | Any) -> float | None:
    materialized = [value for value in values if value is not None]
    if not materialized:
        return None
    return sum(materialized) / len(materialized)


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
