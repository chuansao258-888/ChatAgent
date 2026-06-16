"""Reproducible real-data tuning experiments with sealed-holdout verification."""

from __future__ import annotations

import hashlib
import json
import time
from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from chatagent_eval.agent_module_runner import AgentModuleConfig, run_agent_modules
from chatagent_eval.doc_ingestion_runner import DocIngestionConfig, run_doc_ingestion_retrieval
from chatagent_eval.memory_runner import MemoryConfig, run_memory
from chatagent_eval.parameters import validate_parameter_registry, validate_registry_coverage, validate_tuning_policy
from chatagent_eval.promotion import (
    build_promotion_candidate,
    gate_failures,
    split_audit,
    write_experiment_artifacts,
)
from chatagent_eval.rag_retrieval_runner import RagRetrievalConfig, run_rag_retrieval
from chatagent_eval.text_recall_runner import TextRecallConfig, run_text_recall
from chatagent_eval.tuning import (
    bootstrap_confidence_interval,
    build_experiment_trials,
    pareto_frontier,
    select_champion,
)

SUPPORTED_SUITES = {"agent-modules", "doc-ingestion-retrieval", "memory-v2", "rag-retrieval", "text-recall"}
SUITE_CONFIG_FIELDS = {
    "agent-modules": {
        "intentHistoryTurns": "intent_history_turns",
        "intentMinEvidenceTerms": "intent_min_evidence_terms",
        "rewriteHistoryTurns": "rewrite_history_turns",
        "rewriteMaxAnchors": "rewrite_max_anchors",
        "rewriteMaxExtraTerms": "rewrite_max_extra_terms",
        "toolCandidateLimit": "tool_candidate_limit",
        "multiturnCorefWindowTurns": "multiturn_coref_window_turns",
    },
    "memory-v2": {
        "l1WindowTurns": "l1_window_turns",
        "l1BudgetChars": "l1_budget_chars",
        "l2SegmentTurns": "l2_segment_turns",
        "l3TopK": "l3_top_k",
    },
    "doc-ingestion-retrieval": {
        "topK": "top_k",
        "candidateK": "candidate_k",
        "rrfK": "rrf_k",
    },
    "rag-retrieval": {
        "topK": "top_k",
        "candidateK": "candidate_k",
        "rrfK": "rrf_k",
    },
    "text-recall": {
        "chunkSize": "chunk_size",
        "chunkOverlap": "chunk_overlap",
        "topK": "top_k",
    },
}
SAMPLE_METRIC_PATHS = {
    "agentModules.intentExactPathAccuracy": ("metadata", "intent", "exactPathAccuracy"),
    "agentModules.rewriteAnchorRecall": ("metadata", "queryRewrite", "anchorRecall"),
    "agentModules.toolCallF1": ("metadata", "toolCall", "f1"),
    "agentModules.coreferenceRecall": ("metadata", "multiTurn", "coreferenceRecall"),
    "memory.l1CompleteTurnRecall": ("metadata", "l1", "completeTurnRecall"),
    "memory.l2FactRecall": ("metadata", "l2", "factRecall"),
    "memory.l3RecallHitAtK": ("metadata", "l3", "recallHitAtK"),
    "textRecall.retrievalContextPhraseRecall": ("metadata", "retrieval", "recall"),
    "textRecall.citationSupportRecall": ("metadata", "citation", "recall"),
    "textRecall.chunkSpanRecall": ("metadata", "chunk", "recall"),
    "ragRetrieval.ndcgAtK": ("metadata", "ndcgAtK"),
    "ragRetrieval.hitAtK": ("metadata", "hitAtK"),
    "ragRetrieval.recallAtK": ("metadata", "recallAtK"),
    "ragRetrieval.mrr": ("metadata", "mrr"),
    "docIngestion.hitAtK": ("metadata", "docIngestion", "hitAtK"),
    "docIngestion.contextRecallAtK": ("metadata", "docIngestion", "contextRecallAtK"),
    "docIngestion.mrr": ("metadata", "docIngestion", "mrr"),
    "docIngestion.phraseRecall": ("metadata", "docIngestion", "phraseRecall"),
}


