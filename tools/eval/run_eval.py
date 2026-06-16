from __future__ import annotations

import argparse
import os
from pathlib import Path

from chatagent_eval.agent_module_runner import (
    DEFAULT_AGENT_MODULE_DATASET_ID,
    AgentModuleConfig,
    run_agent_modules,
)
from chatagent_eval.doc_ingestion_analysis import (
    DocIngestionAnalysisConfig,
    run_doc_ingestion_failure_analysis,
)
from chatagent_eval.answer_harness import AnswerHarnessConfig, run_answer_harness
from chatagent_eval.doc_ingestion_preflight import (
    DEFAULT_RAGAS_METRICS as DEFAULT_10D_B2_RAGAS_METRICS,
    DocIngestionPreflightConfig,
    run_doc_ingestion_preflight,
)
from chatagent_eval.evidence_bundle import write_10d_b2_evidence_bundle
from chatagent_eval.memory_runner import DEFAULT_MEMORY_DATASET_ID, MemoryConfig, run_memory
from chatagent_eval.memory_semantic_runner import MemorySemanticConfig, run_memory_semantic
from chatagent_eval.memory_semantic_analysis import (
    MemorySemanticAnalysisConfig,
    run_memory_semantic_analysis,
)
from chatagent_eval.memory_reference_runner import MemoryReferenceConfig, run_memory_reference_clusters
from chatagent_eval.memory_tuning_subset import MemoryTuningSubsetConfig, run_memory_tuning_subset
from chatagent_eval.joint_selection import write_10d_b2_joint_selection
from chatagent_eval.promotion_report import write_10d_b2_promotion_report
from chatagent_eval.ragas_runner import DEFAULT_RAGAS_METRICS, RagasRunnerConfig, run_ragas
from chatagent_eval.text_recall_runner import DEFAULT_TEXT_RECALL_DATASET_ID, TextRecallConfig, run_text_recall
from chatagent_eval.tuning_runner import TuningConfig, run_tuning_experiment

PROJECT_ROOT = Path(__file__).resolve().parents[2]
EVAL_RESOURCE_ROOT = PROJECT_ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"
TUNING_RESOURCE_IDS = {
    "agent-modules": "agent-modules-v1",
    "doc-ingestion-retrieval": "doc-ingestion-retrieval-v1",
    "memory-v2": "memory-v2-v1",
    "rag-retrieval": "rag-retrieval-v1",
    "text-recall": "text-recall-v1",
}


ZHIPUAI_API_KEY_VARS = (
    "CHATAGENT_ZHIPUAI_API_KEY",
    "CHATAGENT_ZHIPUAI_API_KEY_2",
    "CHATAGENT_ZHIPUAI_SECONDARY_API_KEY",
)
ZAI_API_KEY_VARS = (
    "CHATAGENT_ZAI_CODING_API_KEY",
    "CHATAGENT_ZAI_API_KEY",
)
ZHIPUAI_PROVIDER_ALIASES = {"zhipu", "zhipuai"}
ZAI_PROVIDER_ALIASES = {"z.ai", "zai", "z-ai", "zai-coding"}


def _first_env(*names: str) -> str | None:
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return None


def _has_any_env(names: tuple[str, ...]) -> bool:
    return any(os.getenv(name) for name in names)


def _default_ragas_provider() -> str:
    explicit = os.getenv("CHATAGENT_EVAL_RAGAS_LLM_PROVIDER")
    if explicit:
        return explicit
    if _has_any_env(ZHIPUAI_API_KEY_VARS):
        return "zhipu"
    if _has_any_env(ZAI_API_KEY_VARS):
        return "zai"
    return "deepseek"


def _default_answer_provider() -> str:
    explicit = os.getenv("CHATAGENT_EVAL_ANSWER_LLM_PROVIDER")
    if explicit:
        return explicit
    if _has_any_env(ZHIPUAI_API_KEY_VARS):
        return "zhipu"
    if _has_any_env(ZAI_API_KEY_VARS):
        return "zai"
    return "deepseek"


