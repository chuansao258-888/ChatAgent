"""Official Ragas runner for ChatAgent v2 evaluation samples."""

from __future__ import annotations

import json
import math
import os
import asyncio
import time
import urllib.request
from collections.abc import Iterable, Mapping, Sequence
from contextlib import suppress
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol

from chatagent_eval.parameters import config_fingerprint
from chatagent_eval.reports import build_manifest, build_report, write_json_artifact, write_run_artifacts

DEFAULT_RAGAS_METRICS = ("faithfulness", "factual_correctness")
NO_CONTEXT_INAPPLICABLE_METRICS = {"faithfulness", "context_precision", "context_recall"}
REQUIRED_FIELDS = {
    "context_precision": ("user_input", "retrieved_contexts", "reference"),
    "context_recall": ("user_input", "retrieved_contexts", "reference"),
    "faithfulness": ("user_input", "retrieved_contexts", "response"),
    "response_relevancy": ("user_input", "response"),
    "factual_correctness": ("user_input", "response", "reference"),
    "semantic_similarity": ("response", "reference"),
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
    judge_temperature: float = 0.0
    judge_max_tokens: int = 1024
    judge_context_tokens: int | None = None
    judge_timeout_seconds: int | None = None
    judge_max_workers: int | None = None
    judge_think: bool | None = None
    response_relevancy_strictness: int | None = None
    embedding_model: str | None = None
    embedding_num_gpu: int | None = None
    experiment_name: str = "chatagent-ragas"
    batch_size: int = 25
    batch_delay_seconds: float = 0.0
    git_branch: str = "unknown"
    git_sha: str = "unknown"

    def __post_init__(self) -> None:
        if self.failure_mode not in {"nan", "structured"}:
            raise ValueError("failure_mode must be 'nan' or 'structured'")
        if not self.metric_names:
            raise ValueError("at least one Ragas metric is required")
        if self.batch_size < 1:
            raise ValueError("batch_size must be positive")
        if self.batch_delay_seconds < 0:
            raise ValueError("batch_delay_seconds must be non-negative")
        if self.judge_max_tokens < 1:
            raise ValueError("judge_max_tokens must be positive")
        if self.judge_context_tokens is not None and self.judge_context_tokens < 1:
            raise ValueError("judge_context_tokens must be positive")
        if self.judge_timeout_seconds is not None and self.judge_timeout_seconds < 1:
            raise ValueError("judge_timeout_seconds must be positive")
        if self.judge_max_workers is not None and self.judge_max_workers < 1:
            raise ValueError("judge_max_workers must be positive")
        if self.response_relevancy_strictness is not None and self.response_relevancy_strictness < 1:
            raise ValueError("response_relevancy_strictness must be positive")
        if self.embedding_num_gpu is not None and self.embedding_num_gpu < 0:
            raise ValueError("embedding_num_gpu must be non-negative")

    def as_dict(self) -> dict[str, Any]:
        return {
            "metricNames": list(self.metric_names),
            "failureMode": self.failure_mode,
            "maxSamples": self.max_samples,
            "warnOnUnavailable": self.warn_on_unavailable,
            "judgeProvider": self.judge_provider,
            "judgeModel": self.judge_model,
            "judgeBaseUrl": self.judge_base_url,
            "judgeTemperature": self.judge_temperature,
            "judgeMaxTokens": self.judge_max_tokens,
            "judgeContextTokens": self.judge_context_tokens,
            "judgeTimeoutSeconds": self.judge_timeout_seconds,
            "judgeMaxWorkers": self.judge_max_workers,
            "judgeThink": _effective_judge_think(self),
            "judgeStructuredOutputMode": _judge_structured_output_mode(self),
            "responseRelevancyStrictness": _effective_response_relevancy_strictness(self),
            "embeddingModel": self.embedding_model,
            "embeddingNumGpu": self.embedding_num_gpu,
            "experimentName": self.experiment_name,
            "batchSize": self.batch_size,
            "batchDelaySeconds": self.batch_delay_seconds,
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
        "inputSplitHashes": dict((source_manifest.get("config") or {}).get("splitHashes") or {}),
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
            ragas_evaluation = _evaluate_with_checkpoint(
                records=ragas_records,
                output_root=output_root,
                config=config,
                config_fingerprint_value=fingerprint,
                evaluator=evaluator or OfficialRagasEvaluator(),
            )
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
    _remove_ragas_checkpoint(output_root, config.run_id)
    return run_dir


def _evaluate_with_checkpoint(
    *,
    records: Sequence[Mapping[str, Any]],
    output_root: Path,
    config: RagasRunnerConfig,
    config_fingerprint_value: str,
    evaluator: RagasEvaluator,
) -> RagasEvaluation:
    checkpoint_dir = output_root.resolve() / f".{config.run_id}.checkpoint"
    checkpoint_rows = checkpoint_dir / "ragas-scores.jsonl"
    completed = _load_or_create_ragas_checkpoint(
        checkpoint_dir=checkpoint_dir,
        checkpoint_rows=checkpoint_rows,
        run_id=config.run_id,
        config_fingerprint_value=config_fingerprint_value,
    )
    completed_keys = {
        (row.sample_id, metric_name)
        for row in completed
        for metric_name in row.metrics
    }
    all_rows = list(completed)
    for metric_name in config.metric_names:
        metric_config = replace(config, metric_names=(metric_name,))
        eligible = [
            record
            for record in records
            if all(_has_ragas_field(record, field) for field in REQUIRED_FIELDS.get(metric_name, ()))
        ]
        pending = [
            record
            for record in eligible
            if (str(record["sample_id"]), metric_name) not in completed_keys
        ]
        for start in range(0, len(pending), config.batch_size):
            batch = pending[start : start + config.batch_size]
            evaluation = evaluator.evaluate(batch, metric_config)
            expected_ids = {str(record["sample_id"]) for record in batch}
            actual_ids = {row.sample_id for row in evaluation.rows}
            if len(evaluation.rows) != len(batch) or actual_ids != expected_ids:
                raise ValueError(
                    f"Ragas batch result mismatch: expected {len(batch)} rows, received {len(evaluation.rows)}"
                )
            normalized = [
                RagasScoreRow(
                    sample_id=row.sample_id,
                    metrics={metric_name: row.metrics.get(metric_name)},
                    failures=row.failures,
                )
                for row in evaluation.rows
            ]
            all_rows.extend(normalized)
            _append_ragas_checkpoint_rows(checkpoint_rows, normalized)
            if config.batch_delay_seconds and start + config.batch_size < len(pending):
                time.sleep(config.batch_delay_seconds)
    return RagasEvaluation(tuple(_merge_ragas_score_rows(all_rows)))


def _merge_ragas_score_rows(rows: Sequence[RagasScoreRow]) -> list[RagasScoreRow]:
    metrics_by_sample: dict[str, dict[str, float | None]] = {}
    failures_by_sample: dict[str, list[Mapping[str, Any]]] = {}
    order: list[str] = []
    for row in rows:
        if row.sample_id not in metrics_by_sample:
            order.append(row.sample_id)
            metrics_by_sample[row.sample_id] = {}
            failures_by_sample[row.sample_id] = []
        metrics_by_sample[row.sample_id].update(row.metrics)
        failures_by_sample[row.sample_id].extend(row.failures)
    return [
        RagasScoreRow(
            sample_id=sample_id,
            metrics=metrics_by_sample[sample_id],
            failures=tuple(failures_by_sample[sample_id]),
        )
        for sample_id in order
    ]


def _load_or_create_ragas_checkpoint(
    *,
    checkpoint_dir: Path,
    checkpoint_rows: Path,
    run_id: str,
    config_fingerprint_value: str,
) -> list[RagasScoreRow]:
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_config = checkpoint_dir / "config.json"
    expected = {"runId": run_id, "configFingerprint": config_fingerprint_value}
    if checkpoint_config.exists():
        actual = json.loads(checkpoint_config.read_text(encoding="utf-8"))
        if actual != expected:
            raise ValueError(f"Ragas checkpoint config mismatch for run id: {run_id}")
    else:
        checkpoint_config.write_text(
            json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    if not checkpoint_rows.exists():
        return []
    return [_ragas_score_row_from_json(json.loads(line)) for line in checkpoint_rows.read_text(encoding="utf-8").splitlines() if line]


def _append_ragas_checkpoint_rows(path: Path, rows: Sequence[RagasScoreRow]) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(
                json.dumps(
                    {"sampleId": row.sample_id, "metrics": dict(row.metrics), "failures": list(row.failures)},
                    ensure_ascii=False,
                    sort_keys=True,
                )
                + "\n"
            )


def _ragas_score_row_from_json(value: Mapping[str, Any]) -> RagasScoreRow:
    return RagasScoreRow(
        sample_id=str(value["sampleId"]),
        metrics=dict(value.get("metrics") or {}),
        failures=tuple(value.get("failures") or ()),
    )


def _remove_ragas_checkpoint(output_root: Path, run_id: str) -> None:
    checkpoint_dir = output_root.resolve() / f".{run_id}.checkpoint"
    with suppress(FileNotFoundError):
        (checkpoint_dir / "ragas-scores.jsonl").unlink()
    with suppress(FileNotFoundError):
        (checkpoint_dir / "config.json").unlink()
    with suppress(FileNotFoundError, OSError):
        checkpoint_dir.rmdir()


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
    for sample in samples:
        sample_id = str(sample["sampleId"])
        record = _to_ragas_record(sample)
        missing_by_metric = {
            metric: [field for field in REQUIRED_FIELDS.get(metric, ()) if not _has_ragas_field(record, field)]
            for metric in metric_names
        }
        inapplicable_metrics = _inapplicable_metrics(sample, missing_by_metric)
        eligible_metrics = [metric for metric, missing in missing_by_metric.items() if not missing]
        actionable_missing_by_metric = {
            metric: fields for metric, fields in missing_by_metric.items() if metric not in inapplicable_metrics
        }
        missing = sorted({field for fields in actionable_missing_by_metric.values() for field in fields})
        converted = dict(sample)
        metadata = dict(converted.get("metadata") or {})
        metadata["ragasRequiredFields"] = sorted(
            {field for metric in metric_names for field in REQUIRED_FIELDS.get(metric, ())}
        )
        if missing:
            metadata["ragasMissingFields"] = missing
            metadata["ragasMissingFieldsByMetric"] = {
                metric: fields for metric, fields in actionable_missing_by_metric.items() if fields
            }
            if failure_mode == "nan":
                metadata["ragasScores"] = {
                    metric_name: None for metric_name, fields in actionable_missing_by_metric.items() if fields
                }
        if inapplicable_metrics:
            metadata["ragasInapplicableMetrics"] = sorted(inapplicable_metrics)
        converted["metadata"] = metadata
        converted_samples.append(converted)
        if eligible_metrics:
            records.append(record)
        if failure_mode == "structured":
            for metric_name, metric_missing in actionable_missing_by_metric.items():
                if not metric_missing:
                    continue
                failures.append(
                    {
                        "sampleId": sample_id,
                        "metric": metric_name,
                        "errorCategory": "missing_required_ragas_fields",
                        "missingFields": metric_missing,
                    }
                )
    return records, converted_samples, failures


def _inapplicable_metrics(
    sample: Mapping[str, Any],
    missing_by_metric: Mapping[str, Sequence[str]],
) -> set[str]:
    metadata = sample.get("metadata") or {}
    control = metadata.get("controlRun") or {}
    mode = str(control.get("mode") or "") if isinstance(control, Mapping) else ""
    if mode != "no-rag":
        return set()
    return {
        metric
        for metric, missing in missing_by_metric.items()
        if metric in NO_CONTEXT_INAPPLICABLE_METRICS and set(missing) == {"retrieved_contexts"}
    }


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
            inapplicable_metrics = set(metadata.get("ragasInapplicableMetrics") or ())
            for metric_name in metric_names:
                metric_value = row.metrics.get(metric_name)
                if (
                    failure_mode == "structured"
                    and metric_name not in inapplicable_metrics
                    and _json_metric(metric_value) is None
                ):
                    failure = {
                        "sampleId": sample_id,
                        "metric": metric_name,
                        "errorCategory": "ragas_nan" if _is_nan(metric_value) else "ragas_null",
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
            from ragas import EvaluationDataset, evaluate
        except ImportError as exception:
            raise RagasUnavailable("Install the eval extra with `pip install -e .[ragas]` to run official Ragas metrics") from exception

        api_key = config.judge_api_key or _provider_api_key(config.judge_provider)
        if not api_key:
            raise RagasUnavailable(f"Missing API key for Ragas judge provider: {config.judge_provider}")
        base_url = _clean_base_url(config.judge_base_url or _provider_base_url(config.judge_provider))
        client_kwargs = {"api_key": api_key}
        if base_url:
            client_kwargs["base_url"] = base_url
        llm = _build_judge_llm(config, client_kwargs)
        embeddings = _build_embeddings(config.embedding_model, ollama_num_gpu=config.embedding_num_gpu)
        metrics = [
            _build_metric(
                name,
                llm=llm,
                embeddings=embeddings,
                response_relevancy_strictness=_effective_response_relevancy_strictness(config),
            )
            for name in config.metric_names
        ]
        dataset = EvaluationDataset.from_list(list(records))
        result = evaluate(
            dataset=dataset,
            metrics=metrics,
            llm=llm,
            embeddings=embeddings,
            experiment_name=config.experiment_name,
            run_config=_build_run_config(config),
            raise_exceptions=False,
            show_progress=False,
        )
        return _evaluation_from_official_result(result, records, config.metric_names)


def _build_run_config(config: RagasRunnerConfig) -> Any | None:
    if config.judge_timeout_seconds is None and config.judge_max_workers is None:
        return None
    from ragas.run_config import RunConfig

    kwargs: dict[str, Any] = {}
    if config.judge_timeout_seconds is not None:
        kwargs["timeout"] = config.judge_timeout_seconds
    if config.judge_max_workers is not None:
        kwargs["max_workers"] = config.judge_max_workers
    return RunConfig(**kwargs)


def _build_metric(name: str, *, llm: Any, embeddings: Any, response_relevancy_strictness: int = 3) -> Any:
    try:
        from ragas.metrics.collections import (
            ContextPrecision,
            ContextRecall,
            Faithfulness,
            FactualCorrectness,
            ResponseRelevancy,
            SemanticSimilarity,
        )

        factories = {
            "context_precision": lambda: ContextPrecision(llm=llm),
            "context_recall": lambda: ContextRecall(llm=llm),
            "faithfulness": lambda: Faithfulness(llm=llm),
            "response_relevancy": lambda: ResponseRelevancy(
                llm=llm,
                embeddings=embeddings,
                strictness=response_relevancy_strictness,
            ),
            "factual_correctness": lambda: FactualCorrectness(llm=llm),
            "semantic_similarity": lambda: SemanticSimilarity(embeddings=embeddings),
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
        return ResponseRelevancy(llm=llm, embeddings=embeddings, strictness=response_relevancy_strictness)
    if name == "factual_correctness":
        from ragas.metrics import FactualCorrectness

        return FactualCorrectness(llm=llm)
    if name == "semantic_similarity":
        from ragas.metrics import SemanticSimilarity

        return SemanticSimilarity(embeddings=embeddings)
    if name not in factories:
        raise ValueError(f"unsupported Ragas metric: {name}")
    return factories[name]()


def _build_embeddings(embedding_model: str | None, *, ollama_num_gpu: int | None = None) -> Any:
    if not embedding_model:
        return None
    if embedding_model.startswith("ollama/") or embedding_model.startswith("ollama:"):
        model = embedding_model.split("/", 1)[1] if "/" in embedding_model else embedding_model.split(":", 1)[1]
        return _build_ollama_embeddings(model, num_gpu=ollama_num_gpu)
    try:
        from ragas.embeddings.base import embedding_factory
    except ImportError:
        return None
    return embedding_factory(model=embedding_model)


def _build_ollama_embeddings(model: str, *, num_gpu: int | None = None) -> Any:
    try:
        from ragas.embeddings.base import BaseRagasEmbeddings
        from ragas.run_config import RunConfig
    except ImportError as exception:
        raise RagasUnavailable("Install the eval extra with `pip install -e .[ragas]` to use Ollama Ragas embeddings") from exception

    base_url = (
        os.getenv("CHATAGENT_EVAL_RAGAS_EMBEDDING_BASE_URL")
        or os.getenv("CHATAGENT_RAG_EMBEDDING_BASE_URL")
        or "http://127.0.0.1:11434"
    ).rstrip("/")

    class OllamaRagasEmbeddings(BaseRagasEmbeddings):
        def __init__(self, ollama_model: str, ollama_base_url: str, ollama_num_gpu: int | None):
            super().__init__()
            self.model = ollama_model
            self.base_url = ollama_base_url
            self.num_gpu = ollama_num_gpu
            self.run_config = RunConfig()

        def embed_query(self, text: str) -> list[float]:
            return self.embed_documents([text])[0]

        def embed_documents(self, texts: list[str]) -> list[list[float]]:
            return _ollama_embed_batch(self.base_url, self.model, texts, num_gpu=self.num_gpu)

        async def aembed_query(self, text: str) -> list[float]:
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(None, self.embed_query, text)

        async def aembed_documents(self, texts: list[str]) -> list[list[float]]:
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(None, self.embed_documents, texts)

    return OllamaRagasEmbeddings(model, base_url, num_gpu)


def _ollama_embed_batch(
    base_url: str,
    model: str,
    texts: Sequence[str],
    *,
    num_gpu: int | None = None,
) -> list[list[float]]:
    normalized = [str(text or "") for text in texts]
    if not normalized:
        return []
    payload = {"model": model, "input": normalized}
    if num_gpu is not None:
        payload["options"] = {"num_gpu": num_gpu}
    try:
        response = _post_json(f"{base_url}/api/embed", payload)
        embeddings = response.get("embeddings")
        if isinstance(embeddings, list) and len(embeddings) == len(normalized):
            return [_validated_embedding(item) for item in embeddings]
    except Exception:
        # Older Ollama versions may only support /api/embeddings with one prompt.
        pass
    legacy_payloads = [{"model": model, "prompt": text} for text in normalized]
    if num_gpu is not None:
        for legacy_payload in legacy_payloads:
            legacy_payload["options"] = {"num_gpu": num_gpu}
    return [
        _validated_embedding(_post_json(f"{base_url}/api/embeddings", payload).get("embedding"))
        for payload in legacy_payloads
    ]


def _post_json(url: str, payload: Mapping[str, Any]) -> Mapping[str, Any]:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def _validated_embedding(value: Any) -> list[float]:
    if not isinstance(value, list) or not value:
        raise RagasUnavailable("Ollama embedding response did not contain an embedding vector")
    vector = [float(item) for item in value]
    if any(not math.isfinite(item) for item in vector):
        raise RagasUnavailable("Ollama embedding response contained non-finite values")
    return vector


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
        "factual_correctness": ("factual_correctness",),
        "semantic_similarity": ("semantic_similarity",),
    }
    for key in aliases.get(metric_name, (metric_name,)):
        if key in row:
            return _json_metric(row[key])
        parameterized_key = next((candidate for candidate in row if str(candidate).startswith(f"{key}(")), None)
        if parameterized_key is not None:
            return _json_metric(row[parameterized_key])
    return None


def _to_ragas_record(sample: Mapping[str, Any]) -> dict[str, Any]:
    metadata = sample.get("metadata") or {}
    retrieved_contexts = _retrieved_contexts(sample, metadata)
    record: dict[str, Any] = {
        "sample_id": sample["sampleId"],
        "user_input": sample["userInput"],
        "retrieved_contexts": [_context_text(context) for context in retrieved_contexts],
        "retrieved_context_ids": [_context_id(context) for context in retrieved_contexts],
        "reference_context_ids": list(sample.get("referenceContextIds", [])),
    }
    if sample.get("response") is not None:
        record["response"] = sample["response"]
    if sample.get("reference") is not None:
        record["reference"] = sample["reference"]
    if isinstance(metadata, Mapping) and metadata.get("referenceContexts"):
        record["reference_contexts"] = [_reference_context_text(context) for context in metadata["referenceContexts"]]
    return record


def _retrieved_contexts(sample: Mapping[str, Any], metadata: Any) -> Sequence[Any]:
    contexts = sample.get("retrievedContexts")
    if isinstance(contexts, Sequence) and not isinstance(contexts, (str, bytes, bytearray)):
        return contexts
    if isinstance(metadata, Mapping):
        metadata_contexts = metadata.get("retrievedContexts")
        if isinstance(metadata_contexts, Sequence) and not isinstance(metadata_contexts, (str, bytes, bytearray)):
            return metadata_contexts
    return ()


def _context_text(context: Any) -> str:
    if isinstance(context, Mapping):
        return str(context.get("text") or context.get("content") or "")
    return str(context or "")


def _context_id(context: Any) -> str:
    if isinstance(context, Mapping):
        return str(context.get("id") or context.get("chunkId") or context.get("documentId") or "")
    return ""


def _reference_context_text(context: Any) -> str:
    if isinstance(context, Mapping):
        return _context_text(context)
    return str(context or "")


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
    if provider in {"ollama", "llamacpp"}:
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY") or f"{provider}-local"
    if provider == "deepseek":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY") or os.getenv("CHATAGENT_DEEPSEEK_API_KEY")
    if provider in {"zhipu", "zhipuai"}:
        return _first_env(
            "CHATAGENT_ZHIPUAI_API_KEY",
            "CHATAGENT_ZHIPUAI_API_KEY_2",
            "CHATAGENT_ZHIPUAI_SECONDARY_API_KEY",
            "CHATAGENT_EVAL_RAGAS_LLM_API_KEY",
        )
    if provider in {"z.ai", "zai", "z-ai", "zai-coding"}:
        return _first_env("CHATAGENT_ZAI_CODING_API_KEY", "CHATAGENT_ZAI_API_KEY", "CHATAGENT_EVAL_RAGAS_LLM_API_KEY")
    if provider == "openai":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY") or os.getenv("OPENAI_API_KEY")
    return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_API_KEY")


def _provider_base_url(provider: str) -> str | None:
    if provider == "ollama":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or "http://127.0.0.1:11434/v1"
    if provider == "llamacpp":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or "http://127.0.0.1:8080/v1"
    if provider == "deepseek":
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or os.getenv("CHATAGENT_DEEPSEEK_BASE_URL") or "https://api.deepseek.com"
    if provider in {"zhipu", "zhipuai"}:
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or os.getenv("CHATAGENT_ZHIPUAI_BASE_URL")
    if provider in {"z.ai", "zai", "z-ai", "zai-coding"}:
        return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL") or _first_env(
            "CHATAGENT_ZAI_CODING_BASE_URL", "CHATAGENT_ZAI_BASE_URL"
        )
    return os.getenv("CHATAGENT_EVAL_RAGAS_LLM_BASE_URL")


def _first_env(*names: str) -> str | None:
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return None


def _effective_judge_think(config: RagasRunnerConfig) -> bool | None:
    if config.judge_think is not None:
        return config.judge_think
    return False if config.judge_provider in {"ollama", "llamacpp"} else None


def _judge_structured_output_mode(config: RagasRunnerConfig) -> str:
    return "json_schema" if config.judge_provider in {"ollama", "llamacpp"} else "ragas_default"


def _effective_response_relevancy_strictness(config: RagasRunnerConfig) -> int:
    if config.response_relevancy_strictness is not None:
        return config.response_relevancy_strictness
    # Local JSON Schema clients return one structured generation per request.
    return 1 if config.judge_provider in {"ollama", "llamacpp"} else 3


def _judge_llm_kwargs(config: RagasRunnerConfig) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "temperature": config.judge_temperature,
        "max_tokens": config.judge_max_tokens,
    }
    think = _effective_judge_think(config)
    if think is not None:
        if config.judge_provider in {"zhipu", "zhipuai", "z.ai", "zai", "z-ai", "zai-coding"}:
            kwargs["extra_body"] = {"thinking": {"type": "enabled" if think else "disabled"}}
        else:
            kwargs["extra_body"] = {"reasoning_effort": "medium" if think else "none"}
    return kwargs


def _build_judge_llm(config: RagasRunnerConfig, client_kwargs: Mapping[str, Any]) -> Any:
    try:
        from openai import AsyncOpenAI
        from ragas.llms import llm_factory
    except ImportError as exception:
        raise RagasUnavailable("Install the eval extra with `pip install -e .[ragas]` to run official Ragas metrics") from exception

    client = AsyncOpenAI(**client_kwargs)
    if config.judge_provider not in {"ollama", "llamacpp"}:
        return llm_factory(
            config.judge_model,
            client=client,
            **_judge_llm_kwargs(config),
        )

    try:
        import instructor
        from ragas.llms.base import InstructorLLM
    except ImportError as exception:
        raise RagasUnavailable("Install the eval extra with `pip install -e .[ragas]` to use an Ollama Ragas judge") from exception

    # Ragas defaults OpenAI-compatible clients to prompt-only JSON mode; local servers need native schema constraints.
    patched_client = instructor.from_openai(client, mode=instructor.Mode.JSON_SCHEMA)
    return InstructorLLM(
        client=patched_client,
        model=config.judge_model,
        provider=config.judge_provider,
        **_judge_llm_kwargs(config),
    )


def _clean_base_url(value: str | None) -> str | None:
    if not value:
        return None
    cleaned = value.strip().strip(" \t\r\n\"';")
    return cleaned or None
