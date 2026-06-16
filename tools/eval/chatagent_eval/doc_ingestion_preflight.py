"""Preflight checks for 10d-B2 doc-ingestion RAG effectiveness runs."""

from __future__ import annotations

import importlib
import importlib.metadata
import json
import os
import socket
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from chatagent_eval.reports import SAFE_RUN_ID, write_json_artifact

DEFAULT_RAGAS_METRICS = (
    "faithfulness",
    "factual_correctness",
)

ENV_REQUIREMENTS = {
    "databaseUrl": {"variables": ("CHATAGENT_DB_URL",), "required": False, "default": "application.yaml default"},
    "databaseUsername": {"variables": ("CHATAGENT_DB_USERNAME",), "required": False, "default": "application.yaml default"},
    "databasePassword": {"variables": ("CHATAGENT_DB_PASSWORD",), "required": True},
    "ragasJudgeApiKey": {
        "variables": (
            "CHATAGENT_ZHIPUAI_API_KEY",
            "CHATAGENT_ZHIPUAI_API_KEY_2",
            "CHATAGENT_ZHIPUAI_SECONDARY_API_KEY",
            "CHATAGENT_ZAI_CODING_API_KEY",
            "CHATAGENT_ZAI_API_KEY",
            "CHATAGENT_EVAL_RAGAS_LLM_API_KEY",
            "CHATAGENT_DEEPSEEK_API_KEY",
        ),
        "required": True,
    },
    "answerLlmApiKey": {
        "variables": (
            "CHATAGENT_ZHIPUAI_API_KEY",
            "CHATAGENT_ZHIPUAI_API_KEY_2",
            "CHATAGENT_ZHIPUAI_SECONDARY_API_KEY",
            "CHATAGENT_ZAI_CODING_API_KEY",
            "CHATAGENT_ZAI_API_KEY",
            "CHATAGENT_EVAL_ANSWER_LLM_API_KEY",
            "CHATAGENT_DEEPSEEK_API_KEY",
        ),
        "required": True,
    },
}


@dataclass(frozen=True)
class DocIngestionPreflightConfig:
    run_id: str
    dataset_root: Path | None = None
    dataset_id: str = "doc-ingestion-retrieval-v1"
    git_branch: str = "unknown"
    git_sha: str = "unknown"
    postgres_host: str = "localhost"
    postgres_port: int = 5432
    redis_host: str = "localhost"
    redis_port: int = 6379
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    ollama_base_url: str = "http://127.0.0.1:11434"
    mineru_base_url: str = "http://127.0.0.1:8000"
    reranker_base_url: str = "http://127.0.0.1:7997"
    reranker_ready_path: str = "/ready"
    ragas_metrics: tuple[str, ...] = DEFAULT_RAGAS_METRICS
    ragas_judge_provider: str = "deepseek"
    ragas_judge_model: str = "deepseek-chat"
    ragas_judge_base_url: str | None = None
    ragas_embedding_model: str | None = None
    ragas_scoring_smoke: bool = True
    expected_samples: int | None = None
    control_mode_count: int = 1
    max_wall_clock_minutes: int | None = None
    max_provider_calls: int | None = None
    estimated_minutes_per_candidate: int | None = None
    candidate_k_targets: tuple[int, ...] = (24, 32, 50)

    def __post_init__(self) -> None:
        if not SAFE_RUN_ID.fullmatch(self.run_id):
            raise ValueError(f"unsafe run id: {self.run_id}")
        if self.control_mode_count <= 0:
            raise ValueError("control_mode_count must be positive")
        if not self.ragas_metrics:
            raise ValueError("at least one RAGAS metric is required")
        if any(value <= 0 for value in self.candidate_k_targets):
            raise ValueError("candidate_k_targets must be positive")

    def as_dict(self) -> dict[str, Any]:
        return {
            "datasetId": self.dataset_id,
            "datasetRoot": str(self.dataset_root) if self.dataset_root else None,
            "ragasMetrics": list(self.ragas_metrics),
            "ragasJudgeProvider": self.ragas_judge_provider,
            "ragasJudgeModel": self.ragas_judge_model,
            "ragasEmbeddingModel": self.ragas_embedding_model,
            "ragasScoringSmoke": self.ragas_scoring_smoke,
            "expectedSamples": self.expected_samples,
            "controlModeCount": self.control_mode_count,
            "maxWallClockMinutes": self.max_wall_clock_minutes,
            "maxProviderCalls": self.max_provider_calls,
            "estimatedMinutesPerCandidate": self.estimated_minutes_per_candidate,
            "candidateKTargets": list(self.candidate_k_targets),
        }


