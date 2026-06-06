from __future__ import annotations

import contextlib
import io
import json
import math
import tempfile
import unittest
from pathlib import Path
from typing import Any

import run_eval
from chatagent_eval.ragas_runner import (
    RagasEvaluation,
    RagasRunnerConfig,
    RagasScoreRow,
    RagasUnavailable,
    run_ragas,
    to_ragas_records,
)
from chatagent_eval.schemas import load_json, validate

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class RagasRunnerTest(unittest.TestCase):
    def test_converts_v2_samples_to_ragas_records(self) -> None:
        sample = _sample("sample-1")
        records, converted, failures = to_ragas_records([sample], ("faithfulness", "context_precision"))

        self.assertFalse(failures)
        self.assertEqual("Does the context support the answer?", records[0]["user_input"])
        self.assertEqual(["The filing reports revenue of 42."], records[0]["retrieved_contexts"])
        self.assertEqual(["doc-1"], records[0]["retrieved_context_ids"])
        self.assertEqual(["doc-1"], records[0]["reference_context_ids"])
        self.assertIn("ragasRequiredFields", converted[0]["metadata"])

    def test_runs_ragas_smoke_and_distinguishes_metric_families(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_run = _write_source_run(Path(temp_dir) / "source", [_sample("sample-1")])
            output_root = Path(temp_dir) / "out"

            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=output_root,
                config=RagasRunnerConfig(run_id="ragas-1", metric_names=("faithfulness", "response_relevancy")),
                evaluator=FakeEvaluator({"sample-1": {"faithfulness": 0.9, "response_relevancy": 0.8}}),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            validate(report, load_json(RESOURCE_ROOT / "schemas" / "eval-report.schema.json"))
            self.assertEqual("pass", report["status"])
            self.assertEqual(1.0, report["metrics"]["deterministic.hitAtK"])
            self.assertEqual(0.9, report["metrics"]["ragas.faithfulness"])
            metrics = json.loads((run_dir / "metrics.json").read_text(encoding="utf-8"))
            self.assertIn("deterministic", metrics)
            self.assertIn("ragas", metrics)

    def test_missing_ragas_required_fields_are_structured_failures(self) -> None:
        sample = _sample("sample-1")
        sample.pop("response")
        with tempfile.TemporaryDirectory() as temp_dir:
            source_run = _write_source_run(Path(temp_dir) / "source", [sample])
            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=Path(temp_dir) / "out",
                config=RagasRunnerConfig(run_id="ragas-missing", metric_names=("faithfulness",)),
                evaluator=FailIfCalledEvaluator(),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertIsNone(report["metrics"]["ragas.faithfulness"])
            self.assertEqual("missing_required_ragas_fields", failures[0]["errorCategory"])
            self.assertEqual(["response"], failures[0]["missingFields"])

    def test_cli_ragas_smoke_writes_warn_report_for_missing_required_fields(self) -> None:
        sample = _sample("sample-1")
        sample.pop("response")
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            source_run = _write_source_run(temp_path / "source", [sample])

            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "ragas-smoke",
                        "--input-run-dir",
                        str(source_run),
                        "--output-root",
                        str(temp_path / "out"),
                        "--run-id",
                        "cli-ragas-missing",
                        "--metrics",
                        "faithfulness",
                    ]
                )

            report = json.loads((temp_path / "out" / "cli-ragas-missing" / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("warn", report["status"])
            self.assertIsNone(report["metrics"]["ragas.faithfulness"])

    def test_nan_ragas_scores_are_serialized_as_null_with_failures(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_run = _write_source_run(Path(temp_dir) / "source", [_sample("sample-1")])
            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=Path(temp_dir) / "out",
                config=RagasRunnerConfig(run_id="ragas-nan", metric_names=("faithfulness",)),
                evaluator=FakeEvaluator({"sample-1": {"faithfulness": math.nan}}),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            samples = _read_jsonl(run_dir / "samples.jsonl")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertIsNone(report["metrics"]["ragas.faithfulness"])
            self.assertIsNone(samples[0]["metadata"]["ragasScores"]["faithfulness"])
            self.assertEqual("ragas_nan", failures[0]["errorCategory"])

    def test_nan_failure_mode_keeps_row_level_null_without_structured_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_run = _write_source_run(Path(temp_dir) / "source", [_sample("sample-1")])
            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=Path(temp_dir) / "out",
                config=RagasRunnerConfig(run_id="ragas-nan-mode", metric_names=("faithfulness",), failure_mode="nan"),
                evaluator=FakeEvaluator({"sample-1": {"faithfulness": math.nan}}),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            samples = _read_jsonl(run_dir / "samples.jsonl")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertIsNone(report["metrics"]["ragas.faithfulness"])
            self.assertIsNone(samples[0]["metadata"]["ragasScores"]["faithfulness"])
            self.assertEqual([], failures)

    def test_unavailable_ragas_runtime_is_structured_warn_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_run = _write_source_run(Path(temp_dir) / "source", [_sample("sample-1")])
            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=Path(temp_dir) / "out",
                config=RagasRunnerConfig(run_id="ragas-unavailable", metric_names=("faithfulness",)),
                evaluator=UnavailableEvaluator(),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertIsNone(report["metrics"]["ragas.faithfulness"])
            self.assertEqual("ragas_unavailable", failures[0]["errorCategory"])


class FakeEvaluator:
    def __init__(self, scores_by_sample: dict[str, dict[str, float]]) -> None:
        self.scores_by_sample = scores_by_sample

    def evaluate(self, records: list[dict[str, Any]], config: RagasRunnerConfig) -> RagasEvaluation:
        return RagasEvaluation(
            tuple(
                RagasScoreRow(
                    sample_id=record["sample_id"],
                    metrics=self.scores_by_sample[record["sample_id"]],
                )
                for record in records
            )
        )


class FailIfCalledEvaluator:
    def evaluate(self, records: list[dict[str, Any]], config: RagasRunnerConfig) -> RagasEvaluation:
        raise AssertionError("evaluator should not be called")


class UnavailableEvaluator:
    def evaluate(self, records: list[dict[str, Any]], config: RagasRunnerConfig) -> RagasEvaluation:
        raise RagasUnavailable("test unavailable")


def _write_source_run(path: Path, samples: list[dict[str, Any]]) -> Path:
    path.mkdir(parents=True)
    (path / "manifest.json").write_text(
        json.dumps(
            {
                "runId": "source-run",
                "suite": "rag-retrieval",
                "mode": "smoke",
                "datasetId": "dataset",
                "datasetHash": "sha256:dataset",
                "configFingerprint": "source-fingerprint",
            }
        ),
        encoding="utf-8",
    )
    (path / "metrics.json").write_text(json.dumps({"hitAtK": 1.0, "mrr": 1.0}), encoding="utf-8")
    with (path / "samples.jsonl").open("w", encoding="utf-8", newline="\n") as handle:
        for sample in samples:
            handle.write(json.dumps(sample) + "\n")
    return path


def _sample(sample_id: str) -> dict[str, Any]:
    return {
        "sampleId": sample_id,
        "datasetId": "dataset",
        "split": "smoke",
        "userInput": "Does the context support the answer?",
        "response": "The filing reports revenue of 42.",
        "reference": "Revenue is 42.",
        "retrievedContexts": [{"id": "doc-1", "text": "The filing reports revenue of 42.", "sourceId": "source", "score": 0.9}],
        "referenceContextIds": ["doc-1"],
        "metadata": {"sourceType": "real-public-corpus"},
    }


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


if __name__ == "__main__":
    unittest.main()