@dataclass(frozen=True)
class TuningConfig:
    experiment_id: str
    suite: str
    strategy: str = "random"
    combination_budget: int = 8
    random_seed: int = 42
    search_splits: tuple[str, ...] = ("calibration", "development")
    holdout_split: str = "holdout"
    challenge_split: str | None = "challenge"
    max_samples_per_trial: int | None = 50
    holdout_max_samples: int | None = None
    confidence_resamples: int = 300
    doc_ingestion_repair_overlay: Path | None = None
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.suite not in SUPPORTED_SUITES:
            raise ValueError(f"unsupported tuning suite: {self.suite}")
        if self.strategy not in {"grid", "random"}:
            raise ValueError("strategy must be grid or random")
        if self.combination_budget <= 0:
            raise ValueError("combination_budget must be positive")
        if self.max_samples_per_trial is not None and self.max_samples_per_trial <= 0:
            raise ValueError("max_samples_per_trial must be positive when provided")
        if self.holdout_max_samples is not None and self.holdout_max_samples <= 0:
            raise ValueError("holdout_max_samples must be positive when provided")
        if self.confidence_resamples <= 0:
            raise ValueError("confidence_resamples must be positive")
        if self.doc_ingestion_repair_overlay is not None and self.suite != "doc-ingestion-retrieval":
            raise ValueError("doc_ingestion_repair_overlay is only supported for doc-ingestion-retrieval")