def _default_model(provider: str, eval_model_var: str) -> str:
    explicit = os.getenv(eval_model_var)
    if explicit:
        return explicit
    if provider in ZHIPUAI_PROVIDER_ALIASES:
        return os.getenv("CHATAGENT_ZHIPUAI_MODEL") or "glm-4.5-air"
    if provider in ZAI_PROVIDER_ALIASES:
        return _first_env("CHATAGENT_ZAI_CODING_MODEL", "CHATAGENT_ZAI_MODEL") or "glm-4.5-air"
    return "deepseek-chat"


def _default_ragas_base_url(provider: str) -> str | None:
    explicit = os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL")
    if explicit:
        return explicit
    if provider in ZHIPUAI_PROVIDER_ALIASES:
        return os.getenv("CHATAGENT_ZHIPUAI_BASE_URL")
    if provider in ZAI_PROVIDER_ALIASES:
        return _first_env("CHATAGENT_ZAI_CODING_BASE_URL", "CHATAGENT_ZAI_BASE_URL")
    return None


def _default_answer_base_url(provider: str) -> str | None:
    explicit = os.getenv("CHATAGENT_EVAL_ANSWER_LLM_BASE_URL")
    if explicit:
        return explicit
    if provider in ZHIPUAI_PROVIDER_ALIASES:
        return os.getenv("CHATAGENT_ZHIPUAI_BASE_URL")
    if provider in ZAI_PROVIDER_ALIASES:
        return _first_env("CHATAGENT_ZAI_CODING_BASE_URL", "CHATAGENT_ZAI_BASE_URL")
    return os.getenv("CHATAGENT_DEEPSEEK_BASE_URL")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ChatAgent evaluation runner")
    subparsers = parser.add_subparsers(dest="command", required=True)
    default_ragas_provider = _default_ragas_provider()
    default_answer_provider = _default_answer_provider()
    ragas_smoke = subparsers.add_parser("ragas-smoke", help="Run official Ragas metrics over exported v2 samples")
    ragas_smoke.add_argument("--input-run-dir", required=True, type=Path)
    ragas_smoke.add_argument("--output-root", default=Path("artifacts/eval/phase5"), type=Path)
    ragas_smoke.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "ragas-smoke"))
    ragas_smoke.add_argument("--metrics", default=",".join(DEFAULT_RAGAS_METRICS))
    ragas_smoke.add_argument("--failure-mode", choices=("nan", "structured"), default="structured")
    ragas_smoke.add_argument("--max-samples", type=int, default=_optional_int("CHATAGENT_EVAL_MAX_CASES"))
    ragas_smoke.add_argument("--judge-provider", default=default_ragas_provider)
    ragas_smoke.add_argument("--judge-model", default=_default_model(default_ragas_provider, "CHATAGENT_EVAL_RAGAS_LLM_MODEL"))
    ragas_smoke.add_argument("--judge-base-url", default=_default_ragas_base_url(default_ragas_provider))
    ragas_smoke.add_argument("--judge-temperature", type=float, default=0.0)
    ragas_smoke.add_argument("--judge-max-tokens", type=int, default=1024)
    ragas_smoke.add_argument("--judge-context-tokens", type=int)
    ragas_smoke.add_argument("--judge-timeout-seconds", type=int)
    ragas_smoke.add_argument("--judge-max-workers", type=int)
    ragas_smoke.add_argument("--judge-think", action=argparse.BooleanOptionalAction, default=None)
    ragas_smoke.add_argument("--response-relevancy-strictness", type=int)
    ragas_smoke.add_argument("--embedding-model", default=os.getenv("CHATAGENT_EVAL_RAGAS_EMBEDDING_MODEL"))
    ragas_smoke.add_argument("--embedding-num-gpu", type=int)
    ragas_smoke.add_argument("--batch-size", type=int, default=25)
    ragas_smoke.add_argument("--batch-delay-seconds", type=float, default=0.0)
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
    memory_semantic = subparsers.add_parser(
        "memory-semantic",
        help="Run explicit semantic support/usefulness judging over a calibration fixture or full Memory export",
    )
    memory_semantic.add_argument("--input-path", required=True, type=Path)
    memory_semantic.add_argument("--input-kind", choices=("calibration", "full-export"), default="calibration")
    memory_semantic.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    memory_semantic.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "memory-semantic"))
    memory_semantic.add_argument("--judge-provider", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_PROVIDER", "deepseek"))
    memory_semantic.add_argument("--judge-model", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_MODEL", "deepseek-chat"))
    memory_semantic.add_argument("--judge-base-url", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL"))
    memory_semantic.add_argument("--repeats", type=int, default=2)
    memory_semantic.add_argument("--judge-max-attempts-per-repeat", type=int, default=3)
    memory_semantic.add_argument("--reference-cluster-run-dir", type=Path)
    memory_semantic.add_argument(
        "--reuse-reference-semantic-run-dir",
        type=Path,
        help="Reuse exact frozen L3-reference judgments from a prior semantic run",
    )
    memory_semantic.add_argument(
        "--embedding-model",
        default=os.getenv("CHATAGENT_EVAL_RAGAS_EMBEDDING_MODEL", "bge-m3"),
    )
    memory_semantic.add_argument(
        "--embedding-base-url",
        default=os.getenv("CHATAGENT_EVAL_RAGAS_EMBEDDING_BASE_URL")
        or os.getenv("CHATAGENT_RAG_EMBEDDING_BASE_URL"),
    )
    memory_semantic.add_argument("--embedding-num-gpu", type=int, default=1)
    memory_semantic.add_argument("--embedding-match-threshold", type=float, default=0.80)
    memory_semantic.add_argument("--max-samples", type=int)
    memory_semantic.add_argument(
        "--splits",
        default="",
        help="Comma-separated split filter; full-export defaults to calibration,development and holdout must be explicit",
    )
    memory_semantic.add_argument("--targets", default="l2,l3", help="Comma-separated semantic targets: l2,l3")
    memory_semantic.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    memory_semantic.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    memory_reference = subparsers.add_parser(
        "memory-reference-clusters",
        help="Generate independent L3 reference fact clusters from a real Memory export",
    )
    memory_reference.add_argument("--input-path", required=True, type=Path)
    memory_reference.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    memory_reference.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "memory-reference-clusters"))
    memory_reference.add_argument("--judge-provider", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_PROVIDER", "deepseek"))
    memory_reference.add_argument("--judge-model", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_MODEL", "deepseek-chat"))
    memory_reference.add_argument("--judge-base-url", default=os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL"))
    memory_reference.add_argument("--judge-max-attempts", type=int, default=3)
    memory_reference.add_argument("--max-samples", type=int)
    memory_reference.add_argument(
        "--splits",
        default="calibration,development",
        help="Comma-separated split filter; holdout must be requested explicitly for final verification",
    )
    memory_reference.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    memory_reference.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    memory_semantic_analysis = subparsers.add_parser(
        "memory-semantic-analysis",
        help="Create deterministic split/source-group analysis from a completed semantic Memory run",
    )
    memory_semantic_analysis.add_argument("--input-run-dir", required=True, type=Path)
    memory_semantic_analysis.add_argument(
        "--output-root",
        default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d",
        type=Path,
    )
    memory_semantic_analysis.add_argument(
        "--run-id",
        default=os.getenv("CHATAGENT_EVAL_RUN_ID", "memory-semantic-analysis"),
    )
    memory_semantic_analysis.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    memory_semantic_analysis.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    memory_tuning_subset = subparsers.add_parser(
        "memory-tuning-subset",
        help="Build the final calibration/development-only L3 tuning subset",
    )
    memory_tuning_subset.add_argument("--source-export-samples", required=True, type=Path)
    memory_tuning_subset.add_argument("--semantic-run-dir", required=True, type=Path)
    memory_tuning_subset.add_argument(
        "--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path
    )
    memory_tuning_subset.add_argument("--run-id", required=True)
    memory_tuning_subset.add_argument("--hard-negative-target", type=int, default=120)
    memory_tuning_subset.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    memory_tuning_subset.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
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
    doc_ingestion_analysis = subparsers.add_parser(
        "doc-ingestion-analysis",
        help="Analyze Phase 10a doc-ingestion retrieval failures and oracle recall",
    )
    doc_ingestion_analysis.add_argument("--dataset-root", required=True, type=Path)
    doc_ingestion_analysis.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    doc_ingestion_analysis.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "phase10d-10a-analysis"))
    doc_ingestion_analysis.add_argument("--dataset-id", default="doc-ingestion-retrieval-v1")
    doc_ingestion_analysis.add_argument("--top-k", type=int, default=8)
    doc_ingestion_analysis.add_argument("--candidate-k", type=int, default=12)
    doc_ingestion_analysis.add_argument("--rrf-k", type=int, default=60)
    doc_ingestion_analysis.add_argument("--splits", default="", help="Comma-separated split filter; empty means all splits")
    doc_ingestion_analysis.add_argument("--failed-audit-target", type=int, default=50)
    doc_ingestion_analysis.add_argument("--success-audit-target", type=int, default=25)
    doc_ingestion_preflight = subparsers.add_parser(
        "doc-ingestion-preflight",
        help="Run 10d-B2 preflight checks before doc-ingestion RAG effectiveness exports",
    )
    doc_ingestion_preflight.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    doc_ingestion_preflight.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "phase10d-10a-preflight"))
    doc_ingestion_preflight.add_argument("--dataset-root", type=Path)
    doc_ingestion_preflight.add_argument("--dataset-id", default="doc-ingestion-retrieval-v1")
    doc_ingestion_preflight.add_argument("--postgres-host", default=os.getenv("CHATAGENT_EVAL_POSTGRES_HOST", "localhost"))
    doc_ingestion_preflight.add_argument("--postgres-port", type=int, default=_optional_int("CHATAGENT_EVAL_POSTGRES_PORT") or 5432)
    doc_ingestion_preflight.add_argument("--redis-host", default=os.getenv("CHATAGENT_EVAL_REDIS_HOST", "localhost"))
    doc_ingestion_preflight.add_argument("--redis-port", type=int, default=_optional_int("CHATAGENT_EVAL_REDIS_PORT") or 6379)
    doc_ingestion_preflight.add_argument("--rabbitmq-host", default=os.getenv("CHATAGENT_EVAL_RABBITMQ_HOST", "localhost"))
    doc_ingestion_preflight.add_argument("--rabbitmq-port", type=int, default=_optional_int("CHATAGENT_EVAL_RABBITMQ_PORT") or 5672)
    doc_ingestion_preflight.add_argument("--milvus-host", default=os.getenv("CHATAGENT_EVAL_MILVUS_HOST", "localhost"))
    doc_ingestion_preflight.add_argument("--milvus-port", type=int, default=_optional_int("CHATAGENT_EVAL_MILVUS_PORT") or 19530)
    doc_ingestion_preflight.add_argument("--ollama-base-url", default=os.getenv("CHATAGENT_RAG_EMBEDDING_BASE_URL", "http://127.0.0.1:11434"))
    doc_ingestion_preflight.add_argument("--mineru-base-url", default=os.getenv("CHATAGENT_RAG_VDP_MINERU_BASE_URL", "http://127.0.0.1:8000"))
    doc_ingestion_preflight.add_argument("--reranker-base-url", default=os.getenv("CHATAGENT_RAG_RERANKER_BASE_URL", "http://127.0.0.1:7997"))
    doc_ingestion_preflight.add_argument("--reranker-ready-path", default=os.getenv("CHATAGENT_RAG_RERANKER_READY_PATH", "/ready"))
    doc_ingestion_preflight.add_argument("--ragas-metrics", default=",".join(DEFAULT_10D_B2_RAGAS_METRICS))
    doc_ingestion_preflight.add_argument("--ragas-judge-provider", default=default_ragas_provider)
    doc_ingestion_preflight.add_argument("--ragas-judge-model", default=_default_model(default_ragas_provider, "CHATAGENT_EVAL_RAGAS_LLM_MODEL"))
    doc_ingestion_preflight.add_argument("--ragas-judge-base-url", default=_default_ragas_base_url(default_ragas_provider))
    doc_ingestion_preflight.add_argument("--ragas-embedding-model", default=os.getenv("CHATAGENT_EVAL_RAGAS_EMBEDDING_MODEL"))
    doc_ingestion_preflight.add_argument("--skip-ragas-scoring-smoke", action="store_true")
    doc_ingestion_preflight.add_argument("--expected-samples", type=int)
    doc_ingestion_preflight.add_argument("--control-mode-count", type=int, default=1)
    doc_ingestion_preflight.add_argument("--max-wall-clock-minutes", type=int)
    doc_ingestion_preflight.add_argument("--max-provider-calls", type=int)
    doc_ingestion_preflight.add_argument("--estimated-minutes-per-candidate", type=int)
    doc_ingestion_preflight.add_argument("--candidate-k-targets", default="24,32,50")
    doc_ingestion_preflight.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    doc_ingestion_preflight.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    doc_ingestion_answer = subparsers.add_parser(
        "doc-ingestion-answer",
        help="Generate B3.4 answer rows from retrieval export rows; defaults to full-rag only",
    )
    doc_ingestion_answer.add_argument("--input-jsonl", type=Path, help="Path to retrieval JSONL; if omitted, uses a small fixture")
    doc_ingestion_answer.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    doc_ingestion_answer.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "phase10d-b2-answer"))
    doc_ingestion_answer.add_argument("--dataset-id", default="doc-ingestion-retrieval-v1")
    doc_ingestion_answer.add_argument(
        "--control-modes",
        default="full-rag",
    )
    doc_ingestion_answer.add_argument(
        "--splits",
        default="calibration,development",
        help="Comma-separated split filter. Use holdout only for sealed final verification.",
    )
    doc_ingestion_answer.add_argument("--final-top-k", type=int, default=8)
    doc_ingestion_answer.add_argument("--context-token-budget", type=int, default=6000)
    doc_ingestion_answer.add_argument("--llm-provider", default=default_answer_provider)
    doc_ingestion_answer.add_argument("--llm-model", default=_default_model(default_answer_provider, "CHATAGENT_EVAL_ANSWER_LLM_MODEL"))
    doc_ingestion_answer.add_argument(
        "--llm-base-url",
        default=_default_answer_base_url(default_answer_provider),
    )
    doc_ingestion_answer.add_argument("--llm-temperature", type=float, default=0.0)
    doc_ingestion_answer.add_argument("--llm-max-tokens", type=int, default=1024)
    doc_ingestion_answer.add_argument("--dataset-manifest", type=Path)
    doc_ingestion_answer.add_argument("--split-manifest", type=Path)
    doc_ingestion_answer.add_argument("--max-samples", type=int)
    doc_ingestion_answer.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    doc_ingestion_answer.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    doc_ingestion_promote = subparsers.add_parser(
        "doc-ingestion-promote",
        help="Legacy diagnostic: write a superseded 10d-B2 control-matrix promotion report",
    )
    doc_ingestion_promote.add_argument("--candidate-artifact", required=True, type=Path, help="Path to the candidate run directory")
    doc_ingestion_promote.add_argument("--selection-artifact", required=True, type=Path)
    doc_ingestion_promote.add_argument("--preflight-artifact", required=True, type=Path)
    doc_ingestion_promote.add_argument("--expected-dataset-hash", required=True)
    doc_ingestion_promote.add_argument("--expected-calibration-split-hash", required=True)
    doc_ingestion_promote.add_argument("--expected-development-split-hash", required=True)
    doc_ingestion_promote.add_argument("--expected-holdout-split-hash", required=True)
    doc_ingestion_promote.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    doc_ingestion_promote.add_argument("--run-id", default=os.getenv("CHATAGENT_EVAL_RUN_ID", "phase10d-b2-promotion"))
    doc_ingestion_promote.add_argument("--baseline-context-recall", type=float, default=0.6294)
    doc_ingestion_promote.add_argument("--baseline-phrase-recall", type=float, default=0.8552)
    doc_ingestion_promote.add_argument("--git-branch", default=os.getenv("GIT_BRANCH", "unknown"))
    doc_ingestion_promote.add_argument("--git-sha", default=os.getenv("GIT_COMMIT", "unknown"))
    doc_ingestion_select = subparsers.add_parser(
        "doc-ingestion-select",
        help="Legacy diagnostic: select a superseded 10d-B2 control-matrix candidate",
    )
    doc_ingestion_select.add_argument("--candidate-artifact", action="append", required=True, type=Path)
    doc_ingestion_select.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    doc_ingestion_select.add_argument("--run-id", required=True)
    doc_ingestion_select.add_argument("--expected-dataset-hash", required=True)
    doc_ingestion_select.add_argument("--expected-calibration-split-hash", required=True)
    doc_ingestion_select.add_argument("--expected-development-split-hash", required=True)
    doc_ingestion_bundle = subparsers.add_parser(
        "doc-ingestion-bundle",
        help="Legacy diagnostic: compose superseded 10d-B2 control-matrix evidence",
    )
    doc_ingestion_bundle.add_argument("--retrieval-metrics", required=True, type=Path)
    doc_ingestion_bundle.add_argument("--answer-artifact", required=True, type=Path)
    doc_ingestion_bundle.add_argument("--scored-answer-artifact", required=True, type=Path)
    doc_ingestion_bundle.add_argument("--output-root", default=PROJECT_ROOT / "artifacts" / "eval" / "phase10d", type=Path)
    doc_ingestion_bundle.add_argument("--run-id", required=True)
    doc_ingestion_bundle.add_argument("--cost-evidence", required=True, type=Path)
    doc_ingestion_bundle.add_argument("--max-latency-p95-ms", required=True, type=float)
    doc_ingestion_bundle.add_argument("--max-cost-usd", required=True, type=float)
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
    tune.add_argument(
        "--doc-ingestion-repair-overlay",
        type=Path,
        help="Reviewed calibration/development repair overlay for doc-ingestion-retrieval tuning",
    )
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
            judge_temperature=args.judge_temperature,
            judge_max_tokens=args.judge_max_tokens,
            judge_context_tokens=args.judge_context_tokens,
            judge_timeout_seconds=args.judge_timeout_seconds,
            judge_max_workers=args.judge_max_workers,
            judge_think=args.judge_think,
            response_relevancy_strictness=args.response_relevancy_strictness,
            embedding_model=args.embedding_model,
            embedding_num_gpu=args.embedding_num_gpu,
            batch_size=args.batch_size,
            batch_delay_seconds=args.batch_delay_seconds,
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
    if args.command == "memory-semantic":
        semantic_splits = tuple(split.strip() for split in args.splits.split(",") if split.strip())
        if args.input_kind == "full-export" and not semantic_splits:
            semantic_splits = ("calibration", "development")
        config = MemorySemanticConfig(
            run_id=args.run_id,
            input_kind=args.input_kind,
            judge_provider=args.judge_provider,
            judge_model=args.judge_model,
            judge_base_url=args.judge_base_url,
            repeats=args.repeats,
            judge_max_attempts_per_repeat=args.judge_max_attempts_per_repeat,
            embedding_model=args.embedding_model,
            embedding_base_url=args.embedding_base_url,
            embedding_num_gpu=args.embedding_num_gpu,
            embedding_match_threshold=args.embedding_match_threshold,
            max_samples=args.max_samples,
            splits=semantic_splits,
            targets=tuple(target.strip() for target in args.targets.split(",") if target.strip()),
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_memory_semantic(
            input_path=args.input_path,
            output_root=args.output_root,
            config=config,
            reference_cluster_run_dir=args.reference_cluster_run_dir,
            reuse_reference_semantic_run_dir=args.reuse_reference_semantic_run_dir,
        )
        print(run_dir)
        return 0
    if args.command == "memory-reference-clusters":
        config = MemoryReferenceConfig(
            run_id=args.run_id,
            judge_provider=args.judge_provider,
            judge_model=args.judge_model,
            judge_base_url=args.judge_base_url,
            judge_max_attempts=args.judge_max_attempts,
            max_samples=args.max_samples,
            splits=tuple(split.strip() for split in args.splits.split(",") if split.strip()),
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_memory_reference_clusters(input_path=args.input_path, output_root=args.output_root, config=config)
        print(run_dir)
        return 0
    if args.command == "memory-semantic-analysis":
        run_dir = run_memory_semantic_analysis(
            input_run_dir=args.input_run_dir,
            output_root=args.output_root,
            config=MemorySemanticAnalysisConfig(
                run_id=args.run_id,
                git_branch=args.git_branch,
                git_sha=args.git_sha,
            ),
        )
        print(run_dir)
        return 0
    if args.command == "memory-tuning-subset":
        run_dir = run_memory_tuning_subset(
            source_export_samples=args.source_export_samples,
            semantic_run_dir=args.semantic_run_dir,
            output_root=args.output_root,
            config=MemoryTuningSubsetConfig(
                run_id=args.run_id,
                hard_negative_target=args.hard_negative_target,
                git_branch=args.git_branch,
                git_sha=args.git_sha,
            ),
        )
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
    if args.command == "doc-ingestion-analysis":
        config = DocIngestionAnalysisConfig(
            run_id=args.run_id,
            dataset_id=args.dataset_id,
            top_k=args.top_k,
            candidate_k=args.candidate_k,
            rrf_k=args.rrf_k,
            splits=tuple(split.strip() for split in args.splits.split(",") if split.strip()),
            failed_audit_target=args.failed_audit_target,
            success_audit_target=args.success_audit_target,
        )
        run_dir = run_doc_ingestion_failure_analysis(
            dataset_root=args.dataset_root, output_root=args.output_root, config=config
        )
        print(run_dir)
        return 0
    if args.command == "doc-ingestion-preflight":
        config = DocIngestionPreflightConfig(
            run_id=args.run_id,
            dataset_root=args.dataset_root,
            dataset_id=args.dataset_id,
            postgres_host=args.postgres_host,
            postgres_port=args.postgres_port,
            redis_host=args.redis_host,
            redis_port=args.redis_port,
            rabbitmq_host=args.rabbitmq_host,
            rabbitmq_port=args.rabbitmq_port,
            milvus_host=args.milvus_host,
            milvus_port=args.milvus_port,
            ollama_base_url=args.ollama_base_url,
            mineru_base_url=args.mineru_base_url,
            reranker_base_url=args.reranker_base_url,
            reranker_ready_path=args.reranker_ready_path,
            ragas_metrics=tuple(metric.strip() for metric in args.ragas_metrics.split(",") if metric.strip()),
            ragas_judge_provider=args.ragas_judge_provider,
            ragas_judge_model=args.ragas_judge_model,
            ragas_judge_base_url=args.ragas_judge_base_url,
            ragas_embedding_model=args.ragas_embedding_model,
            ragas_scoring_smoke=not args.skip_ragas_scoring_smoke,
            expected_samples=args.expected_samples,
            control_mode_count=args.control_mode_count,
            max_wall_clock_minutes=args.max_wall_clock_minutes,
            max_provider_calls=args.max_provider_calls,
            estimated_minutes_per_candidate=args.estimated_minutes_per_candidate,
            candidate_k_targets=_int_tuple(args.candidate_k_targets),
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_doc_ingestion_preflight(output_root=args.output_root, config=config)
        print(run_dir)
        return 0
    if args.command == "doc-ingestion-answer":
        rows = _read_jsonl(args.input_jsonl) if args.input_jsonl else _smoke_fixture_rows()
        dataset_hash, split_hashes = _answer_provenance(args.dataset_manifest, args.split_manifest)
        config = AnswerHarnessConfig(
            run_id=args.run_id,
            dataset_id=args.dataset_id,
            control_modes=tuple(m.strip() for m in args.control_modes.split(",") if m.strip()),
            splits=tuple(split.strip() for split in args.splits.split(",") if split.strip()),
            final_top_k=args.final_top_k,
            context_token_budget=args.context_token_budget,
            llm_provider=args.llm_provider,
            llm_model=args.llm_model,
            llm_base_url=args.llm_base_url,
            llm_temperature=args.llm_temperature,
            llm_max_tokens=args.llm_max_tokens,
            dataset_hash=dataset_hash,
            split_hashes=tuple(split_hashes.items()),
            source_artifact=str(args.input_jsonl) if args.input_jsonl else None,
            max_samples=args.max_samples,
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        run_dir = run_answer_harness(rows=rows, output_root=args.output_root, config=config)
        print(run_dir)
        return 0
    if args.command == "doc-ingestion-promote":
        run_dir = write_10d_b2_promotion_report(
            candidate_artifact=args.candidate_artifact,
            selection_artifact=args.selection_artifact,
            preflight_artifact=args.preflight_artifact,
            output_root=args.output_root,
            run_id=args.run_id,
            expected_dataset_hash=args.expected_dataset_hash,
            expected_search_split_hashes={
                "calibration": args.expected_calibration_split_hash,
                "development": args.expected_development_split_hash,
            },
            expected_holdout_split_hash=args.expected_holdout_split_hash,
            baseline_context_recall=args.baseline_context_recall,
            baseline_phrase_recall=args.baseline_phrase_recall,
            git_branch=args.git_branch,
            git_sha=args.git_sha,
        )
        print(run_dir)
        return 0
    if args.command == "doc-ingestion-select":
        run_dir = write_10d_b2_joint_selection(
            candidate_artifacts=args.candidate_artifact,
            output_root=args.output_root,
            run_id=args.run_id,
            expected_dataset_hash=args.expected_dataset_hash,
            expected_search_split_hashes={
                "calibration": args.expected_calibration_split_hash,
                "development": args.expected_development_split_hash,
            },
        )
        print(run_dir)
        return 0
    if args.command == "doc-ingestion-bundle":
        run_dir = write_10d_b2_evidence_bundle(
            retrieval_metrics_path=args.retrieval_metrics,
            answer_artifact=args.answer_artifact,
            scored_answer_artifact=args.scored_answer_artifact,
            cost_evidence_path=args.cost_evidence,
            output_root=args.output_root,
            run_id=args.run_id,
            max_latency_p95_ms=args.max_latency_p95_ms,
            max_cost_usd=args.max_cost_usd,
        )
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
            doc_ingestion_repair_overlay=args.doc_ingestion_repair_overlay,
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


def _int_tuple(value: str) -> tuple[int, ...]:
    return tuple(int(item.strip()) for item in value.split(",") if item.strip())


def _read_jsonl(path: Path) -> list[dict]:
    import json
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def _answer_provenance(dataset_manifest_path: Path | None, split_manifest_path: Path | None) -> tuple[str | None, dict[str, str]]:
    if dataset_manifest_path is None and split_manifest_path is None:
        return None, {}
    if dataset_manifest_path is None or split_manifest_path is None:
        raise ValueError("--dataset-manifest and --split-manifest must be provided together")
    import json
    dataset_manifest = json.loads(dataset_manifest_path.read_text(encoding="utf-8"))
    split_manifest = json.loads(split_manifest_path.read_text(encoding="utf-8"))
    dataset_hash = str(dataset_manifest.get("datasetHash") or "")
    split_hashes = {
        str(split): str(details.get("groupHash") or "")
        for split, details in (split_manifest.get("splits") or {}).items()
        if details.get("groupHash")
    }
    if not dataset_hash or not split_hashes:
        raise ValueError("dataset and split manifests must contain datasetHash and split groupHash values")
    return dataset_hash, split_hashes


def _smoke_fixture_rows() -> list[dict]:
    return [
        {
            "sampleId": "smoke-1",
            "datasetId": "doc-ingestion-retrieval-v1",
            "sourceGroupId": "smoke-group",
            "split": "calibration",
            "userInput": "What was the company total revenue for fiscal year 2023?",
            "referenceContextIds": ["chunk-a"],
            "metadata": {
                "format": "SEC_HTML",
                "referenceContent": "Total revenue for fiscal year 2023 was $42.3 million.",
                "generationMethod": "template-question",
                "retrievedContexts": [
                    {"chunkId": "chunk-a", "documentId": "doc-1", "content": "Total revenue for fiscal year 2023 was $42.3 million.", "score": 0.95},
                ],
                "candidateContexts": [
                    {"chunkId": "chunk-a", "documentId": "doc-1", "content": "Total revenue for fiscal year 2023 was $42.3 million.", "score": 0.95},
                ],
                "retrievalLatency": {"candidatePathMs": 5.0, "finalProductionPathMs": 8.0},
            },
        },
        {
            "sampleId": "smoke-2",
            "datasetId": "doc-ingestion-retrieval-v1",
            "sourceGroupId": "smoke-group-2",
            "split": "calibration",
            "userInput": "How many shares were outstanding as of the filing date?",
            "referenceContextIds": ["chunk-b"],
            "metadata": {
                "format": "SEC_HTML",
                "referenceContent": "As of October 31, 2023, the registrant had 152,847,291 shares outstanding.",
                "generationMethod": "template-question",
                "retrievedContexts": [
                    {"chunkId": "chunk-b", "documentId": "doc-2", "content": "The registrant had 152,847,291 shares outstanding.", "score": 0.91},
                ],
                "candidateContexts": [
                    {"chunkId": "chunk-b", "documentId": "doc-2", "content": "The registrant had 152,847,291 shares outstanding.", "score": 0.91},
                ],
                "retrievalLatency": {"candidatePathMs": 5.0, "finalProductionPathMs": 8.0},
            },
        },
    ]


if __name__ == "__main__":
    raise SystemExit(main())
