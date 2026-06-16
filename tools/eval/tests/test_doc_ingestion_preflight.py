from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from chatagent_eval.doc_ingestion_preflight import DocIngestionPreflightConfig, run_doc_ingestion_preflight


class DocIngestionPreflightTest(unittest.TestCase):
    def test_preflight_writes_report_without_secret_values(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "dataset")
            output_root = temp_path / "out"

            with _env_ready():
                run_dir = run_doc_ingestion_preflight(
                    output_root=output_root,
                    config=DocIngestionPreflightConfig(
                        run_id="phase10d-preflight",
                        dataset_root=dataset_root,
                        expected_samples=585,
                        max_provider_calls=100000,
                        max_wall_clock_minutes=400,
                        estimated_minutes_per_candidate=120,
                    ),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=_pass_metrics,
                    probe_ragas_scoring=_pass_ragas_scoring,
                )

            preflight = json.loads((run_dir / "preflight.json").read_text(encoding="utf-8"))
            serialized = json.dumps(preflight, ensure_ascii=False)
            self.assertEqual("pass", preflight["status"])
            self.assertIn("CHATAGENT_DB_PASSWORD", serialized)
            self.assertNotIn("secret-password", serialized)
            self.assertEqual([24, 32, 50], preflight["checks"][-1]["candidateK"]["approved"])
            self.assertEqual("pass", _check(preflight, "service.redis")["status"])

    def test_preflight_accepts_shared_zhipu_key_without_serializing_value(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            env = {
                "CHATAGENT_DB_URL": "jdbc:postgresql://localhost:5432/chatagent",
                "CHATAGENT_DB_USERNAME": "eval_user",
                "CHATAGENT_DB_PASSWORD": "secret-password",
                "CHATAGENT_ZHIPUAI_API_KEY": "secret-zhipu-key",
            }
            with mock.patch.dict(os.environ, env, clear=True):
                run_dir = run_doc_ingestion_preflight(
                    output_root=Path(temp_dir) / "out",
                    config=DocIngestionPreflightConfig(run_id="phase10d-preflight"),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=_pass_metrics,
                    probe_ragas_scoring=_pass_ragas_scoring,
                )

            preflight = json.loads((run_dir / "preflight.json").read_text(encoding="utf-8"))
            serialized = json.dumps(preflight, ensure_ascii=False)
            self.assertEqual("pass", _check(preflight, "env.ragasJudgeApiKey")["status"])
            self.assertEqual("pass", _check(preflight, "env.answerLlmApiKey")["status"])
            self.assertIn("CHATAGENT_ZHIPUAI_API_KEY", serialized)
            self.assertNotIn("secret-zhipu-key", serialized)

    def test_preflight_fails_when_factual_correctness_metric_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with _env_ready():
                run_dir = run_doc_ingestion_preflight(
                    output_root=Path(temp_dir) / "out",
                    config=DocIngestionPreflightConfig(run_id="phase10d-preflight"),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=lambda metrics: {
                        "version": "0.4.0",
                        "metricClasses": {"faithfulness": "Faithfulness"},
                        "unavailableMetrics": ["factual_correctness"],
                    },
                    probe_ragas_scoring=_pass_ragas_scoring,
                )

            preflight = json.loads((run_dir / "preflight.json").read_text(encoding="utf-8"))
            metric_check = _check(preflight, "ragas.metrics")
            self.assertEqual("fail", preflight["status"])
            self.assertEqual(["factual_correctness"], metric_check["unavailableMetrics"])

    def test_preflight_rejects_existing_run_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_root = Path(temp_dir) / "out"
            (output_root / "phase10d-preflight").mkdir(parents=True)

            with self.assertRaisesRegex(ValueError, "already exists"):
                run_doc_ingestion_preflight(
                    output_root=output_root,
                    config=DocIngestionPreflightConfig(run_id="phase10d-preflight"),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=_pass_metrics,
                    probe_ragas_scoring=_pass_ragas_scoring,
                )

    def test_candidate_k_budget_fails_closed_when_declared_budget_is_too_low(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with _env_ready():
                run_dir = run_doc_ingestion_preflight(
                    output_root=Path(temp_dir) / "out",
                    config=DocIngestionPreflightConfig(
                        run_id="phase10d-preflight",
                        expected_samples=585,
                        max_provider_calls=1000,
                        max_wall_clock_minutes=180,
                        estimated_minutes_per_candidate=120,
                    ),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=_pass_metrics,
                    probe_ragas_scoring=_pass_ragas_scoring,
                )

            preflight = json.loads((run_dir / "preflight.json").read_text(encoding="utf-8"))
            candidate_k = _check(preflight, "budget.candidateK")["candidateK"]
            self.assertEqual("fail", preflight["status"])
            self.assertEqual([], candidate_k["approved"])
            self.assertEqual([24, 32, 50], [item["candidateK"] for item in candidate_k["skipped"]])

    def test_candidate_k_budget_approves_only_the_affordable_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with _env_ready():
                run_dir = run_doc_ingestion_preflight(
                    output_root=Path(temp_dir) / "out",
                    config=DocIngestionPreflightConfig(
                        run_id="phase10d-preflight",
                        expected_samples=585,
                        max_provider_calls=50000,
                        max_wall_clock_minutes=250,
                        estimated_minutes_per_candidate=120,
                    ),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=_pass_metrics,
                    probe_ragas_scoring=_pass_ragas_scoring,
                )

            preflight = json.loads((run_dir / "preflight.json").read_text(encoding="utf-8"))
            candidate_k = _check(preflight, "budget.candidateK")["candidateK"]
            self.assertEqual([24, 32], candidate_k["approved"])
            self.assertEqual([50], [item["candidateK"] for item in candidate_k["skipped"]])
            self.assertEqual(3510, candidate_k["approvedEstimatedProviderCallsTotal"])
            self.assertEqual(240, candidate_k["approvedEstimatedWallClockMinutesTotal"])

    def test_preflight_fails_when_scoring_smoke_has_null_metric(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with _env_ready():
                run_dir = run_doc_ingestion_preflight(
                    output_root=Path(temp_dir) / "out",
                    config=DocIngestionPreflightConfig(run_id="phase10d-preflight"),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=_pass_metrics,
                    probe_ragas_scoring=lambda config: {
                        "status": "pass",
                        "scores": {"faithfulness": 0.9, "factual_correctness": None},
                    },
                )

            preflight = json.loads((run_dir / "preflight.json").read_text(encoding="utf-8"))
            scoring = _check(preflight, "ragas.scoringSmoke")
            self.assertEqual("fail", preflight["status"])
            self.assertIn("factual_correctness", scoring["missingNumericMetrics"])

    def test_preflight_fails_when_scoring_smoke_returns_boolean_metric(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with _env_ready():
                run_dir = run_doc_ingestion_preflight(
                    output_root=Path(temp_dir) / "out",
                    config=DocIngestionPreflightConfig(
                        run_id="phase10d-preflight",
                        ragas_metrics=("faithfulness",),
                    ),
                    probe_tcp=_pass_tcp,
                    probe_http=_pass_http,
                    probe_metrics=_pass_metrics,
                    probe_ragas_scoring=lambda config: {
                        "status": "pass",
                        "scores": {"faithfulness": True},
                    },
                )

            preflight = json.loads((run_dir / "preflight.json").read_text(encoding="utf-8"))
            scoring = _check(preflight, "ragas.scoringSmoke")
            self.assertEqual("fail", preflight["status"])
            self.assertEqual(["faithfulness"], scoring["missingNumericMetrics"])
            self.assertEqual([], scoring["numericMetrics"])


def _write_dataset_root(path: Path) -> Path:
    (path / "manifests" / "datasets").mkdir(parents=True)
    (path / "manifests" / "splits").mkdir(parents=True)
    (path / "datasets" / "doc-ingestion").mkdir(parents=True)
    (path / "manifests" / "datasets" / "doc-ingestion-retrieval-v1.json").write_text(
        json.dumps({"localPath": "datasets/doc-ingestion/doc-ingestion-retrieval-v1.jsonl"}),
        encoding="utf-8",
    )
    (path / "manifests" / "splits" / "doc-ingestion-retrieval-v1.json").write_text(
        json.dumps({"splits": []}),
        encoding="utf-8",
    )
    (path / "datasets" / "doc-ingestion" / "doc-ingestion-retrieval-v1.jsonl").write_text("", encoding="utf-8")
    return path


def _env_ready() -> mock._patch_dict:
    return mock.patch.dict(
        os.environ,
        {
            "CHATAGENT_DB_URL": "jdbc:postgresql://localhost:5432/chatagent",
            "CHATAGENT_DB_USERNAME": "eval_user",
            "CHATAGENT_DB_PASSWORD": "secret-password",
            "CHATAGENT_EVAL_RAGAS_LLM_API_KEY": "secret-judge-key",
            "CHATAGENT_EVAL_ANSWER_LLM_API_KEY": "secret-answer-key",
        },
    )


def _pass_tcp(host: str, port: int, timeout: float) -> tuple[str, str | None]:
    return "pass", None


def _pass_http(url: str, timeout: float) -> tuple[str, str | None]:
    return "pass", "HTTP 200"


def _pass_metrics(metrics: tuple[str, ...]) -> dict[str, object]:
    return {
        "version": "0.4.0",
        "metricClasses": {metric: metric for metric in metrics},
        "unavailableMetrics": [],
    }


def _pass_ragas_scoring(config: DocIngestionPreflightConfig) -> dict[str, object]:
    return {
        "status": "pass",
        "scores": {metric: 0.8 for metric in config.ragas_metrics},
    }


def _check(preflight: dict[str, object], check_id: str) -> dict[str, object]:
    for check in preflight["checks"]:
        if check["id"] == check_id:
            return check
    raise AssertionError(f"missing check: {check_id}")


if __name__ == "__main__":
    unittest.main()