def run_tuning_experiment(
    *,
    dataset_root: Path,
    output_root: Path,
    parameter_space_path: Path,
    policy_path: Path,
    registry_path: Path,
    config: TuningConfig,
) -> Path:
    parameter_space = _read_json(parameter_space_path)
    policy = _read_json(policy_path)
    registry = _read_json(registry_path)
    validate_parameter_registry(registry)
    validate_registry_coverage(registry, [parameter_space])
    validate_tuning_policy(policy, parameter_space, registry, config.suite)

    dataset_manifest = _read_json(dataset_root / "manifests" / "datasets" / f"{_dataset_id(config.suite)}.json")
    split_manifest = _read_json(dataset_root / dataset_manifest["splitManifestPath"])
    audit = split_audit(
        split_manifest,
        search_splits=config.search_splits,
        holdout_split=config.holdout_split,
        challenge_split=config.challenge_split,
    )
    trials = build_experiment_trials(
        parameter_space,
        experiment_id=config.experiment_id,
        baseline_parameters=policy["baselineParameters"],
        strategy=config.strategy,
        combination_budget=config.combination_budget,
        random_seed=config.random_seed,
    )

    experiment_dir = output_root / config.experiment_id
    search_runs_root = experiment_dir / "search-runs"
    completed: list[dict[str, Any]] = []
    baseline_categories: Mapping[str, float] | None = None
    for trial in trials:
        evaluated = _evaluate_trial(
            trial=trial,
            dataset_root=dataset_root,
            output_root=search_runs_root,
            config=config,
            policy=policy,
            dataset_manifest=dataset_manifest,
            search_split_hashes=audit["searchSplitHashes"],
            baseline_category_metrics=baseline_categories,
        )
        completed.append(evaluated)
        if evaluated["stage"] == "baseline" and evaluated["status"] == "completed":
            baseline_categories = evaluated["categoryMetrics"]

    secondary_metrics = [(item["metric"], item["direction"]) for item in policy.get("secondaryMetrics", [])]
    champion = select_champion(
        completed,
        primary_metric=policy["primaryMetric"],
        direction=policy["direction"],
        secondary_metrics=secondary_metrics,
        baseline_parameters=policy["baselineParameters"],
    )
    replay = _evaluate_verification_run(
        name="champion-replay",
        parameters=champion["parameters"],
        splits=config.search_splits,
        max_samples=config.max_samples_per_trial,
        dataset_root=dataset_root,
        output_root=experiment_dir / "verification-runs",
        config=config,
        policy=policy,
        baseline_category_metrics=baseline_categories,
    )
    baseline_holdout = _evaluate_verification_run(
        name="baseline-holdout",
        parameters=policy["baselineParameters"],
        splits=(config.holdout_split,),
        max_samples=config.holdout_max_samples,
        dataset_root=dataset_root,
        output_root=experiment_dir / "verification-runs",
        config=config,
        policy=policy,
        baseline_category_metrics=None,
    )
    holdout = _evaluate_verification_run(
        name="champion-holdout",
        parameters=champion["parameters"],
        splits=(config.holdout_split,),
        max_samples=config.holdout_max_samples,
        dataset_root=dataset_root,
        output_root=experiment_dir / "verification-runs",
        config=config,
        policy=policy,
        baseline_category_metrics=baseline_holdout["categoryMetrics"],
    )
    challenge = None
    if audit["challengeSplit"]:
        challenge = _evaluate_verification_run(
            name="champion-challenge",
            parameters=champion["parameters"],
            splits=(str(audit["challengeSplit"]),),
            max_samples=config.holdout_max_samples,
            dataset_root=dataset_root,
            output_root=experiment_dir / "verification-runs",
            config=config,
            policy=policy,
            baseline_category_metrics=None,
        )

    holdout_verification = _holdout_verification(
        champion=champion,
        replay=replay,
        baseline_holdout=baseline_holdout,
        holdout=holdout,
        challenge=challenge,
        audit=audit,
        policy=policy,
    )
    promotion_candidate = build_promotion_candidate(
        experiment_id=config.experiment_id,
        suite=config.suite,
        champion=champion,
        holdout_verification=holdout_verification,
        registry_id=registry["registryId"],
    )
    frontier = pareto_frontier(completed, objectives=_pareto_objectives(completed, policy))
    leaderboard = _leaderboard(completed, policy)
    experiment_manifest = {
        "experimentId": config.experiment_id,
        "suite": config.suite,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "strategy": config.strategy,
        "combinationBudget": config.combination_budget,
        "randomSeed": config.random_seed,
        "parameterSpaceId": parameter_space["id"],
        "parameterSpaceVersion": parameter_space["version"],
        "registryId": registry["registryId"],
        "datasetId": dataset_manifest["datasetId"],
        "datasetHash": dataset_manifest["datasetHash"],
        "docIngestionRepairOverlay": _overlay_metadata(config.doc_ingestion_repair_overlay),
        "searchSplits": list(config.search_splits),
        "searchSplitHashes": dict(audit["searchSplitHashes"]),
        "sealedHoldoutSplit": config.holdout_split,
        "sealedHoldoutHashIncludedInIterativeTrials": False,
        "championSelectedBeforeHoldoutAccess": True,
        "trialCount": len(completed),
        "gitBranch": config.git_branch,
        "gitSha": config.git_sha,
        "artifactFiles": [
            "experiment-manifest.json",
            "parameter-space.yaml",
            "trials.jsonl",
            "leaderboard.csv",
            "pareto-frontier.json",
            "champion-candidate.yaml",
            "holdout-verification.json",
            "promotion-decision.md",
        ],
    }
    return write_experiment_artifacts(
        output_root=output_root,
        experiment_manifest=experiment_manifest,
        parameter_space=parameter_space,
        trials=completed,
        leaderboard=leaderboard,
        pareto_frontier=frontier,
        champion_candidate=promotion_candidate,
        holdout_verification=holdout_verification,
    )


