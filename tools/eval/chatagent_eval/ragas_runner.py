"""Official Ragas runner for ChatAgent v2 evaluation samples."""

from __future__ import annotations

import json
import math
import os
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol

from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import build_manifest, build_report, write_json_artifact, write_run_artifacts

DEFAULT_RAGAS_METRICS = ("faithfulness", "response_relevancy", "context_precision", "context_recall")
REQUIRED_FIELDS = {
    "context_precision": ("user_input", "retrieved_contexts", "reference"),
    "context_recall": ("user_input", "retrieved_contexts", "reference"),
    "faithfulness": ("user_input", "retrieved_contexts", "response"),
    "response_relevancy": ("user_input", "response"),
    "factual_correctness": ("user_input", "response", "reference"),
}
ARTIFACT_FILES = ("manifest.json", "metrics.json", "samples.jsonl", "failures.jsonl", "report.json")


class RagasUnavailable(RuntimeError):
    """Raised when the optional Ragas runtime or Provider configuration is not available."""


@dataclass(frozen=True)
class RagasRunnerConfig:
    run_id: str
    mode: str = "ragas-smoke"
    metric_names: tuple[str, ...] = DEFAULT_RAGAS_METRICS
    failure_mode: str = "structured"
    max_samples: int | None = None
    warn_on_unavailable: bool = True
    judge_provider: str = "deepseek"
    judge_model: str = "deepseek-chat"
    judge_base_url: str | None = None
    judge_api_key: str | None = None
    embedding_model: str | None = None
    experiment_name: str = "chatagent-ragas"
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.failure_mode not in {"nan", "structured"}:
            raise ValueError("failure_mode must be 'nan' or 'structured'")
        if not self.metric_names:
            raise ValueError("at least one Ragas metric is required")

    def as_dict(self) -> dict[str, Any]:
        return {
            "metricNames": list(self.metric_names),
            "failureMode": self.failure_mode,
            "maxSamples": self.max_samples,
            "warnOnUnavailable": self.warn_on_unavailable,
            "judgeProvider": self.judge_provider,
            "judgeModel": self.judge_model,
            "judgeBaseUrl": self.judge_base_url,
            "embeddingModel": self.embedding_model,
            "experimentName": self.experiment_name,
        }


