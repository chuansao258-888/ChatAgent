from __future__ import annotations

import argparse
import os
from pathlib import Path

from chatagent_eval.ragas_runner import DEFAULT_RAGAS_METRICS, RagasRunnerConfig, run_ragas


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
    return 2


def _optional_int(name: str) -> int | None:
    value = os.getenv(name)
    return int(value) if value else None


if __name__ == "__main__":
    raise SystemExit(main())
