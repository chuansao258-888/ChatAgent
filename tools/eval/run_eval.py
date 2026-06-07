from __future__ import annotations

import argparse
import os
from pathlib import Path

from chatagent_eval.agent_module_runner import (
    DEFAULT_AGENT_MODULE_DATASET_ID,
    AgentModuleConfig,
    run_agent_modules,
)
from chatagent_eval.memory_runner import DEFAULT_MEMORY_DATASET_ID, MemoryConfig, run_memory
from chatagent_eval.ragas_runner import DEFAULT_RAGAS_METRICS, RagasRunnerConfig, run_ragas
from chatagent_eval.text_recall_runner import DEFAULT_TEXT_RECALL_DATASET_ID, TextRecallConfig, run_text_recall
from chatagent_eval.tuning_runner import TuningConfig, run_tuning_experiment

PROJECT_ROOT = Path(__file__).resolve().parents[2]
EVAL_RESOURCE_ROOT = PROJECT_ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"
TUNING_RESOURCE_IDS = {
    "agent-modules": "agent-modules-v1",
    "memory-v2": "memory-v2-v1",
    "rag-retrieval": "rag-retrieval-v1",
    "text-recall": "text-recall-v1",
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ChatAgent evaluation runner")
    subparsers = parser.add_subparsers(dest="command", required=True)
    ragas_smoke = subparsers.add_parser("ragas-smoke", help="Run official Ragas metrics over exported v2 samples")
    ragas_smoke.add_argument("--input-run-dir", required=True, type=Path)
    ragas_smoke.add_argument("--output-root", default=Path("artifacts/eval/phase5"), type=Path)
    ragas_smoke.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "ragas-smoke"))
    ragas_smoke.add_argument("--metrics", default=",".join(DEFAULT_RAGAS_METRICS))
    ragas_smoke.add_argument("--failure-mode", choices=("nan", "structured"), default="structured")
    ragas_smoke.add_argument("--max-samples", type=int, default=_optional_int("CHATAGENT_EVAL_MAX_CASES"))
    ragas_smoke.add_argument("--judge-provider", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_PROVIDER", "deepseek"))
    ragas_smoke.add_argument("--judge-model", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_MODEL", "deepseek-chat"))
    ragas_smoke.add_argument("--judge-base-url", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL"))
    ragas_smoke.add_argument("--embedding-model", default=os.getenv("CHATAGENT_EVAL_RAGAS_EMBEDDING_MODEL"))
    ragas_smoke.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    ragas_smoke.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    text_recall = subparsers.add_parser("text-recall-smoke", help="Run deterministic text recall over real v2 source files")
    text_recall.add_argument("--dataset-root", default=Path("artifacts/eval/phase3"), type=Path)
    text_recall.add_argument("--output-root", default=Path("artifacts/eval/phase6"), type=Path)
    text_recall.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "text-recall-smoke"))
    text_recall.add_argument("--dataset-id", default=DEFAULT_TEXT_RECALL_DATASET_ID)
    text_recall.add_argument("--chunk-size", type=int, default=2000)
    text_recall.add_argument("--chunk-overlap", type=int, default=200)
    text_recall.add_argument("--top-k", type=int, default=5)
    text_recall.add_argument("--max-samples", type=int, default=_optional_int("CHATAGENT_EVAL_MAX_CASES"))
    text_recall.add_argument("--splits", default="", help="Comma-separated split filter; empty means all splits")
    text_recall.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    text_recall.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    memory = subparsers.add_parser("memory-smoke", help="Run deterministic Memory V2 eval over real multi-turn tasks")
    memory.add_argument("--dataset-root", default=Path("artifacts/eval/phase3"), type=Path)
    memory.add_argument("--output-root", default=Path("artifacts/eval/phase7"), type=Path)
    memory.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "memory-smoke"))
    memory.add_argument("--dataset-id", default=DEFAULT_MEMORY_DATASET_ID)
    memory.add_argument("--l1-window-turns", type=int, default=4)
    memory.add_argument("--l1-budget-chars", type=int, default=8000)
    memory.add_argument("--l2-segment-turns", type=int, default=4)
    memory.add_argument("--l3-top-k", type=int, default=3)
    memory.add_argument("--max-samples", type=int, default=_optional_int("CHATAGENT_EVAL_MAX_CASES"))
    memory.add_argument("--splits", default="", help="Comma-separated split filter; empty means all splits")
    memory.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    memory.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    agent_modules = subparsers.add_parser(
        "agent-modules-smoke",
        help="Run deterministic intent, rewrite, tool-call, and multi-turn module evals",
    )
    agent_modules.add_argument("--dataset-root", default=Path("artifacts/eval/phase3"), type=Path)
    agent_modules.add_argument("--output-root", default=Path("artifacts/eval/phase8"), type=Path)
    agent_modules.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "agent-modules-smoke"))
    agent_modules.add_argument("--dataset-id", default=DEFAULT_AGENT_MODULE_DATASET_ID)
    agent_modules.add_argument("--intent-history-turns", type=int, default=6)
    agent_modules.add_argument("--intent-min-evidence-terms", type=int, default=1)
    agent_modules.add_argument("--rewrite-history-turns", type=int, default=6)
    agent_modules.add_argument("--rewrite-max-anchors", type=int, default=3)
    agent_modules.add_argument("--rewrite-max-extra-terms", type=int, default=0)
    agent_modules.add_argument("--tool-candidate-limit", type=int, default=1)
    agent_modules.add_argument("--multiturn-coref-window-turns", type=int, default=6)
    agent_modules.add_argument("--enable-ragas-agent-metrics", action="store_true")
    agent_modules.add_argument("--max-samples", type=int, default=_optional_int("CHATAGENT_EVAL_MAX_CASES"))
    agent_modules.add_argument("--splits", default="", help="Comma-separated split filter; empty means all splits")
    agent_modules.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    agent_modules.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    tune = subparsers.add_parser("tune-suite", help="Run reproducible real-data parameter tuning with sealed holdout")
    tune.add_argument("--suite", choices=tuple(TUNING_RESOURCE_IDS), required=True)
    tune.add_argument("--dataset-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase3", type=Path)
    tune.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase9", type=Path)
    tune.add_argument("--experiment-id", required=True)
    tune.add_argument("--strategy", choices=("grid", "random"), default="random")
    tune.add_argument("--combination-budget", type=int, default=8)
    tune.add_argument("--random-seed", type=int, default=42)
    tune.add_argument("--search-splits", default="calibration,development")
    tune.add_argument("--holdout-split", default="holdout")
    tune.add_argument("--challenge-split", default="challenge")
    tune.add_argument("--max-samples-per-trial", type=int, default=50)
    tune.add_argument("--holdout-max-samples", type=int)
    tune.add_argument("--confidence-resamples", type=int, default=300)
    tune.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    tune.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))

    args = parser.parse_args(argv)
    if args.command == "ragas-smoke":
        config = RagasRunnerConfig(
            run_id=args.run_id,
            metric_names=tuple(metric.strip() for metric in args.metrics.split(",") if metric.strip()),
            failure_mode=args.failure_mode,
            max_samples=args.max_samples,
            judge_provider=args.judge_provider,
            judge_model=args.judge_model,
            judge_base_url=args.judge_base_url,
            embedding_model=args.embedding_model,
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_ragas(input_run_dir=args.input_run_dir, output_root=args.output_root, config=config)
        print(run_dir)
        return 0
    if args.command == "text-recall-smoke":
        config = TextRecallConfig(
            run_id=args.run_id,
            dataset_id=args.dataset_id,
            chunk_size=args.chunk_size,
            chunk_overlap=args.chunk_overlap,
            top_k=args.top_k,
            max_samples=args.max_samples,
            splits=tuple(split.strip() for split in args.splits.split(",") if split.strip()),
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_text_recall(dataset_root=args.dataset_root, output_root=args.output_root, config=config)
        print(run_dir)
        return 0
    if args.command == "memory-smoke":
        config = MemoryConfig(
            run_id=args.run_id,
            dataset_id=args.dataset_id,
            l1_window_turns=args.l1_window_turns,
            l1_budget_chars=args.l1_budget_chars,
            l2_segment_turns=args.l2_segment_turns,
            l3_top_k=args.l3_top_k,
            max_samples=args.max_samples,
            splits=tuple(split.strip() for split in args.splits.split(",") if split.strip()),
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_memory(dataset_root=args.dataset_root, output_root=args.output_root, config=config)
        print(run_dir)
        return 0
    if args.command == "agent-modules-smoke":
        config = AgentModuleConfig(
            run_id=args.run_id,
            dataset_id=args.dataset_id,
            intent_history_turns=args.intent_history_turns,
            intent_min_evidence_terms=args.intent_min_evidence_terms,
            rewrite_history_turns=args.rewrite_history_turns,
            rewrite_max_anchors=args.rewrite_max_anchors,
            rewrite_max_extra_terms=args.rewrite_max_extra_terms,
            tool_candidate_limit=args.tool_candidate_limit,
            multiturn_coref_window_turns=args.multiturn_coref_window_turns,
            ragas_agent_metrics=args.enable_ragas_agent_metrics,
            max_samples=args.max_samples,
            splits=tuple(split.strip() for split in args.splits.split(",") if split.strip()),
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_agent_modules(dataset_root=args.dataset_root, output_root=args.output_root, config=config)
        print(run_dir)
        return 0
    if args.command == "tune-suite":
        resource_id = TUNING_RESOURCE_IDS[args.suite]
        config = TuningConfig(
            experiment_id=args.experiment_id,
            suite=args.suite,
            strategy=args.strategy,
            combination_budget=args.combination_budget,
            random_seed=args.random_seed,
            search_splits=tuple(split.strip() for split in args.search_splits.split(",") if split.strip()),
            holdout_split=args.holdout_split,
            challenge_split=args.challenge_split or None,
            max_samples_per_trial=args.max_samples_per_trial,
            holdout_max_samples=args.holdout_max_samples,
            confidence_resamples=args.confidence_resamples,
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        experiment_dir = run_tuning_experiment(
            dataset_root=args.dataset_root,
            output_root=args.output_root,
            parameter_space_path=EVAL_RESOURCE_ROOT / "parameter-spaces" / f"{resource_id}.json",
            policy_path=EVAL_RESOURCE_ROOT / "tuning-policies" / f"{resource_id}.json",
            registry_path=EVAL_RESOURCE_ROOT / "parameter-registry-v1.json",
            config=config,
        )
        print(experiment_dir)
        return 0
    return 2


def _optional_int(name: str) -> int | None:
    value = os.getenv(name)
    return int(value) if value else None


if __name__ == "__main__":
    raise SystemExit(main())