def _evaluate_trial(
    *,
    trial: Mapping[str, Any],
    dataset_root: Path,
    output_root: Path,
    config: TuningConfig,
    policy: Mapping[str, Any],
    dataset_manifest: Mapping[str, Any],
    search_split_hashes: Mapping[str, str],
    baseline_category_metrics: Mapping[str, float] | None,
) -> dict[str, Any]:
    evaluated = dict(trial)
    try:
        result = _run_suite(
            suite=config.suite,
            parameters=trial["parameters"],
            run_id=str(trial["trialId"]),
            splits=config.search_splits,
            max_samples=config.max_samples_per_trial,
            dataset_root=dataset_root,
            output_root=output_root,
            git_branch=config.git_branch,
            git_sha=config.git_sha,
            doc_ingestion_repair_overlay=config.doc_ingestion_repair_overlay,
        )
        sample_values = _sample_metric_values(result["samples"], policy["primaryMetric"])
        confidence = bootstrap_confidence_interval(
            sample_values or [float(result["metrics"][policy["primaryMetric"]])],
            random_seed=config.random_seed,
            resamples=config.confidence_resamples,
        )
        categories = _category_metrics(result["samples"], policy["primaryMetric"])
        failures = gate_failures(
            result["metrics"],
            category_metrics=categories,
            baseline_category_metrics=baseline_category_metrics,
            policy=policy,
            latency_p95_ms=result["latencyP95Ms"],
            cost_usd=0.0,
        )
        evaluated.update(
            {
                "status": "completed",
                "datasetHash": dataset_manifest["datasetHash"],
                "splitHashes": dict(search_split_hashes),
                "codeSha": config.git_sha,
                "models": {},
                "metrics": result["metrics"],
                "categoryMetrics": categories,
                "confidenceInterval": confidence,
                "repeatedRunVariance": confidence["variance"],
                "failureCount": result["failureCount"],
                "gateFailures": failures,
                "latencyP95Ms": result["latencyP95Ms"],
                "executionElapsedMs": result["executionElapsedMs"],
                "costUsd": 0.0,
            }
        )
    except (KeyError, TypeError, ValueError) as exception:
        evaluated.update(
            {
                "status": "failed",
                "metrics": {},
                "gateFailures": [f"invalid-trial:{exception}"],
                "latencyP95Ms": None,
                "costUsd": 0.0,
            }
        )
    return evaluated


def _evaluate_verification_run(
    *,
    name: str,
    parameters: Mapping[str, Any],
    splits: Sequence[str],
    max_samples: int | None,
    dataset_root: Path,
    output_root: Path,
    config: TuningConfig,
    policy: Mapping[str, Any],
    baseline_category_metrics: Mapping[str, float] | None,
) -> dict[str, Any]:
    result = _run_suite(
        suite=config.suite,
        parameters=parameters,
        run_id=name,
        splits=splits,
        max_samples=max_samples,
        dataset_root=dataset_root,
        output_root=output_root,
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        doc_ingestion_repair_overlay=config.doc_ingestion_repair_overlay,
    )
    values = _sample_metric_values(result["samples"], policy["primaryMetric"])
    confidence = bootstrap_confidence_interval(
        values or [float(result["metrics"][policy["primaryMetric"]])],
        random_seed=config.random_seed,
        resamples=config.confidence_resamples,
    )
    categories = _category_metrics(result["samples"], policy["primaryMetric"])
    failures = gate_failures(
        result["metrics"],
        category_metrics=categories,
        baseline_category_metrics=baseline_category_metrics,
        policy=policy,
        latency_p95_ms=result["latencyP95Ms"],
        cost_usd=0.0,
    )
    return {
        "name": name,
        "splits": list(splits),
        "metrics": result["metrics"],
        "categoryMetrics": categories,
        "confidenceInterval": confidence,
        "gateFailures": failures,
        "latencyP95Ms": result["latencyP95Ms"],
        "executionElapsedMs": result["executionElapsedMs"],
        "costUsd": 0.0,
    }