@dataclass(frozen=True)
class RagasScoreRow:
    sample_id: str
    metrics: Mapping[str, float | None]
    failures: tuple[Mapping[str, Any], ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class RagasEvaluation:
    rows: tuple[RagasScoreRow, ...]


class RagasEvaluator(Protocol):
    def evaluate(self, records: Sequence[Mapping[str, Any]], config: RagasRunnerConfig) -> RagasEvaluation:
        ...


def run_ragas(
    *,
    input_run_dir: Path,
    output_root: Path,
    config: RagasRunnerConfig,
    evaluator: RagasEvaluator | None = None,
) -> Path:
    source_manifest, deterministic_metrics, source_samples = load_v2_run(input_run_dir, config.max_samples)
    ragas_records, converted_samples, conversion_failures = to_ragas_records(
        source_samples,
        config.metric_names,
        failure_mode=config.failure_mode,
    )
    config_dict = config.as_dict() | {
        "inputRunId": source_manifest["runId"],
        "inputSuite": source_manifest["suite"],
        "inputConfigFingerprint": source_manifest["configFingerprint"],
    }
    fingerprint = config_fingerprint(config_dict)
    models = {
        "ragasLlmProvider": config.judge_provider,
        "ragasLlmModel": config.judge_model,
    }
    if config.embedding_model:
        models["ragasEmbeddingModel"] = config.embedding_model

    ragas_evaluation = RagasEvaluation(())
    unavailable_failure: dict[str, Any] | None = None
    if ragas_records:
        try:
            ragas_evaluation = (evaluator or OfficialRagasEvaluator()).evaluate(ragas_records, config)
        except RagasUnavailable as exception:
            if not config.warn_on_unavailable:
                raise
            unavailable_failure = {
                "sampleId": None,
                "metric": None,
                "errorCategory": "ragas_unavailable",
                "message": str(exception),
            }

    scored_samples, ragas_failures = merge_sample_scores(
        converted_samples,
        ragas_evaluation,
        config.metric_names,
        failure_mode=config.failure_mode,
    )
    failures = [*conversion_failures, *ragas_failures]
    if unavailable_failure is not None:
        failures.append(unavailable_failure)

    deterministic_prefixed = {f"deterministic.{key}": value for key, value in deterministic_metrics.items() if _is_metric_value(value)}
    ragas_metrics = aggregate_ragas_metrics(ragas_evaluation.rows, config.metric_names)
    merged_report_metrics = deterministic_prefixed | {f"ragas.{key}": value for key, value in ragas_metrics.items()}
    has_null_ragas_metric = any(value is None for value in ragas_metrics.values())
    status = "warn" if failures or has_null_ragas_metric else "pass"

    manifest = build_manifest(
        run_id=config.run_id,
        suite="ragas",
        mode=config.mode,
        timestamp=datetime.now(timezone.utc).isoformat(),
        git_branch=config.git_branch,
        git_sha=config.git_sha,
        dataset_id=source_manifest["datasetId"],
        dataset_hash=source_manifest["datasetHash"],
        config=config_dict,
        config_fingerprint=fingerprint,
        models=models,
        artifact_files=ARTIFACT_FILES,
    )
    metrics = {
        "status": status,
        "deterministic": deterministic_prefixed,
        "ragas": ragas_metrics,
        "merged": merged_report_metrics,
    }
    report = build_report(
        run_id=config.run_id,
        suite="ragas",
        mode=config.mode,
        status=status,
        dataset_id=source_manifest["datasetId"],
        dataset_hash=source_manifest["datasetHash"],
        config_fingerprint=fingerprint,
        metrics=merged_report_metrics,
        threshold_results=[],
    )
    run_dir = write_run_artifacts(output_root, manifest, metrics, scored_samples, failures)
    write_json_artifact(run_dir / "report.json", report)
    return run_dir


def load_v2_run(input_run_dir: Path, max_samples: int | None = None) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    manifest = _read_json(input_run_dir / "manifest.json")
    metrics = _read_json(input_run_dir / "metrics.json")
    samples = list(_read_jsonl(input_run_dir / "samples.jsonl", max_items=max_samples))
    return manifest, metrics, samples


def to_ragas_records(
    samples: Sequence[Mapping[str, Any]],
    metric_names: Sequence[str],
    failure_mode: str = "structured",
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    records: list[dict[str, Any]] = []
    converted_samples: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    required = sorted({field for metric in metric_names for field in REQUIRED_FIELDS.get(metric, ())})
    for sample in samples:
        sample_id = str(sample["sampleId"])
        record = _to_ragas_record(sample)
        missing = [field for field in required if not _has_ragas_field(record, field)]
        converted = dict(sample)
        metadata = dict(converted.get("metadata") or {})
        metadata["ragasRequiredFields"] = required
        if missing:
            metadata["ragasMissingFields"] = missing
            if failure_mode == "nan":
                metadata["ragasScores"] = {metric_name: None for metric_name in metric_names}
        converted["metadata"] = metadata
        converted_samples.append(converted)
        if missing:
            if failure_mode == "structured":
                failures.append(
                    {
                        "sampleId": sample_id,
                        "metric": None,
                        "errorCategory": "missing_required_ragas_fields",
                        "missingFields": missing,
                    }
                )
            continue
        records.append(record)
    return records, converted_samples, failures


def merge_sample_scores(
    samples: Sequence[Mapping[str, Any]],
    evaluation: RagasEvaluation,
    metric_names: Sequence[str],
    failure_mode: str = "structured",
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    scores_by_sample = {row.sample_id: row for row in evaluation.rows}
    scored_samples: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    for sample in samples:
        sample_id = str(sample["sampleId"])
        scored = dict(sample)
        metadata = dict(scored.get("metadata") or {})
        row = scores_by_sample.get(sample_id)
        if row is not None:
            row_metrics = {name: _json_metric(row.metrics.get(name)) for name in metric_names}
            metadata["ragasScores"] = row_metrics
            for metric_name, metric_value in row.metrics.items():
                if failure_mode == "structured" and metric_name in metric_names and _is_nan(metric_value):
                    failure = {
                        "sampleId": sample_id,
                        "metric": metric_name,
                        "errorCategory": "ragas_nan",
                    }
                    if row.failures:
                        failure["details"] = list(row.failures)
                    failures.append(failure)
            if failure_mode == "structured":
                failures.extend(dict(item) | {"sampleId": sample_id} for item in row.failures)
        scored["metadata"] = metadata
        scored_samples.append(scored)
    return scored_samples, failures


def aggregate_ragas_metrics(rows: Sequence[RagasScoreRow], metric_names: Sequence[str]) -> dict[str, float | None]:
    aggregates: dict[str, float | None] = {}
    for metric_name in metric_names:
        values = [_json_metric(row.metrics.get(metric_name)) for row in rows]
        numeric = [value for value in values if value is not None]
        aggregates[metric_name] = sum(numeric) / len(numeric) if numeric else None
    return aggregates


class OfficialRagasEvaluator:
    def evaluate(self, records: Sequence[Mapping[str, Any]], config: RagasRunnerConfig) -> RagasEvaluation:
        try:
            from openai import AsyncOpenAI
            from ragas import EvaluationDataset, evaluate
            from ragas.llms import llm_factory
        except ImportError as exception:
            raise RagasUnavailable("Install the eval extra with `pip install -e .[ragas]` to run official Ragas metrics") from exception

        api_key = config.judge_api_key or _provider_api_key(config.judge_provider)
        if not api_key:
            raise RagasUnavailable(f"Missing API key for Ragas judge provider: {config.judge_provider}")
        base_url = config.judge_base_url or _provider_base_url(config.judge_provider)
        client_kwargs = {"api_key": api_key}
        if base_url:
            client_kwargs["base_url"] = base_url
        llm = llm_factory(config.judge_model, client=AsyncOpenAI(**client_kwargs))
        embeddings = _build_embeddings(config.embedding_model)
        metrics = [_build_metric(name, llm=llm, embeddings=embeddings) for name in config.metric_names]
        dataset = EvaluationDataset.from_list(list(records))
        result = evaluate(
            dataset=dataset,
            metrics=metrics,
            llm=llm,
            embeddings=embeddings,
            experiment_name=config.experiment_name,
            raise_exceptions=False,
            show_progress=False,
        )
        return _evaluation_from_official_result(result, records, config.metric_names)


def _build_metric(name: str, *, llm: Any, embeddings: Any) -> Any:
    try:
        from ragas.metrics.collections import ContextPrecision, ContextRecall, Faithfulness, FactualCorrectness, ResponseRelevancy

        factories = {
            "context_precision": lambda: ContextPrecision(llm=llm),
            "context_recall": lambda: ContextRecall(llm=llm),
            "faithfulness": lambda: Faithfulness(llm=llm),
            "response_relevancy": lambda: ResponseRelevancy(llm=llm, embeddings=embeddings),
            "factual_correctness": lambda: FactualCorrectness(llm=llm),
        }
        if name in factories:
            return factories[name]()
    except ImportError:
        pass

    from ragas.metrics import Faithfulness, LLMContextPrecisionWithReference, LLMContextRecall

    factories = {
        "context_precision": lambda: LLMContextPrecisionWithReference(llm=llm),
        "context_recall": lambda: LLMContextRecall(llm=llm),
        "faithfulness": lambda: Faithfulness(llm=llm),
    }
    if name == "response_relevancy":
        try:
            from ragas.metrics import ResponseRelevancy
        except ImportError:
            from ragas.metrics import AnswerRelevancy as ResponseRelevancy
        return ResponseRelevancy(llm=llm, embeddings=embeddings)
    if name == "factual_correctness":
        from ragas.metrics import FactualCorrectness

        return FactualCorrectness(llm=llm)
    if name not in factories:
        raise ValueError(f"unsupported Ragas metric: {name}")
    return factories[name]()


def _build_embeddings(embedding_model: str | None) -> Any:
    if not embedding_model:
        return None
    try:
        from ragas.embeddings.base import embedding_factory
    except ImportError:
        return None
    return embedding_factory(model=embedding_model)


def _evaluation_from_official_result(
    result: Any,
    records: Sequence[Mapping[str, Any]],
    metric_names: Sequence[str],
) -> RagasEvaluation:
    raw_rows: list[Mapping[str, Any]]
    if hasattr(result, "to_pandas"):
        raw_rows = result.to_pandas().to_dict(orient="records")
    elif hasattr(result, "scores"):
        raw_rows = list(result.scores)
    else:
        raw_rows = []
    rows: list[RagasScoreRow] = []
    for index, record in enumerate(records):
        raw = raw_rows[index] if index < len(raw_rows) else {}
        metrics = {name: _extract_metric(raw, name) for name in metric_names}
        rows.append(RagasScoreRow(sample_id=str(record["sample_id"]), metrics=metrics))
    return RagasEvaluation(tuple(rows))


def _extract_metric(row: Mapping[str, Any], metric_name: str) -> float | None:
    aliases = {
        "response_relevancy": ("response_relevancy", "answer_relevancy"),
        "context_precision": ("context_precision", "llm_context_precision_with_reference"),
        "context_recall": ("context_recall", "llm_context_recall"),
    }
    for key in aliases.get(metric_name, (metric_name,)):
        if key in row:
            return _json_metric(row[key])
    return None


def _to_ragas_record(sample: Mapping[str, Any]) -> dict[str, Any]:
    record: dict[str, Any] = {
        "sample_id": sample["sampleId"],
        "user_input": sample["userInput"],
        "retrieved_contexts": [context.get("text", "") for context in sample.get("retrievedContexts", [])],
        "retrieved_context_ids": [context.get("id", "") for context in sample.get("retrievedContexts", [])],
        "reference_context_ids": list(sample.get("referenceContextIds", [])),
    }
    if sample.get("response") is not None:
        record["response"] = sample["response"]
    if sample.get("reference") is not None:
        record["reference"] = sample["reference"]
    metadata = sample.get("metadata") or {}
    if isinstance(metadata, Mapping) and metadata.get("referenceContexts"):
        record["reference_contexts"] = list(metadata["referenceContexts"])
    return record


def _has_ragas_field(record: Mapping[str, Any], field_name: str) -> bool:
    value = record.get(field_name)
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, Sequence):
        return bool(value)
    return True


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path, max_items: int | None = None) -> Iterable[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        for index, line in enumerate(handle):
            if max_items is not None and index >= max_items:
                break
            if line.strip():
                yield json.loads(line)


def _is_metric_value(value: Any) -> bool:
    return value is None or isinstance(value, (int, float)) and not isinstance(value, bool)


def _json_metric(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if hasattr(value, "item") and not isinstance(value, (bytes, bytearray, str)):
        try:
            value = value.item()
        except (AttributeError, TypeError, ValueError):
            pass
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return None if _is_nan(value) else float(value)
    return None


def _is_nan(value: Any) -> bool:
    return isinstance(value, float) and math.isnan(value)


def _provider_api_key(provider: str) -> str | None:
    if provider == "deepseek":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY") or os.getenv("CHATAGENT_DEEPSEEK_API_KEY")
    if provider == "openai":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY") or os.getenv("OPENAI_API_KEY")
    return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY")


def _provider_base_url(provider: str) -> str | None:
    if provider == "deepseek":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or os.getenv("CHATAGENT_DEEPSEEK_BASE_URL") or "https://api.deepseek.com"
    return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL")