ProbeTcp = Callable[[str, int, float], tuple[str, str | None]]
ProbeHttp = Callable[[str, float], tuple[str, str | None]]
ProbeMetrics = Callable[[Sequence[str]], Mapping[str, Any]]
ProbeRagasScoring = Callable[["DocIngestionPreflightConfig"], Mapping[str, Any]]


def run_doc_ingestion_preflight(
    *,
    output_root: Path,
    config: DocIngestionPreflightConfig,
    probe_tcp: ProbeTcp | None = None,
    probe_http: ProbeHttp | None = None,
    probe_metrics: ProbeMetrics | None = None,
    probe_ragas_scoring: ProbeRagasScoring | None = None,
) -> Path:
    probe_tcp = probe_tcp or _probe_tcp
    probe_http = probe_http or _probe_http
    probe_metrics = probe_metrics or _probe_ragas_metrics
    probe_ragas_scoring = probe_ragas_scoring or _probe_ragas_scoring
    run_dir = (output_root.resolve() / config.run_id).resolve()
    if not run_dir.is_relative_to(output_root.resolve()):
        raise ValueError(f"run directory escapes output root: {config.run_id}")
    if run_dir.exists():
        raise ValueError(f"preflight artifact root already exists: {run_dir}")
    run_dir.mkdir(parents=True)

    checks: list[dict[str, Any]] = []
    checks.extend(_env_checks())
    checks.extend(_dataset_checks(config))
    checks.extend(_artifact_checks(run_dir))
    checks.extend(_service_checks(config, probe_tcp=probe_tcp, probe_http=probe_http))
    checks.extend(_ragas_checks(config, probe_metrics=probe_metrics, probe_ragas_scoring=probe_ragas_scoring))
    candidate_k_decision = _candidate_k_decision(config)
    checks.append(
        {
            "id": "budget.candidateK",
            "status": "pass" if candidate_k_decision["approved"] else "fail",
            "candidateK": candidate_k_decision,
        }
    )

    status = _overall_status(checks)
    preflight = {
        "runId": config.run_id,
        "suite": "doc-ingestion-rag-effectiveness",
        "mode": "preflight",
        "status": status,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "git": {"branch": config.git_branch, "sha": config.git_sha},
        "config": config.as_dict(),
        "checks": checks,
    }
    report = {
        "runId": config.run_id,
        "suite": "doc-ingestion-rag-effectiveness",
        "mode": "preflight",
        "status": status,
        "checks": {
            "total": len(checks),
            "pass": sum(1 for check in checks if check["status"] == "pass"),
            "warn": sum(1 for check in checks if check["status"] == "warn"),
            "fail": sum(1 for check in checks if check["status"] == "fail"),
        },
    }
    write_json_artifact(run_dir / "preflight.json", preflight)
    write_json_artifact(run_dir / "report.json", report)
    return run_dir


def _env_checks() -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    for requirement, metadata in ENV_REQUIREMENTS.items():
        variable_names = tuple(metadata["variables"])
        present = [name for name in variable_names if bool(os.getenv(name))]
        required = bool(metadata["required"])
        status = "pass" if present or not required else "fail"
        checks.append(
            {
                "id": f"env.{requirement}",
                "status": status,
                "variables": list(variable_names),
                "presentVariables": present,
                "required": required,
                "default": metadata.get("default"),
            }
        )
    return checks


def _dataset_checks(config: DocIngestionPreflightConfig) -> list[dict[str, Any]]:
    if config.dataset_root is None:
        return [{"id": "dataset.manifest", "status": "warn", "message": "datasetRoot not provided"}]
    manifest_path = config.dataset_root / "manifests" / "datasets" / f"{config.dataset_id}.json"
    split_path = config.dataset_root / "manifests" / "splits" / f"{config.dataset_id}.json"
    checks = [
        _path_check("dataset.manifest", manifest_path),
        _path_check("dataset.splitManifest", split_path),
    ]
    if manifest_path.exists():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            local_path = manifest.get("localPath")
            checks.append(
                _path_check(
                    "dataset.localPath",
                    config.dataset_root / str(local_path),
                    extra={"localPath": local_path},
                )
            )
        except (OSError, json.JSONDecodeError) as exc:
            checks.append({"id": "dataset.localPath", "status": "fail", "message": str(exc)})
    return checks


def _artifact_checks(run_dir: Path) -> list[dict[str, Any]]:
    return [
        {
            "id": "artifact.rootUnique",
            "status": "pass",
            "path": str(run_dir),
        }
    ]