def _run_suite(
    *,
    suite: str,
    parameters: Mapping[str, Any],
    run_id: str,
    splits: Sequence[str],
    max_samples: int | None,
    dataset_root: Path,
    output_root: Path,
    git_branch: str,
    git_sha: str,
    doc_ingestion_repair_overlay: Path | None,
) -> dict[str, Any]:
    kwargs = {SUITE_CONFIG_FIELDS[suite][key]: value for key, value in parameters.items()}
    common = {
        "run_id": run_id,
        "splits": tuple(splits),
        "max_samples": max_samples,
        "git_branch": git_branch,
        "git_sha": git_sha,
    }
    start = time.perf_counter()
    if suite == "agent-modules":
        run_dir = run_agent_modules(
            dataset_root=dataset_root,
            output_root=output_root,
            config=AgentModuleConfig(**common, **kwargs),
        )
    elif suite == "memory-v2":
        run_dir = run_memory(
            dataset_root=dataset_root,
            output_root=output_root,
            config=MemoryConfig(**common, **kwargs),
        )
    elif suite == "doc-ingestion-retrieval":
        run_dir = run_doc_ingestion_retrieval(
            dataset_root=dataset_root,
            output_root=output_root,
            config=DocIngestionConfig(**common, repair_overlay_path=doc_ingestion_repair_overlay, **kwargs),
        )
    elif suite == "text-recall":
        run_dir = run_text_recall(
            dataset_root=dataset_root,
            output_root=output_root,
            config=TextRecallConfig(**common, **kwargs),
        )
    elif suite == "rag-retrieval":
        run_dir = run_rag_retrieval(
            dataset_root=dataset_root,
            output_root=output_root,
            config=RagRetrievalConfig(**common, **kwargs),
        )
    else:
        raise ValueError(f"unsupported tuning suite: {suite}")
    elapsed_ms = (time.perf_counter() - start) * 1000.0
    report = _read_json(run_dir / "report.json")
    samples = _read_jsonl(run_dir / "samples.jsonl")
    failures = _read_jsonl(run_dir / "failures.jsonl")
    numeric_metrics = {
        key: value
        for key, value in report["metrics"].items()
        if value is None or isinstance(value, (int, float))
    }
    return {
        "metrics": numeric_metrics,
        "samples": samples,
        "failureCount": len(failures),
        "latencyP95Ms": numeric_metrics.get("latencyP95Ms", numeric_metrics.get("p95LatencyMs")),
        "executionElapsedMs": elapsed_ms,
    }


def _holdout_verification(
    *,
    champion: Mapping[str, Any],
    replay: Mapping[str, Any],
    baseline_holdout: Mapping[str, Any],
    holdout: Mapping[str, Any],
    challenge: Mapping[str, Any] | None,
    audit: Mapping[str, Any],
    policy: Mapping[str, Any],
) -> dict[str, Any]:
    primary = policy["primaryMetric"]
    tolerance = float(policy.get("holdoutTolerance", 0.0))
    development_value = float(champion["metrics"][primary])
    replay_value = float(replay["metrics"][primary])
    holdout_value = float(holdout["metrics"][primary])
    if policy["direction"] == "maximize":
        replay_matches = replay_value >= development_value - tolerance
        holdout_matches = holdout_value >= development_value - tolerance
    else:
        replay_matches = replay_value <= development_value + tolerance
        holdout_matches = holdout_value <= development_value + tolerance
    verification_gates = [*replay["gateFailures"], *holdout["gateFailures"]]
    if challenge is not None:
        verification_gates.extend(challenge["gateFailures"])
    status = "pass" if replay_matches and holdout_matches and not verification_gates else "warn"
    return {
        "status": status,
        "championTrialId": champion["trialId"],
        "championConfigFingerprint": champion["configFingerprint"],
        "championSelectedBeforeHoldoutAccess": True,
        "holdoutOpenedAfterChampionSelection": True,
        "searchSplitHashes": dict(audit["searchSplitHashes"]),
        "holdoutSplit": audit["holdoutSplit"],
        "holdoutSplitHash": audit["holdoutSplitHash"],
        "challengeSplit": audit["challengeSplit"],
        "challengeSplitHash": audit["challengeSplitHash"],
        "overlapCount": audit["overlapCount"],
        "primaryMetric": primary,
        "developmentValue": development_value,
        "developmentReplay": replay,
        "baselineHoldout": baseline_holdout,
        "holdout": holdout,
        "challenge": challenge,
        "verificationGateFailures": verification_gates,
        "tolerance": tolerance,
    }


