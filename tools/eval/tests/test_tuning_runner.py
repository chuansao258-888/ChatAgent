from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import run_eval
from chatagent_eval.datasets import sha256_file
from chatagent_eval.parameters import validate_parameter_registry, validate_registry_coverage, validate_tuning_policy
from chatagent_eval.promotion import split_audit, validate_search_splits
from chatagent_eval.schemas import load_json, validate
from chatagent_eval.tuning import bootstrap_confidence_interval, build_experiment_trials, pareto_frontier, select_champion
from chatagent_eval.tuning_runner import TuningConfig, run_tuning_experiment

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class TuningRunnerTest(unittest.TestCase):
    def test_registry_and_all_committed_parameter_spaces_validate(self) -> None:
        registry = load_json(RESOURCE_ROOT / "parameter-registry-v1.json")
        spaces = [
            load_json(RESOURCE_ROOT / "parameter-spaces" / name)
            for name in (
                "rag-retrieval-v1.json",
                "text-recall-v1.json",
                "memory-v2-v1.json",
                "agent-modules-v1.json",
            )
        ]
        validate(registry, load_json(RESOURCE_ROOT / "schemas" / "eval-parameter-registry.schema.json"))
        validate_parameter_registry(registry)
        validate_registry_coverage(registry, spaces)
        for suite, resource_id in (
            ("agent-modules", "agent-modules-v1"),
            ("memory-v2", "memory-v2-v1"),
            ("text-recall", "text-recall-v1"),
        ):
            space = load_json(RESOURCE_ROOT / "parameter-spaces" / f"{resource_id}.json")
            policy = load_json(RESOURCE_ROOT / "tuning-policies" / f"{resource_id}.json")
            validate_tuning_policy(policy, space, registry, suite)

    def test_search_split_policy_rejects_holdout_and_overlap(self) -> None:
        with self.assertRaisesRegex(ValueError, "cannot access"):
            validate_search_splits(("development", "holdout"))
        manifest = {
            "splits": {
                "development": {"groupHash": "sha256:dev", "groupIds": ["same"]},
                "holdout": {"groupHash": "sha256:holdout", "groupIds": ["same"]},
            }
        }
        with self.assertRaisesRegex(ValueError, "overlap"):
            split_audit(manifest, search_splits=("development",), holdout_split="holdout", challenge_split=None)

    def test_trials_confidence_and_pareto_are_reproducible(self) -> None:
        space = {
            "id": "space",
            "version": "1",
            "primaryMetric": "quality",
            "direction": "maximize",
            "parameters": [
                {"id": "topK", "classification": "quality-tunable", "type": "integer", "values": [1, 3]}
            ],
        }
        trials = build_experiment_trials(
            space,
            experiment_id="exp",
            baseline_parameters={"topK": 3},
            strategy="grid",
            combination_budget=2,
            random_seed=7,
        )
        self.assertEqual(["baseline", "sensitivity"], [trial["stage"] for trial in trials])
        first = bootstrap_confidence_interval([0.0, 1.0, 1.0], random_seed=7, resamples=50)
        second = bootstrap_confidence_interval([0.0, 1.0, 1.0], random_seed=7, resamples=50)
        self.assertEqual(first, second)
        completed = [
            {"status": "completed", "gateFailures": [], "configFingerprint": "a", "metrics": {"quality": 1.0}, "latencyP95Ms": 20.0, "costUsd": 0.0},
            {"status": "completed", "gateFailures": [], "configFingerprint": "b", "metrics": {"quality": 0.9}, "latencyP95Ms": 10.0, "costUsd": 0.0},
            {"status": "completed", "gateFailures": [], "configFingerprint": "c", "metrics": {"quality": 0.8}, "latencyP95Ms": 30.0, "costUsd": 0.0},
        ]
        frontier = pareto_frontier(
            completed,
            objectives=[("quality", "maximize"), ("latencyP95Ms", "minimize"), ("costUsd", "minimize")],
        )
        self.assertEqual(["a", "b"], [trial["configFingerprint"] for trial in frontier])

    def test_champion_prefers_baseline_when_quality_is_tied(self) -> None:
        trials = [
            {
                "status": "completed",
                "gateFailures": [],
                "configFingerprint": "candidate",
                "parameters": {"topK": 5},
                "metrics": {"quality": 1.0},
                "latencyP95Ms": None,
                "costUsd": 0.0,
            },
            {
                "status": "completed",
                "gateFailures": [],
                "configFingerprint": "baseline",
                "parameters": {"topK": 3},
                "metrics": {"quality": 1.0},
                "latencyP95Ms": None,
                "costUsd": 0.0,
            },
        ]

        champion = select_champion(
            trials,
            primary_metric="quality",
            direction="maximize",
            baseline_parameters={"topK": 3},
        )

        self.assertEqual("baseline", champion["configFingerprint"])

    def test_run_tuning_experiment_seals_holdout_and_writes_promotion_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            experiment_dir = run_tuning_experiment(
                dataset_root=dataset_root,
                output_root=temp_path / "out",
                parameter_space_path=RESOURCE_ROOT / "parameter-spaces" / "agent-modules-v1.json",
                policy_path=RESOURCE_ROOT / "tuning-policies" / "agent-modules-v1.json",
                registry_path=RESOURCE_ROOT / "parameter-registry-v1.json",
                config=TuningConfig(
                    experiment_id="agent-tuning-test",
                    suite="agent-modules",
                    combination_budget=2,
                    random_seed=9,
                    max_samples_per_trial=4,
                    holdout_max_samples=2,
                    confidence_resamples=30,
                    git_sha="test-sha",
                ),
            )

            expected_files = {
                "experiment-manifest.json",
                "parameter-space.yaml",
                "trials.jsonl",
                "leaderboard.csv",
                "pareto-frontier.json",
                "champion-candidate.yaml",
                "holdout-verification.json",
                "promotion-decision.md",
            }
            self.assertEqual(expected_files, {path.name for path in experiment_dir.iterdir() if path.is_file()})
            manifest = load_json(experiment_dir / "experiment-manifest.json")
            trials = _read_jsonl(experiment_dir / "trials.jsonl")
            holdout = load_json(experiment_dir / "holdout-verification.json")
            candidate = load_json(experiment_dir / "champion-candidate.yaml")
            self.assertFalse(manifest["sealedHoldoutHashIncludedInIterativeTrials"])
            self.assertTrue(manifest["championSelectedBeforeHoldoutAccess"])
            self.assertTrue(holdout["holdoutOpenedAfterChampionSelection"])
            self.assertEqual(0, holdout["overlapCount"])
            self.assertEqual([], holdout["verificationGateFailures"])
            self.assertFalse(candidate["productionDefaultsChanged"])
            self.assertTrue(candidate["requiresSeparateReviewedChange"])
            frontier = load_json(experiment_dir / "pareto-frontier.json")["trials"]
            self.assertLess(len(frontier), len(trials))
            for trial in trials:
                self.assertNotIn("holdout", trial.get("splitHashes", {}))
                self.assertIsNone(trial["latencyP95Ms"])
                self.assertGreater(trial["executionElapsedMs"], 0.0)
                validate(trial, load_json(RESOURCE_ROOT / "schemas" / "eval-trial.schema.json"))

    def test_cli_tune_suite_runs_without_provider(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "tune-suite",
                        "--suite",
                        "agent-modules",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--experiment-id",
                        "cli-tuning",
                        "--combination-budget",
                        "1",
                        "--max-samples-per-trial",
                        "4",
                        "--holdout-max-samples",
                        "2",
                        "--confidence-resamples",
                        "20",
                    ]
                )

            self.assertEqual(0, exit_code)
            self.assertTrue((temp_path / "out" / "cli-tuning" / "promotion-decision.md").exists())