def _service_checks(config: DocIngestionPreflightConfig, *, probe_tcp: ProbeTcp, probe_http: ProbeHttp) -> list[dict[str, Any]]:
    return [
        _tcp_check("service.postgresql", config.postgres_host, config.postgres_port, probe_tcp),
        _tcp_check("service.redis", config.redis_host, config.redis_port, probe_tcp),
        _tcp_check("service.rabbitmq", config.rabbitmq_host, config.rabbitmq_port, probe_tcp),
        _tcp_check("service.milvus", config.milvus_host, config.milvus_port, probe_tcp),
        _http_check("service.ollama", _join_url(config.ollama_base_url, "/api/tags"), probe_http),
        _http_check("service.mineru", _join_url(config.mineru_base_url, "/health"), probe_http),
        _http_check("service.reranker", _join_url(config.reranker_base_url, config.reranker_ready_path), probe_http),
    ]


def _ragas_checks(
    config: DocIngestionPreflightConfig,
    *,
    probe_metrics: ProbeMetrics,
    probe_ragas_scoring: ProbeRagasScoring,
) -> list[dict[str, Any]]:
    try:
        result = dict(probe_metrics(config.ragas_metrics))
    except Exception as exc:  # pragma: no cover - defensive boundary for optional runtime
        return [{"id": "ragas.runtime", "status": "fail", "message": type(exc).__name__}]
    unavailable = list(result.get("unavailableMetrics") or [])
    version = result.get("version")
    checks = [
        {
            "id": "ragas.runtime",
            "status": "pass" if version else "fail",
            "version": version,
        },
        {
            "id": "ragas.metrics",
            "status": "pass" if not unavailable else "fail",
            "requestedMetrics": list(config.ragas_metrics),
            "unavailableMetrics": unavailable,
            "metricClasses": dict(result.get("metricClasses") or {}),
        },
    ]
    if config.ragas_scoring_smoke:
        try:
            scoring = dict(probe_ragas_scoring(config))
        except Exception as exc:  # pragma: no cover - defensive boundary for optional runtime
            scoring = {"status": "fail", "message": type(exc).__name__}
        scores = dict(scoring.get("scores") or {})
        missing = [metric for metric in config.ragas_metrics if not _is_numeric_metric(scores.get(metric))]
        checks.append(
            {
                "id": "ragas.scoringSmoke",
                "status": "pass" if scoring.get("status") == "pass" and not missing else "fail",
                "requestedMetrics": list(config.ragas_metrics),
                "numericMetrics": sorted(metric for metric, value in scores.items() if _is_numeric_metric(value)),
                "missingNumericMetrics": missing,
                "message": scoring.get("message"),
            }
        )
    return checks


def _candidate_k_decision(config: DocIngestionPreflightConfig) -> dict[str, Any]:
    estimated_provider_calls_per_candidate = None
    if config.expected_samples is not None:
        estimated_provider_calls_per_candidate = (
            config.expected_samples * config.control_mode_count * (1 + len(config.ragas_metrics))
        )

    approved: list[int] = []
    skipped: list[dict[str, Any]] = []
    prefix_open = True
    for value in config.candidate_k_targets:
        candidate_count = len(approved) + 1
        cumulative_provider_calls = (
            estimated_provider_calls_per_candidate * candidate_count
            if estimated_provider_calls_per_candidate is not None
            else None
        )
        cumulative_wall_clock_minutes = (
            config.estimated_minutes_per_candidate * candidate_count
            if config.estimated_minutes_per_candidate is not None
            else None
        )
        reasons: list[str] = []
        if config.max_provider_calls is None or cumulative_provider_calls is None:
            reasons.append("maxProviderCalls and expectedSamples are required")
        elif cumulative_provider_calls > config.max_provider_calls:
            reasons.append("cumulative estimated provider calls exceed maxProviderCalls")
        if config.max_wall_clock_minutes is None or cumulative_wall_clock_minutes is None:
            reasons.append("maxWallClockMinutes and estimatedMinutesPerCandidate are required")
        elif cumulative_wall_clock_minutes > config.max_wall_clock_minutes:
            reasons.append("cumulative estimated wall-clock minutes exceed maxWallClockMinutes")
        if reasons or not prefix_open:
            prefix_open = False
            skipped.append(
                {
                    "candidateK": value,
                    "reason": "; ".join(reasons) if reasons else "earlier candidateK value exceeded the declared budget",
                    "cumulativeEstimatedProviderCalls": cumulative_provider_calls,
                    "maxProviderCalls": config.max_provider_calls,
                    "cumulativeEstimatedWallClockMinutes": cumulative_wall_clock_minutes,
                    "maxWallClockMinutes": config.max_wall_clock_minutes,
                }
            )
            continue
        approved.append(value)
    return {
        "approved": approved,
        "skipped": skipped,
        "estimatedProviderCallsPerCandidate": estimated_provider_calls_per_candidate,
        "approvedEstimatedProviderCallsTotal": (
            estimated_provider_calls_per_candidate * len(approved)
            if estimated_provider_calls_per_candidate is not None
            else None
        ),
        "maxWallClockMinutes": config.max_wall_clock_minutes,
        "estimatedMinutesPerCandidate": config.estimated_minutes_per_candidate,
        "approvedEstimatedWallClockMinutesTotal": (
            config.estimated_minutes_per_candidate * len(approved)
            if config.estimated_minutes_per_candidate is not None
            else None
        ),
    }