def _leaderboard(trials: Sequence[Mapping[str, Any]], policy: Mapping[str, Any]) -> list[dict[str, Any]]:
    primary = policy["primaryMetric"]
    reverse = policy["direction"] == "maximize"
    ordered = sorted(
        trials,
        key=lambda trial: (
            bool(trial.get("gateFailures")),
            _leaderboard_metric_value(trial, primary, reverse),
            float(trial.get("latencyP95Ms") or float("inf")),
            str(trial["configFingerprint"]),
        ),
    )
    rows: list[dict[str, Any]] = []
    for rank, trial in enumerate(ordered, start=1):
        confidence = trial.get("confidenceInterval") or {}
        rows.append(
            {
                "rank": rank,
                "trialId": trial["trialId"],
                "stage": trial.get("stage"),
                "status": trial["status"],
                "primaryMetric": trial.get("metrics", {}).get(primary),
                "confidenceLower": confidence.get("lower"),
                "confidenceUpper": confidence.get("upper"),
                "latencyP95Ms": trial.get("latencyP95Ms"),
                "executionElapsedMs": trial.get("executionElapsedMs"),
                "costUsd": trial.get("costUsd"),
                "gateFailures": ";".join(trial.get("gateFailures", [])),
                "configFingerprint": trial["configFingerprint"],
                "parameters": json.dumps(trial["parameters"], sort_keys=True),
            }
        )
    return rows


def _pareto_objectives(
    trials: Sequence[Mapping[str, Any]],
    policy: Mapping[str, Any],
) -> list[tuple[str, str]]:
    eligible = [trial for trial in trials if trial.get("status") == "completed" and not trial.get("gateFailures")]
    objectives = [(policy["primaryMetric"], policy["direction"])]
    if eligible and all(trial.get("latencyP95Ms") is not None for trial in eligible):
        objectives.append(("latencyP95Ms", "minimize"))
    if eligible and all(trial.get("costUsd") is not None for trial in eligible):
        objectives.append(("costUsd", "minimize"))
    return objectives


def _leaderboard_metric_value(trial: Mapping[str, Any], metric: str, reverse: bool) -> float:
    value = trial.get("metrics", {}).get(metric)
    if value is None:
        return float("inf")
    return -float(value) if reverse else float(value)


def _sample_metric_values(samples: Sequence[Mapping[str, Any]], metric: str) -> list[float]:
    path = SAMPLE_METRIC_PATHS.get(metric)
    if path is None:
        return []
    values: list[float] = []
    for sample in samples:
        value = _nested(sample, path)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            values.append(float(value))
    return values


def _category_metrics(samples: Sequence[Mapping[str, Any]], metric: str) -> dict[str, float]:
    path = SAMPLE_METRIC_PATHS.get(metric)
    if path is None:
        return {}
    grouped: dict[str, list[float]] = defaultdict(list)
    for sample in samples:
        value = _nested(sample, path)
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            continue
        metadata = sample.get("metadata") or {}
        source_metadata = metadata.get("sourceMetadata") or {}
        category = str(source_metadata.get("domain") or metadata.get("format") or metadata.get("sourceUrl") or "all")
        grouped[category].append(float(value))
    return {category: sum(values) / len(values) for category, values in sorted(grouped.items())}


def _nested(value: Mapping[str, Any], path: Sequence[str]) -> Any:
    current: Any = value
    for key in path:
        if not isinstance(current, Mapping):
            return None
        current = current.get(key)
    return current


def _dataset_id(suite: str) -> str:
    return {
        "agent-modules": "memory-v2-dialogues",
        "doc-ingestion-retrieval": "doc-ingestion-retrieval-v1",
        "memory-v2": "memory-v2-dialogues",
        "rag-retrieval": "beir-scifact-rag-v1",
        "text-recall": "sec-companyfacts-text-recall-v1",
    }[suite]


def _overlay_metadata(path: Path | None) -> Mapping[str, Any] | None:
    if path is None:
        return None
    if not path.exists():
        raise ValueError(f"doc-ingestion repair overlay not found: {path}")
    return {
        "path": str(path),
        "name": path.name,
        "sha256": _file_hash(path),
    }


def _file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return f"sha256:{digest.hexdigest()}"


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
