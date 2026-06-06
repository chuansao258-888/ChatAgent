from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from chatagent_eval.deterministic_metrics import (
    hit_at_k,
    ndcg_at_k,
    phrase_recall,
    precision_at_k,
    recall_at_k,
    reciprocal_rank,
)
from chatagent_eval.parameters import config_fingerprint, validate_parameter_space
from chatagent_eval.reports import build_manifest, write_run_artifacts
from chatagent_eval.schemas import load_json, validate
from chatagent_eval.thresholds import evaluate_thresholds
from chatagent_eval.tuning import grid_trials, random_trials, select_champion

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class CoreContractTest(unittest.TestCase):
    def test_shared_schema_fixtures_validate(self) -> None:
        pairs = [
            ("eval-sample.schema.json", "sample-valid.json"),
            ("eval-report.schema.json", "report-valid.json"),
            ("eval-run-manifest.schema.json", "manifest-valid.json"),
            ("eval-parameter-space.schema.json", "parameter-space-valid.json"),
            ("eval-trial.schema.json", "trial-valid.json"),
        ]
        for schema_name, fixture_name in pairs:
            with self.subTest(schema=schema_name):
                validate(
                    load_json(RESOURCE_ROOT / "fixtures" / fixture_name),
                    load_json(RESOURCE_ROOT / "schemas" / schema_name),
                )

    def test_schema_rejects_missing_required_field(self) -> None:
        sample = load_json(RESOURCE_ROOT / "fixtures" / "sample-valid.json")
        del sample["sampleId"]
        with self.assertRaisesRegex(ValueError, "sampleId"):
            validate(sample, load_json(RESOURCE_ROOT / "schemas" / "eval-sample.schema.json"))

    def test_deterministic_metrics(self) -> None:
        parity = load_json(RESOURCE_ROOT / "fixtures" / "core-contract-parity.json")["retrievalMetrics"]
        retrieved = parity["retrieved"]
        relevant = set(parity["relevant"])
        k = parity["k"]
        expected = parity["expected"]
        self.assertEqual(expected["hitAt3"], hit_at_k(retrieved, relevant, k))
        self.assertAlmostEqual(expected["recallAt3"], recall_at_k(retrieved, relevant, k))
        self.assertAlmostEqual(expected["precisionAt3"], precision_at_k(retrieved, relevant, k))
        self.assertEqual(expected["mrr"], reciprocal_rank(retrieved, relevant))
        self.assertAlmostEqual(expected["ndcgAt3"], ndcg_at_k(retrieved, relevant, k))
        self.assertEqual(0.5, phrase_recall(["Required   Phrase appears."], ["required phrase", "missing"]))

    def test_thresholds_preserve_fail_over_warn(self) -> None:
        result = evaluate_thresholds(
            {"recall": 0.7, "latency": 3500.0},
            {
                "recall": {"min": 0.8, "severity": "fail"},
                "latency": {"max": 3000.0, "severity": "warn"},
            },
        )
        self.assertEqual("fail", result["status"])
        with self.assertRaisesRegex(ValueError, "severity"):
            evaluate_thresholds({"recall": 0.7}, {"recall": {"min": 0.8, "severity": "ignore"}})

    def test_parameter_validation_and_fingerprint_are_reproducible(self) -> None:
        space = load_json(RESOURCE_ROOT / "fixtures" / "parameter-space-valid.json")
        validate_parameter_space(space)
        self.assertEqual(config_fingerprint({"topK": 3, "rrfK": 60}), config_fingerprint({"rrfK": 60, "topK": 3}))
        parity = load_json(RESOURCE_ROOT / "fixtures" / "core-contract-parity.json")["configFingerprint"]
        self.assertEqual(parity["sha256"], config_fingerprint(parity["config"]))
        invalid = copy.deepcopy(space)
        invalid["parameters"][0]["values"] = ["three"]
        with self.assertRaisesRegex(ValueError, "integer"):
            validate_parameter_space(invalid)

    def test_trials_and_champion_selection_are_deterministic(self) -> None:
        space = load_json(RESOURCE_ROOT / "fixtures" / "parameter-space-valid.json")
        self.assertEqual(6, len(grid_trials(space, experiment_id="exp")))
        first = random_trials(space, experiment_id="exp", budget=3, random_seed=42)
        second = random_trials(space, experiment_id="exp", budget=3, random_seed=42)
        self.assertEqual(first, second)

        trials = [
            _completed_trial("a", 0.90, 500.0, []),
            _completed_trial("b", 0.92, 700.0, ["latency"]),
            _completed_trial("c", 0.90, 400.0, []),
            _completed_trial("d", 0.90, None, []),
        ]
        champion = select_champion(trials, primary_metric="ndcg", direction="maximize")
        self.assertEqual("c", champion["trialId"])

    def test_non_tuning_manifest_omits_tuning_fields(self) -> None:
        manifest = build_manifest(
            run_id="run-1",
            suite="rag-retrieval",
            mode="smoke",
            timestamp="2026-06-06T00:00:00Z",
            git_branch="branch",
            git_sha="sha",
            dataset_id="dataset",
            dataset_hash="hash",
            config={"topK": 3},
            config_fingerprint="fingerprint",
        )
        self.assertNotIn("tuning", manifest)
        validate(manifest, load_json(RESOURCE_ROOT / "schemas" / "eval-run-manifest.schema.json"))
        with tempfile.TemporaryDirectory() as temp_dir:
            run_dir = write_run_artifacts(Path(temp_dir), manifest, {"status": "pass"})
            written = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertNotIn("tuning", written)

    def test_artifact_writer_rejects_run_directory_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_root = Path(temp_dir) / "eval"
            with self.assertRaisesRegex(ValueError, "escapes output root"):
                write_run_artifacts(output_root, {"runId": ".."}, {"status": "pass"})


def _completed_trial(trial_id: str, ndcg: float, latency: float | None, gate_failures: list[str]) -> dict:
    return {
        "trialId": trial_id,
        "configFingerprint": trial_id,
        "status": "completed",
        "metrics": {"ndcg": ndcg},
        "gateFailures": gate_failures,
        "latencyP95Ms": latency,
        "costUsd": 0.0,
    }


if __name__ == "__main__":
    unittest.main()