def _write_dataset_root(path: Path) -> Path:
    rows = [
        _row("calibration-a", "group-calibration", "calibration", "Clarification", True),
        _row("development-a", "group-development", "development", "Follow-up", True),
        _row("holdout-a", "group-holdout", "holdout", "Clarification", True),
        _row("challenge-a", "group-challenge", "challenge", "N/A", False),
    ]
    dataset_path = path / "datasets" / "memory" / "memory-v2-dialogues.jsonl"
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    split_manifest = {
        "schemaVersion": 1,
        "datasetId": "memory-v2-dialogues",
        "splits": {
            "calibration": {"groupHash": "sha256:calibration", "groupIds": ["group-calibration"]},
            "development": {"groupHash": "sha256:development", "groupIds": ["group-development"]},
            "holdout": {"groupHash": "sha256:holdout", "groupIds": ["group-holdout"]},
            "challenge": {"groupHash": "sha256:challenge", "groupIds": ["group-challenge"]},
        },
    }
    split_path = path / "manifests" / "splits" / "memory-v2-dialogues.json"
    split_path.parent.mkdir(parents=True, exist_ok=True)
    split_path.write_text(json.dumps(split_manifest), encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "datasetId": "memory-v2-dialogues",
        "version": 2,
        "sourceIds": ["mtrag-human"],
        "recordSchema": "eval-memory-dataset-record.schema.json",
        "localPath": "datasets/memory/memory-v2-dialogues.jsonl",
        "datasetHash": sha256_file(dataset_path),
        "splitManifestPath": "manifests/splits/memory-v2-dialogues.json",
        "splitManifestHash": sha256_file(split_path),
        "recordCount": len(rows),
        "groupCount": len(rows),
        "splits": {
            split: {"recordCount": 1, "groupCount": 1, "groupHash": details["groupHash"]}
            for split, details in split_manifest["splits"].items()
        },
    }
    manifest_path = path / "manifests" / "datasets" / "memory-v2-dialogues.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def _row(sample_id: str, group_id: str, split: str, multi_turn: str, answerable: bool) -> dict:
    turns = [{"speaker": "user", "text": "Where do the Arizona Cardinals play?"}]
    if multi_turn != "N/A":
        turns.extend(
            [
                {"speaker": "agent", "text": "The Cardinals are an NFL team."},
                {"speaker": "user", "text": "Do they play outside the US?"},
            ]
        )
    return {
        "sampleId": sample_id,
        "datasetId": "memory-v2-dialogues",
        "sourceGroupId": group_id,
        "split": split,
        "turns": turns,
        "expectedResponse": "The Arizona Cardinals played in London." if answerable else "I do not have the answer.",
        "referenceContextIds": ["doc-1"] if answerable else [],
        "metadata": {
            "answerability": ["ANSWERABLE" if answerable else "UNANSWERABLE"],
            "domain": f"domain-{split}",
            "multiTurn": [multi_turn],
            "questionType": ["Explanation"],
            "sourceTaskId": sample_id,
        },
    }


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


if __name__ == "__main__":
    unittest.main()
