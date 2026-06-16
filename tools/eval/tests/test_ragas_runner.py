from __future__ import annotations

import contextlib
import io
import json
import math
import os
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

import run_eval
from chatagent_eval.ragas_runner import (
    RagasEvaluation,
    RagasRunnerConfig,
    RagasScoreRow,
    RagasUnavailable,
    _build_embeddings,
    _build_judge_llm,
    _build_run_config,
    _clean_base_url,
    _judge_llm_kwargs,
    _provider_api_key,
    _provider_base_url,
    _extract_metric,
    run_ragas,
    to_ragas_records,
)
from chatagent_eval.schemas import load_json, validate

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class RagasRunnerTest(unittest.TestCase):
    def test_default_ragas_metrics_match_one_run_b3_4_scope(self) -> None:
        self.assertEqual(("faithfulness", "factual_correctness"), RagasRunnerConfig(run_id="default-scope").metric_names)

    def test_converts_v2_samples_to_ragas_records(self) -> None:
        sample = _sample("sample-1")
        records, converted, failures = to_ragas_records([sample], ("faithfulness", "context_precision"))

        self.assertFalse(failures)
        self.assertEqual("Does the context support the answer?", records[0]["user_input"])
        self.assertEqual(["The filing reports revenue of 42."], records[0]["retrieved_contexts"])
        self.assertEqual(["doc-1"], records[0]["retrieved_context_ids"])
        self.assertEqual(["doc-1"], records[0]["reference_context_ids"])
        self.assertIn("ragasRequiredFields", converted[0]["metadata"])

    def test_zhipu_provider_uses_shared_eval_environment(self) -> None:
        with mock.patch.dict(
            os.environ,
            {
                "CHATAGENT_ZHIPUAI_API_KEY": "fake-zhipu-primary",
                "CHATAGENT_ZHIPUAI_API_KEY_2": "fake-zhipu-secondary",
                "CHATAGENT_ZAI_CODING_API_KEY": "fake-zai-key",
                "CHATAGENT_DEEPSEEK_API_KEY": "fake-deepseek-key",
                "CHATAGENT_ZHIPUAI_BASE_URL": "https://example.test/api/paas/v4",
            },
            clear=True,
        ):
            self.assertEqual("fake-zhipu-primary", _provider_api_key("zhipu"))
            self.assertEqual("https://example.test/api/paas/v4", _provider_base_url("zhipu"))

    def test_provider_priority_uses_second_zhipu_then_zai_then_deepseek(self) -> None:
        with mock.patch.dict(
            os.environ,
            {
                "CHATAGENT_ZHIPUAI_API_KEY_2": "fake-zhipu-secondary",
                "CHATAGENT_ZAI_CODING_API_KEY": "fake-zai-key",
                "CHATAGENT_DEEPSEEK_API_KEY": "fake-deepseek-key",
                "CHATAGENT_ZAI_CODING_BASE_URL": "https://zai.example.test/api/paas/v4",
            },
            clear=True,
        ):
            self.assertEqual("fake-zhipu-secondary", _provider_api_key("zhipu"))
            self.assertEqual("fake-zai-key", _provider_api_key("zai"))
            self.assertEqual("https://zai.example.test/api/paas/v4", _provider_base_url("zai"))
            self.assertEqual("fake-deepseek-key", _provider_api_key("deepseek"))

    def test_converts_doc_ingestion_answer_rows_to_ragas_records(self) -> None:
        sample = _sample("doc-ingestion-1")
        sample.pop("retrievedContexts")
        sample["metadata"] = {
            "retrievedContexts": [
                {
                    "chunkId": "chunk-1",
                    "documentId": "doc-1",
                    "content": "The filing reports revenue of 42.",
                }
            ],
            "referenceContexts": [
                {
                    "chunkId": "chunk-1",
                    "content": "The filing reports revenue of 42.",
                }
            ],
            "controlRun": {"mode": "full-rag"},
        }

        records, converted, failures = to_ragas_records([sample], ("context_recall", "semantic_similarity"))

        self.assertFalse(failures)
        self.assertEqual(["The filing reports revenue of 42."], records[0]["retrieved_contexts"])
        self.assertEqual(["chunk-1"], records[0]["retrieved_context_ids"])
        self.assertEqual(["The filing reports revenue of 42."], records[0]["reference_contexts"])
        self.assertIn("response", converted[0]["metadata"]["ragasRequiredFields"])
        self.assertIn("reference", converted[0]["metadata"]["ragasRequiredFields"])

    def test_semantic_similarity_requires_response_and_reference(self) -> None:
        sample = _sample("semantic-missing")
        sample.pop("reference")

        records, converted, failures = to_ragas_records([sample], ("semantic_similarity",))

        self.assertEqual([], records)
        self.assertEqual(["reference"], converted[0]["metadata"]["ragasMissingFields"])
        self.assertEqual("missing_required_ragas_fields", failures[0]["errorCategory"])

    def test_no_rag_row_still_scores_answer_metrics_without_contexts(self) -> None:
        sample = _sample("no-rag")
        sample["retrievedContexts"] = []
        sample["metadata"] = {"controlRun": {"mode": "no-rag"}}
        records, converted, failures = to_ragas_records(
            [sample],
            ("context_recall", "factual_correctness", "semantic_similarity"),
        )

        self.assertEqual(1, len(records))
        self.assertEqual([], failures)
        self.assertEqual(["context_recall"], converted[0]["metadata"]["ragasInapplicableMetrics"])
        self.assertNotIn("ragasMissingFieldsByMetric", converted[0]["metadata"])

    def test_no_rag_run_keeps_answer_scores_and_null_context_score(self) -> None:
        sample = _sample("no-rag")
        sample["retrievedContexts"] = []
        sample["metadata"] = {"controlRun": {"mode": "no-rag"}}
        scores = {"no-rag": {"context_recall": 0.0, "factual_correctness": 0.7, "semantic_similarity": 0.8}}
        with tempfile.TemporaryDirectory() as temp_dir:
            source_run = _write_source_run(Path(temp_dir) / "source", [sample])
            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=Path(temp_dir) / "out",
                config=RagasRunnerConfig(
                    run_id="ragas-no-context",
                    metric_names=("context_recall", "factual_correctness", "semantic_similarity"),
                ),
                evaluator=FakeEvaluator(scores),
            )

            result = _read_jsonl(run_dir / "samples.jsonl")[0]["metadata"]["ragasScores"]
            self.assertIsNone(result["context_recall"])
            self.assertEqual(0.7, result["factual_correctness"])
            self.assertEqual(0.8, result["semantic_similarity"])

    def test_six_control_style_run_passes_with_no_rag_context_metrics_inapplicable(self) -> None:
        no_rag = _sample("no-rag")
        no_rag["retrievedContexts"] = []
        no_rag["metadata"] = {"controlRun": {"mode": "no-rag"}}
        full_rag = _sample("full-rag")
        full_rag["metadata"] = {"controlRun": {"mode": "full-rag"}}
        metrics = (
            "faithfulness",
            "response_relevancy",
            "context_precision",
            "context_recall",
            "factual_correctness",
            "semantic_similarity",
        )
        scores = {
            "no-rag": {
                "response_relevancy": 0.7,
                "factual_correctness": 0.6,
                "semantic_similarity": 0.65,
            },
            "full-rag": {metric: 0.85 for metric in metrics},
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_run = _write_source_run(root / "source", [no_rag, full_rag])
            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=root / "out",
                config=RagasRunnerConfig(run_id="ragas-controls", metric_names=metrics),
                evaluator=FakeEvaluator(scores),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            scored = {row["sampleId"]: row for row in _read_jsonl(run_dir / "samples.jsonl")}
            self.assertEqual("pass", report["status"])
            self.assertEqual([], failures)
            self.assertIsNone(scored["no-rag"]["metadata"]["ragasScores"]["faithfulness"])

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

    def test_preserves_source_dataset_and_split_hash_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_run = _write_source_run(root / "source", [_sample("sample-1")])
            source_manifest = json.loads((source_run / "manifest.json").read_text(encoding="utf-8"))
            source_manifest["config"] = {"splitHashes": {"calibration": "sha256:cal"}}
            (source_run / "manifest.json").write_text(json.dumps(source_manifest), encoding="utf-8")

            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=root / "out",
                config=RagasRunnerConfig(run_id="ragas-provenance", metric_names=("faithfulness",)),
                evaluator=FakeEvaluator({"sample-1": {"faithfulness": 1.0}}),
            )

            manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("sha256:dataset", manifest["datasetHash"])
            self.assertEqual({"calibration": "sha256:cal"}, manifest["config"]["inputSplitHashes"])

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
                            "--judge-timeout-seconds",
                            "600",
                            "--judge-max-workers",
                            "1",
                            "--batch-delay-seconds",
                            "2.5",
                        ]
                    )

            report = json.loads((temp_path / "out" / "cli-ragas-missing" / "report.json").read_text(encoding="utf-8"))
            manifest = json.loads((temp_path / "out" / "cli-ragas-missing" / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("warn", report["status"])
            self.assertIsNone(report["metrics"]["ragas.faithfulness"])
            self.assertEqual(600, manifest["config"]["judgeTimeoutSeconds"])
            self.assertEqual(1, manifest["config"]["judgeMaxWorkers"])
            self.assertEqual(2.5, manifest["config"]["batchDelaySeconds"])

    def test_cli_ragas_smoke_defaults_to_shared_zhipu_config(self) -> None:
        sample = _sample("sample-1")
        sample.pop("response")
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            source_run = _write_source_run(temp_path / "source", [sample])

            with mock.patch.dict(
                os.environ,
                {
                    "CHATAGENT_ZHIPUAI_API_KEY": "fake-zhipu-key",
                    "CHATAGENT_ZHIPUAI_BASE_URL": "https://example.test/api/paas/v4",
                    "CHATAGENT_ZHIPUAI_MODEL": "glm-4.5-air",
                },
                clear=True,
            ):
                with contextlib.redirect_stdout(io.StringIO()):
                    exit_code = run_eval.main(
                        [
                            "ragas-smoke",
                            "--input-run-dir",
                            str(source_run),
                            "--output-root",
                            str(temp_path / "out"),
                            "--run-id",
                            "cli-ragas-zhipu-defaults",
                            "--metrics",
                            "faithfulness",
                        ]
                    )

            manifest = json.loads((temp_path / "out" / "cli-ragas-zhipu-defaults" / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("zhipu", manifest["config"]["judgeProvider"])
            self.assertEqual("glm-4.5-air", manifest["config"]["judgeModel"])
            self.assertEqual("https://example.test/api/paas/v4", manifest["config"]["judgeBaseUrl"])

    def test_cli_ragas_smoke_defaults_to_zai_when_zhipu_keys_absent(self) -> None:
        sample = _sample("sample-1")
        sample.pop("response")
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            source_run = _write_source_run(temp_path / "source", [sample])

            with mock.patch.dict(
                os.environ,
                {
                    "CHATAGENT_ZAI_CODING_API_KEY": "fake-zai-key",
                    "CHATAGENT_ZAI_CODING_BASE_URL": "https://zai.example.test/api/paas/v4",
                    "CHATAGENT_ZAI_CODING_MODEL": "glm-4.5-air",
                },
                clear=True,
            ):
                with contextlib.redirect_stdout(io.StringIO()):
                    exit_code = run_eval.main(
                        [
                            "ragas-smoke",
                            "--input-run-dir",
                            str(source_run),
                            "--output-root",
                            str(temp_path / "out"),
                            "--run-id",
                            "cli-ragas-zai-defaults",
                            "--metrics",
                            "faithfulness",
                        ]
                    )

            manifest = json.loads((temp_path / "out" / "cli-ragas-zai-defaults" / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("zai", manifest["config"]["judgeProvider"])
            self.assertEqual("glm-4.5-air", manifest["config"]["judgeModel"])
            self.assertEqual("https://zai.example.test/api/paas/v4", manifest["config"]["judgeBaseUrl"])

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

    def test_applicable_null_score_warns_even_when_aggregate_is_numeric(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_run = _write_source_run(
                Path(temp_dir) / "source",
                [_sample("sample-1"), _sample("sample-2")],
            )
            run_dir = run_ragas(
                input_run_dir=source_run,
                output_root=Path(temp_dir) / "out",
                config=RagasRunnerConfig(run_id="ragas-partial-null", metric_names=("semantic_similarity",)),
                evaluator=FakeEvaluator(
                    {
                        "sample-1": {"semantic_similarity": 0.8},
                        "sample-2": {"semantic_similarity": None},
                    }
                ),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertEqual(0.8, report["metrics"]["ragas.semantic_similarity"])
            self.assertEqual("ragas_null", failures[0]["errorCategory"])
            self.assertEqual("sample-2", failures[0]["sampleId"])
            self.assertEqual("semantic_similarity", failures[0]["metric"])

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

    def test_batches_and_resumes_ragas_evaluation_from_checkpoint(self) -> None:
        samples = [_sample(f"sample-{index}") for index in range(5)]
        scores = {sample["sampleId"]: {"faithfulness": 1.0} for sample in samples}

        class InterruptingEvaluator(FakeEvaluator):
            def __init__(self) -> None:
                super().__init__(scores)
                self.calls = 0

            def evaluate(self, records, config):
                self.calls += 1
                if self.calls == 2:
                    raise KeyboardInterrupt()
                return super().evaluate(records, config)

        class CountingEvaluator(FakeEvaluator):
            def __init__(self) -> None:
                super().__init__(scores)
                self.sample_ids: list[str] = []

            def evaluate(self, records, config):
                self.sample_ids.extend(record["sample_id"] for record in records)
                return super().evaluate(records, config)

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            source_run = _write_source_run(temp_path / "source", samples)
            output_root = temp_path / "out"
            config = RagasRunnerConfig(run_id="ragas-resume", metric_names=("faithfulness",), batch_size=2)
            with self.assertRaises(KeyboardInterrupt):
                run_ragas(input_run_dir=source_run, output_root=output_root, config=config, evaluator=InterruptingEvaluator())

            checkpoint_rows = output_root / ".ragas-resume.checkpoint" / "ragas-scores.jsonl"
            self.assertEqual(2, len(_read_jsonl(checkpoint_rows)))

            resumed = CountingEvaluator()
            run_dir = run_ragas(input_run_dir=source_run, output_root=output_root, config=config, evaluator=resumed)
            self.assertEqual(["sample-2", "sample-3", "sample-4"], resumed.sample_ids)
            self.assertEqual(5, len(_read_jsonl(run_dir / "samples.jsonl")))
            self.assertFalse((output_root / ".ragas-resume.checkpoint").exists())

    def test_batch_delay_waits_between_pending_batches(self) -> None:
        samples = [_sample(f"sample-{index}") for index in range(5)]
        scores = {sample["sampleId"]: {"faithfulness": 1.0} for sample in samples}

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            source_run = _write_source_run(temp_path / "source", samples)
            output_root = temp_path / "out"
            config = RagasRunnerConfig(
                run_id="ragas-delay",
                metric_names=("faithfulness",),
                batch_size=2,
                batch_delay_seconds=1.5,
            )
            with mock.patch("chatagent_eval.ragas_runner.time.sleep") as sleep:
                run_ragas(input_run_dir=source_run, output_root=output_root, config=config, evaluator=FakeEvaluator(scores))

        self.assertEqual([mock.call(1.5), mock.call(1.5)], sleep.call_args_list)

    def test_batch_delay_must_be_non_negative(self) -> None:
        with self.assertRaisesRegex(ValueError, "batch_delay_seconds"):
            RagasRunnerConfig(run_id="ragas-negative-delay", batch_delay_seconds=-0.1)

    def test_rejects_incomplete_ragas_batch_result(self) -> None:
        class IncompleteEvaluator:
            def evaluate(self, records, config):
                return RagasEvaluation(())

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            source_run = _write_source_run(temp_path / "source", [_sample("sample-1")])
            with self.assertRaisesRegex(ValueError, "batch result mismatch"):
                run_ragas(
                    input_run_dir=source_run,
                    output_root=temp_path / "out",
                    config=RagasRunnerConfig(run_id="ragas-incomplete", metric_names=("faithfulness",)),
                    evaluator=IncompleteEvaluator(),
                )

    def test_ollama_embedding_adapter_uses_batch_endpoint(self) -> None:
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self) -> bytes:
                return json.dumps({"embeddings": [[0.1, 0.2], [0.3, 0.4]]}).encode("utf-8")

        with mock.patch("urllib.request.urlopen", return_value=FakeResponse()) as urlopen:
            embeddings = _build_embeddings("ollama/bge-m3:latest")
            vectors = embeddings.embed_documents(["alpha", "beta"])

        self.assertEqual([[0.1, 0.2], [0.3, 0.4]], vectors)
        request = urlopen.call_args.args[0]
        self.assertTrue(request.full_url.endswith("/api/embed"))

    def test_ollama_embedding_adapter_records_and_sends_explicit_gpu_layer_limit(self) -> None:
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self) -> bytes:
                return json.dumps({"embeddings": [[0.1, 0.2]]}).encode("utf-8")

        config = RagasRunnerConfig(
            run_id="ollama-embedding-profile",
            embedding_model="ollama/bge-m3:latest",
            embedding_num_gpu=1,
        )
        with mock.patch("urllib.request.urlopen", return_value=FakeResponse()) as urlopen:
            embeddings = _build_embeddings(config.embedding_model, ollama_num_gpu=config.embedding_num_gpu)
            vectors = embeddings.embed_documents(["alpha"])

        self.assertEqual([[0.1, 0.2]], vectors)
        self.assertEqual(1, config.as_dict()["embeddingNumGpu"])
        request = urlopen.call_args.args[0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertEqual({"num_gpu": 1}, payload["options"])

    def test_embedding_num_gpu_must_be_non_negative(self) -> None:
        with self.assertRaisesRegex(ValueError, "embedding_num_gpu must be non-negative"):
            RagasRunnerConfig(run_id="invalid-embedding-num-gpu", embedding_num_gpu=-1)

    def test_clean_base_url_strips_env_file_semicolon(self) -> None:
        self.assertEqual("https://api.deepseek.com", _clean_base_url('"https://api.deepseek.com";'))

    def test_ollama_judge_uses_local_defaults_and_disables_thinking(self) -> None:
        config = RagasRunnerConfig(run_id="ollama-judge", judge_provider="ollama", judge_model="qwen3.5:9b")

        with mock.patch.dict("os.environ", {}, clear=True):
            self.assertEqual("ollama-local", _provider_api_key("ollama"))
            self.assertEqual("http://127.0.0.1:11434/v1", _provider_base_url("ollama"))

        self.assertEqual(
            {"temperature": 0.0, "max_tokens": 1024, "extra_body": {"reasoning_effort": "none"}},
            _judge_llm_kwargs(config),
        )
        self.assertFalse(config.as_dict()["judgeThink"])
        self.assertEqual("json_schema", config.as_dict()["judgeStructuredOutputMode"])
        self.assertEqual(1, config.as_dict()["responseRelevancyStrictness"])

    def test_non_ollama_judge_does_not_send_thinking_override(self) -> None:
        config = RagasRunnerConfig(run_id="deepseek-judge")

        self.assertEqual(
            {"temperature": 0.0, "max_tokens": 1024},
            _judge_llm_kwargs(config),
        )
        self.assertIsNone(config.as_dict()["judgeThink"])
        self.assertEqual("ragas_default", config.as_dict()["judgeStructuredOutputMode"])
        self.assertEqual(3, config.as_dict()["responseRelevancyStrictness"])

    def test_zai_judge_uses_provider_thinking_disable_when_explicit(self) -> None:
        config = RagasRunnerConfig(run_id="zai-no-think", judge_provider="zai", judge_think=False)

        self.assertEqual(
            {"temperature": 0.0, "max_tokens": 1024, "extra_body": {"thinking": {"type": "disabled"}}},
            _judge_llm_kwargs(config),
        )
        self.assertFalse(config.as_dict()["judgeThink"])
        self.assertEqual("ragas_default", config.as_dict()["judgeStructuredOutputMode"])

    def test_zhipu_judge_uses_provider_thinking_enable_when_explicit(self) -> None:
        config = RagasRunnerConfig(run_id="zhipu-think", judge_provider="zhipu", judge_think=True)

        self.assertEqual(
            {"temperature": 0.0, "max_tokens": 1024, "extra_body": {"thinking": {"type": "enabled"}}},
            _judge_llm_kwargs(config),
        )
        self.assertTrue(config.as_dict()["judgeThink"])

    def test_ollama_judge_uses_native_json_schema_mode(self) -> None:
        import instructor

        config = RagasRunnerConfig(run_id="ollama-json-schema", judge_provider="ollama", judge_model="qwen3.5:9b-ragas")
        with (
            mock.patch("openai.AsyncOpenAI", return_value=mock.sentinel.raw_client) as async_openai,
            mock.patch("instructor.from_openai", return_value=mock.sentinel.patched_client) as from_openai,
            mock.patch("ragas.llms.base.InstructorLLM", return_value=mock.sentinel.llm) as instructor_llm,
        ):
            llm = _build_judge_llm(config, {"api_key": "ollama-local", "base_url": "http://127.0.0.1:11434/v1"})

        self.assertIs(mock.sentinel.llm, llm)
        async_openai.assert_called_once_with(api_key="ollama-local", base_url="http://127.0.0.1:11434/v1")
        from_openai.assert_called_once_with(mock.sentinel.raw_client, mode=instructor.Mode.JSON_SCHEMA)
        instructor_llm.assert_called_once_with(
            client=mock.sentinel.patched_client,
            model="qwen3.5:9b-ragas",
            provider="ollama",
            temperature=0.0,
            max_tokens=1024,
            extra_body={"reasoning_effort": "none"},
        )

    def test_llamacpp_judge_uses_local_defaults_and_native_json_schema_mode(self) -> None:
        config = RagasRunnerConfig(run_id="llamacpp-json-schema", judge_provider="llamacpp", judge_model="qwen3.5-9b-ragas")

        with mock.patch.dict("os.environ", {}, clear=True):
            self.assertEqual("llamacpp-local", _provider_api_key("llamacpp"))
            self.assertEqual("http://127.0.0.1:8080/v1", _provider_base_url("llamacpp"))

        self.assertEqual(
            {"temperature": 0.0, "max_tokens": 1024, "extra_body": {"reasoning_effort": "none"}},
            _judge_llm_kwargs(config),
        )
        self.assertFalse(config.as_dict()["judgeThink"])
        self.assertEqual("json_schema", config.as_dict()["judgeStructuredOutputMode"])
        self.assertEqual(1, config.as_dict()["responseRelevancyStrictness"])

    def test_response_relevancy_strictness_can_be_explicitly_overridden(self) -> None:
        config = RagasRunnerConfig(
            run_id="explicit-response-relevancy-strictness",
            judge_provider="llamacpp",
            response_relevancy_strictness=2,
        )

        self.assertEqual(2, config.as_dict()["responseRelevancyStrictness"])

    def test_response_relevancy_strictness_must_be_positive(self) -> None:
        with self.assertRaisesRegex(ValueError, "response_relevancy_strictness must be positive"):
            RagasRunnerConfig(run_id="invalid-response-relevancy-strictness", response_relevancy_strictness=0)

    def test_judge_context_tokens_are_recorded_and_must_be_positive(self) -> None:
        config = RagasRunnerConfig(run_id="judge-context", judge_context_tokens=131072)

        self.assertEqual(131072, config.as_dict()["judgeContextTokens"])
        with self.assertRaisesRegex(ValueError, "judge_context_tokens must be positive"):
            RagasRunnerConfig(run_id="invalid-judge-context", judge_context_tokens=0)

    def test_judge_run_config_records_timeout_and_workers(self) -> None:
        config = RagasRunnerConfig(run_id="judge-run-config", judge_timeout_seconds=600, judge_max_workers=1)

        self.assertEqual(600, config.as_dict()["judgeTimeoutSeconds"])
        self.assertEqual(1, config.as_dict()["judgeMaxWorkers"])
        run_config = _build_run_config(config)
        self.assertEqual(600, run_config.timeout)
        self.assertEqual(1, run_config.max_workers)

    def test_judge_run_config_must_be_positive(self) -> None:
        with self.assertRaisesRegex(ValueError, "judge_timeout_seconds must be positive"):
            RagasRunnerConfig(run_id="invalid-timeout", judge_timeout_seconds=0)
        with self.assertRaisesRegex(ValueError, "judge_max_workers must be positive"):
            RagasRunnerConfig(run_id="invalid-workers", judge_max_workers=0)

    def test_extract_metric_accepts_parameterized_ragas_column(self) -> None:
        row = {"factual_correctness(mode=f1)": 1.0}
        self.assertEqual(1.0, _extract_metric(row, "factual_correctness"))


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