def _path_check(check_id: str, path: Path, extra: Mapping[str, Any] | None = None) -> dict[str, Any]:
    return {
        "id": check_id,
        "status": "pass" if path.exists() else "fail",
        "path": str(path),
    } | dict(extra or {})


def _tcp_check(check_id: str, host: str, port: int, probe_tcp: ProbeTcp) -> dict[str, Any]:
    status, message = probe_tcp(host, port, 2.0)
    result = {"id": check_id, "status": status, "target": f"{host}:{port}"}
    if message:
        result["message"] = message
    return result


def _http_check(check_id: str, url: str, probe_http: ProbeHttp) -> dict[str, Any]:
    status, message = probe_http(url, 3.0)
    result = {"id": check_id, "status": status, "url": _redact_url(url)}
    if message:
        result["message"] = message
    return result


def _probe_tcp(host: str, port: int, timeout: float) -> tuple[str, str | None]:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return "pass", None
    except OSError as exc:
        return "fail", type(exc).__name__


def _probe_http(url: str, timeout: float) -> tuple[str, str | None]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            status = getattr(response, "status", 200)
            return ("pass" if 200 <= status < 500 else "fail"), f"HTTP {status}"
    except Exception as exc:
        return "fail", type(exc).__name__


def _probe_ragas_metrics(metrics: Sequence[str]) -> Mapping[str, Any]:
    try:
        version = importlib.metadata.version("ragas")
    except importlib.metadata.PackageNotFoundError:
        return {"version": None, "metricClasses": {}, "unavailableMetrics": list(metrics)}

    metric_classes: dict[str, str] = {}
    unavailable: list[str] = []
    for metric in metrics:
        class_name = _metric_class_name(metric)
        if _can_import_metric_class(class_name):
            metric_classes[metric] = class_name
        else:
            unavailable.append(metric)
    return {"version": version, "metricClasses": metric_classes, "unavailableMetrics": unavailable}


def _probe_ragas_scoring(config: DocIngestionPreflightConfig) -> Mapping[str, Any]:
    from chatagent_eval.ragas_runner import OfficialRagasEvaluator, RagasRunnerConfig

    record = {
        "sample_id": "preflight-ragas-smoke",
        "user_input": "What revenue is stated in the provided context?",
        "response": "The provided context states that revenue was 42 million.",
        "reference": "Revenue was 42 million.",
        "retrieved_contexts": ["Revenue was 42 million in fiscal year 2023."],
        "reference_contexts": ["Revenue was 42 million in fiscal year 2023."],
    }
    evaluation = OfficialRagasEvaluator().evaluate(
        [record],
        RagasRunnerConfig(
            run_id="preflight-ragas-smoke",
            metric_names=config.ragas_metrics,
            judge_provider=config.ragas_judge_provider,
            judge_model=config.ragas_judge_model,
            judge_base_url=config.ragas_judge_base_url,
            embedding_model=config.ragas_embedding_model,
            warn_on_unavailable=False,
        ),
    )
    scores = dict(evaluation.rows[0].metrics) if evaluation.rows else {}
    return {"status": "pass", "scores": scores}


def _metric_class_name(metric: str) -> str:
    return {
        "context_precision": "ContextPrecision",
        "context_recall": "ContextRecall",
        "faithfulness": "Faithfulness",
        "response_relevancy": "ResponseRelevancy",
        "factual_correctness": "FactualCorrectness",
        "semantic_similarity": "SemanticSimilarity",
    }.get(metric, metric)


def _can_import_metric_class(class_name: str) -> bool:
    for module_name in ("ragas.metrics.collections", "ragas.metrics"):
        try:
            module = importlib.import_module(module_name)
        except ImportError:
            continue
        if hasattr(module, class_name):
            return True
    return False


def _join_url(base_url: str, path: str) -> str:
    base = base_url.rstrip("/")
    suffix = path if path.startswith("/") else f"/{path}"
    return f"{base}{suffix}"


def _redact_url(url: str) -> str:
    parsed = urlparse(url)
    netloc = parsed.hostname or ""
    if parsed.port:
        netloc = f"{netloc}:{parsed.port}"
    return parsed._replace(netloc=netloc).geturl()


def _overall_status(checks: Sequence[Mapping[str, Any]]) -> str:
    if any(check.get("status") == "fail" for check in checks):
        return "fail"
    if any(check.get("status") == "warn" for check in checks):
        return "warn"
    return "pass"


def _is_numeric_metric(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)
